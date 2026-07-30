package com.zhuque.m7_gate;

/**
 * M7 · v1 内置规则集（每条一个 GateRule 实现类，这里列清单和判定语义）：
 *
 * BLOCK 级：
 *   idempotency        effect=write 且 idempotency_verified=false（数据来自 L1 幂等实测）
 *   l0-completeness    L0 schema 完备性低于阈值（阈值 = 通过的 L0 case 占比，默认 0.9）
 *   closure            闭包检查结论 = BLOCKED
 *   sensitive-masking  命中敏感字段且未标注脱敏（sensitivity_flags 非空但无脱敏标注）
 *
 * WARN 级：
 *   l2-accuracy        L2 选工具准确率 < 0.9
 *   budget             工具数或 token 超预算
 *   latency            p95 延迟 > 10s（数据来自 L1 实测耗时）
 *
 * 实现纪律：
 * - 每个规则一个类，实现 GateRule，用 @Component 注册，GateEngine 自动收集
 * - 规则内部只读证据（test_report / closure 结论 / tool 标注），不做任何新计算
 * - detail 文案直接面向用户：「发生了什么 + 怎么修」
 */
public final class BuiltinRules {
    private BuiltinRules() {}
    // TODO：IdempotencyRule / L0CompletenessRule / ClosureRule / SensitiveMaskingRule
    //       L2AccuracyRule / BudgetRule / LatencyRule
}
