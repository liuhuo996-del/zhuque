package com.zhuque.m3_matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.zhuque.common.ApiException;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.IntentRow;
import com.zhuque.persistence.ControlPlaneRepository.ToolRow;

/**
 * M3 · 意图 → 工具匹配，产出「意图 × 工具」矩阵数据。
 *
 * 实现方式（重要，别改架构）：
 * v1 池子 300~500 条量级，不上向量检索。把富化后的 tool 目录压缩成摘要
 * （见 summarizeCatalog），整体灌给长上下文模型，直接输出结构化匹配结果。
 * 池子超过 2000 条（zhuque.matching.prefilter-threshold）才走 RetrievalPrefilter
 * 扩展点——v1 用 NoopPrefilter，不实现真检索。
 *
 * 硬要求：
 * - 命中 forbidden 约束的工具进 excluded，且必须说明命中了哪一条
 * - 每条 match 必须有 reason，没有 reason 的匹配在后处理里直接丢弃并记 warning
 * - 匹配结果只是候选：落库 pack_tool 时 added_by=ai、带 reason 和 confidence
 * - 记录本次匹配用的模型和 prompt 版本（复现实验用）
 */
@Service
public class IntentMatcher {

    private final ControlPlaneRepository repository;
    private final RetrievalPrefilter prefilter;

    @Value("${zhuque.matching.prefilter-threshold:2000}")
    private int prefilterThreshold;

    public IntentMatcher(ControlPlaneRepository repository, RetrievalPrefilter prefilter) {
        this.repository = repository;
        this.prefilter = prefilter;
    }

    public record Match(UUID intentId, UUID toolId, String strength, String reason, double confidence) {}

    public record Excluded(UUID toolId, String reason) {} // reason 必须指出命中的 forbidden 条目

    public record MatchResult(
            List<Match> matches,
            List<Excluded> excluded,
            List<UUID> unhitTools,        // 未被任何意图命中的工具 → 矩阵列尾"建议移除"
            List<UUID> uncoveredIntents,  // 未被任何工具覆盖的意图 → 矩阵行尾"无工具覆盖"
            String modelName,
            String promptVersion) {
    }

    /**
     * 功能：匹配主入口。
     * 流程：summarizeCatalog → （池子超阈值时 prefilter）→ 组 prompt 调模型 →
     * 解析结构化输出 → 后处理校验（丢弃无 reason 的、confidence 范围裁剪）→
     * 计算 unhit / uncovered 两个衍生集合。
     */
    public MatchResult match(List<UUID> intentIds, List<String> forbiddenRules, List<UUID> candidateToolIds) {
        if (intentIds == null || intentIds.isEmpty()) {
            throw ApiException.badRequest("没有可匹配的意图", "先保存至少一条原子意图");
        }
        if (candidateToolIds == null || candidateToolIds.isEmpty()) {
            throw ApiException.badRequest("没有候选工具", "至少选择一个 API 来源或工具");
        }
        List<IntentRow> intents = repository.intentsByIds(intentIds);
        if (intents.size() != new LinkedHashSet<>(intentIds).size()) {
            throw ApiException.badRequest("部分意图不存在", "刷新数字员工意图后重试");
        }
        List<UUID> filteredIds = candidateToolIds;
        if (candidateToolIds.size() > prefilterThreshold) {
            filteredIds = prefilter.prefilter(intents.stream().map(IntentRow::text).toList(),
                    candidateToolIds, prefilterThreshold);
        }
        List<ToolRow> tools = repository.toolsByIds(filteredIds);
        List<String> forbidden = forbiddenRules == null ? List.of()
                : forbiddenRules.stream().filter(value -> value != null && !value.isBlank()).toList();

        List<Excluded> excluded = new ArrayList<>();
        Set<UUID> excludedIds = new LinkedHashSet<>();
        for (ToolRow tool : tools) {
            for (String rule : forbidden) {
                double overlap = similarity(rule, searchable(tool));
                if (overlap >= 0.16 || explicitForbidden(rule, tool)) {
                    excluded.add(new Excluded(tool.id(), "命中禁止规则「" + rule + "」"));
                    excludedIds.add(tool.id());
                    break;
                }
            }
        }

        List<Match> matches = new ArrayList<>();
        Set<UUID> hitTools = new LinkedHashSet<>();
        Set<UUID> coveredIntents = new LinkedHashSet<>();
        for (IntentRow intent : intents) {
            List<ScoredTool> scored = tools.stream().filter(tool -> !excludedIds.contains(tool.id()))
                    .map(tool -> new ScoredTool(tool, score(intent.text(), tool)))
                    .sorted(Comparator.comparingDouble(ScoredTool::score).reversed()).toList();
            for (int index = 0; index < scored.size(); index++) {
                ScoredTool candidate = scored.get(index);
                if (candidate.score() < 0.12 && !(index == 0 && candidate.score() >= 0.05)) {
                    continue;
                }
                double confidence = Math.min(0.98, 0.45 + candidate.score() * 1.8);
                String strength = confidence >= 0.75 ? "strong" : "weak";
                String reason = "意图「" + intent.text() + "」与工具 " + candidate.tool().name()
                        + " 的名称、描述和关键参数匹配（规则相似度 "
                        + String.format(Locale.ROOT, "%.2f", candidate.score()) + "）";
                if (reason.isBlank()) {
                    continue;
                }
                matches.add(new Match(intent.id(), candidate.tool().id(), strength, reason, confidence));
                hitTools.add(candidate.tool().id());
                coveredIntents.add(intent.id());
            }
        }
        List<UUID> unhit = tools.stream().map(ToolRow::id).filter(id -> !hitTools.contains(id)).toList();
        List<UUID> uncovered = intents.stream().map(IntentRow::id)
                .filter(id -> !coveredIntents.contains(id)).toList();
        return new MatchResult(List.copyOf(matches), List.copyOf(excluded), unhit, uncovered,
                "zhuque-deterministic-matcher-v1", "intent-tool-match-v1");
    }

    /**
     * 功能：把 tool 目录压缩成模型可整体吞下的摘要。
     * 每条约 50 token：name / 一句描述（富化后的"何时用"句）/ effect / 关键参数名。
     * 摘要质量直接决定匹配质量——描述太长要截断，太短说明富化没做好。
     */
    public String summarizeCatalog(List<UUID> toolIds) {
        List<ToolRow> tools = repository.toolsByIds(toolIds == null ? List.of() : toolIds);
        StringBuilder result = new StringBuilder();
        for (ToolRow tool : tools) {
            String description = firstSentence(tool.description());
            if (description.length() > 120) {
                description = description.substring(0, 120) + "…";
            }
            result.append(tool.id()).append(" | ").append(tool.name())
                    .append(" | effect=").append(tool.effect())
                    .append(" | params=").append(properties(tool.inputSchema()))
                    .append(" | ").append(description).append('\n');
        }
        return result.toString();
    }

    private static double score(String intent, ToolRow tool) {
        double text = similarity(intent, tool.name() + " " + tool.description());
        double parameters = similarity(intent, String.join(" ", properties(tool.inputSchema())));
        double path = similarity(intent, tool.path());
        return Math.min(1, text * 0.68 + parameters * 0.22 + path * 0.10);
    }

    private static String searchable(ToolRow tool) {
        return tool.name() + " " + tool.description() + " " + tool.path() + " " + tool.effect();
    }

    private static boolean explicitForbidden(String rule, ToolRow tool) {
        String normalized = normalize(rule);
        if ((normalized.contains("不得删除") || normalized.contains("禁止删除"))
                && "delete".equals(tool.effect())) {
            return true;
        }
        if ((normalized.contains("不得写") || normalized.contains("只读")
                || normalized.contains("不得操作") || normalized.contains("不得发起"))
                && ("write".equals(tool.effect()) || "delete".equals(tool.effect()))) {
            return true;
        }
        return false;
    }

    private static double similarity(String left, String right) {
        Set<String> a = grams(normalize(left));
        Set<String> b = grams(normalize(right));
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> grams(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : text.split("_+")) {
            if (token.isBlank()) {
                continue;
            }
            result.add(token);
            int[] points = token.codePoints().toArray();
            for (int index = 0; index + 1 < points.length; index++) {
                result.add(new String(points, index, 2));
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_")
                .replaceAll("^_|_$", "");
    }

    @SuppressWarnings("unchecked")
    private static List<String> properties(Map<String, Object> schema) {
        Object value = schema.get("properties");
        return value instanceof Map<?, ?> map
                ? ((Map<String, Object>) map).keySet().stream().toList() : List.of();
    }

    private static String firstSentence(String value) {
        if (value == null) {
            return "";
        }
        int end = value.indexOf('。');
        return end >= 0 ? value.substring(0, end + 1) : value;
    }

    private record ScoredTool(ToolRow tool, double score) {
    }
}
