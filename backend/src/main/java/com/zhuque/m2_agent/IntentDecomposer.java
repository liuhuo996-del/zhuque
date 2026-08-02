package com.zhuque.m2_agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhuque.ai.AiModelClient;
import com.zhuque.common.ApiException;

/**
 * M2 · 职责描述 → 意图列表的 AI 拆解。
 *
 * v1 就是一个表单加一次 AI 拆解，人可以直接改结果；不做多轮对话式 onboarding。
 */
@Component
public class IntentDecomposer {

    private static final Pattern SPLIT = Pattern.compile("[。！？!?；;\\n]+|(?<=，)(?=(?:查询|查看|创建|修改|更新|删除|取消|发起|处理|生成|核对|跟进|拒绝))");
    private static final List<String> ACTION_PREFIXES = List.of(
            "查询", "查看", "检索", "搜索", "创建", "新增", "修改", "更新", "删除", "取消",
            "发起", "处理", "生成", "核对", "跟进", "关闭", "审核", "拒绝", "通知", "计算", "列出");

    private final AiModelClient ai;

    public IntentDecomposer(AiModelClient ai) {
        this.ai = ai;
    }

    public record DecomposeResult(
            List<String> intents,          // 有序；每条 source=ai 落库
            List<String> forbiddenRules,   // 负向约束列表，M3 匹配当排除规则用
            String splitAdvice) {          // 超过 15 条时的拆分建议，否则 null
    }

    /**
     * 功能：调模型把职责描述拆成意图列表。
     *
     * 拆解要求（写进 prompt，也写进后处理校验）：
     * - 每条意图是原子任务，动词开头（"查询订单状态"、"为符合条件的订单发起退款申请"）
     * - 8~12 条为宜；模型输出超过 15 条 → 不截断，原样返回 + splitAdvice
     *   提示"职责过宽，建议拆分为两个数字员工"
     * - forbiddenNotes 单独解析成负向约束列表，绝不混进正向意图
     * - 后处理：去重、剔除空条目、校验动词开头（不满足的原样保留但打标）
     *
     * 落库规则：每条 source=ai；用户在矩阵页编辑过的行由前端回传时改 source=human。
     */
    public DecomposeResult decompose(String description, String forbiddenNotes) {
        if (description == null || description.isBlank()) {
            throw ApiException.badRequest("职责描述不能为空", "描述该数字员工要完成的业务任务后再拆解");
        }
        List<String> intents = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        if (ai.available()) {
            JsonNode result = ai.completeJson("""
                    把数字员工职责拆成原子意图。只返回 JSON object：
                    {"intents":["动词开头的原子任务"],"forbiddenRules":["禁止事项"]}。
                    正向意图以 8~12 条为宜，不得把禁止事项混入 intents，不得虚构职责外能力。
                    """, "职责描述：" + description + "\n明确禁止的事：" + safe(forbiddenNotes)).orElse(null);
            if (result != null) {
                readStrings(result.path("intents"), intents);
                readStrings(result.path("forbiddenRules"), forbidden);
            }
        }
        if (intents.isEmpty()) {
            intents.addAll(split(description));
        }
        if (forbidden.isEmpty()) {
            forbidden.addAll(split(forbiddenNotes));
        }
        intents = clean(intents);
        forbidden = clean(forbidden);
        String advice = intents.size() > 15
                ? "职责过宽，建议按业务边界拆分为两个数字员工；当前结果未截断，请人工决定拆分位置"
                : null;
        return new DecomposeResult(List.copyOf(intents), List.copyOf(forbidden), advice);
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String sentence : SPLIT.split(text)) {
            String cleaned = sentence.trim().replaceFirst("^(你是|负责|职责是|需要|可以|并且|以及|同时)", "").trim();
            if (!cleaned.isBlank()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static List<String> clean(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String cleaned = value.trim().replaceFirst("^[\\d一二三四五六七八九十]+[.、)]\\s*", "")
                    .replaceAll("[。；;]+$", "");
            if (!cleaned.isBlank()) {
                unique.add(cleaned);
            }
        }
        return new ArrayList<>(unique);
    }

    private static void readStrings(JsonNode node, List<String> target) {
        if (node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual()) {
                    target.add(value.asText());
                }
            });
        }
    }

    /** 供接口层展示“非动词开头”提示，不篡改模型原文。 */
    public boolean startsWithActionVerb(String intent) {
        return intent != null && ACTION_PREFIXES.stream().anyMatch(intent::startsWith);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
