package com.zhuque.m3_matching;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

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
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：把 tool 目录压缩成模型可整体吞下的摘要。
     * 每条约 50 token：name / 一句描述（富化后的"何时用"句）/ effect / 关键参数名。
     * 摘要质量直接决定匹配质量——描述太长要截断，太短说明富化没做好。
     */
    public String summarizeCatalog(List<UUID> toolIds) {
        throw new UnsupportedOperationException("TODO");
    }
}
