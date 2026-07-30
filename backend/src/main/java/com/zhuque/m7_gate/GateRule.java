package com.zhuque.m7_gate;

import java.util.Map;
import java.util.UUID;

/**
 * M7 · 单条门禁规则。规则是硬判定，不是评分——
 * 分数给人看，规则给机器执行，不许输出"危险指数 72 分"这种无法驱动动作的东西。
 *
 * 每条规则独立可开关、阈值可配（config 从设置页来）。
 */
public interface GateRule {

    /** 规则 id，稳定不变（gate_decision.rule_id / 前端"被 xx 规则阻断"都引用它） */
    String id();

    /** BLOCK（可豁免的硬阻断）还是 WARN（不阻断但审批界面必须显示） */
    String severity();

    /**
     * 功能：对一个 Release 判定。
     * 返回的 detail 必须能直接渲染成「被哪条规则挡住、怎么修」，
     * 例如 "trigger_payout 未声明幂等键：重试会重复打款。补 idempotencyKey 或移除该工具"。
     */
    Verdict evaluate(UUID releaseId, Map<String, Object> config);

    record Verdict(boolean pass, String detail) {}
}
