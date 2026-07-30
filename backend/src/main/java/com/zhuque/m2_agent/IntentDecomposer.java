package com.zhuque.m2_agent;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * M2 · 职责描述 → 意图列表的 AI 拆解。
 *
 * v1 就是一个表单加一次 AI 拆解，人可以直接改结果；不做多轮对话式 onboarding。
 */
@Component
public class IntentDecomposer {

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
        throw new UnsupportedOperationException("TODO");
    }
}
