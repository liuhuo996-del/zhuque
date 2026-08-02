package com.zhuque.m7_gate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.zhuque.persistence.ControlPlaneRepository;

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

    private abstract static class EvidenceRule implements GateRule {
        protected final ControlPlaneRepository repository;
        EvidenceRule(ControlPlaneRepository repository) { this.repository = repository; }
        protected List<Map<String, Object>> tools(UUID releaseId) {
            return maps(repository.requireRelease(releaseId).manifest().get("tools"));
        }
    }

    @Component
    public static final class IdempotencyRule extends EvidenceRule {
        public IdempotencyRule(ControlPlaneRepository repository) { super(repository); }
        public String id() { return "idempotency"; }
        public String severity() { return "BLOCK"; }
        public Verdict evaluate(UUID releaseId, Map<String, Object> config) {
            List<String> unsafe = repository.testReports(releaseId, "L1").stream()
                    .filter(report -> report.caseId().contains("idempotency") && !"pass".equals(report.result()))
                    .map(report -> report.caseId().replace("L1-idempotency-", "")).toList();
            return new Verdict(unsafe.isEmpty(), unsafe.isEmpty() ? "写操作幂等实测通过"
                    : "写工具未通过幂等实测：" + unsafe + "。补 Idempotency-Key 或移除工具");
        }
    }

    @Component
    public static final class L0CompletenessRule extends EvidenceRule {
        public L0CompletenessRule(ControlPlaneRepository repository) { super(repository); }
        public String id() { return "l0-completeness"; }
        public String severity() { return "BLOCK"; }
        public Verdict evaluate(UUID releaseId, Map<String, Object> config) {
            var reports = repository.testReports(releaseId, "L0");
            double threshold = number(config.get("l0Threshold"), 0.9);
            double ratio = reports.isEmpty() ? 0 : reports.stream().filter(r -> "pass".equals(r.result())).count()
                    / (double) reports.size();
            return new Verdict(ratio >= threshold, "L0 完备率=" + String.format("%.2f", ratio)
                    + "，要求 ≥ " + threshold + (ratio >= threshold ? "" : "。补齐 schema 后重跑 L0"));
        }
    }

    @Component
    public static final class ClosureRule extends EvidenceRule {
        public ClosureRule(ControlPlaneRepository repository) { super(repository); }
        public String id() { return "closure"; }
        public String severity() { return "BLOCK"; }
        public Verdict evaluate(UUID releaseId, Map<String, Object> config) {
            Map<String, Object> closure = map(repository.requireRelease(releaseId).manifest().get("closure"));
            String conclusion = String.valueOf(closure.getOrDefault("conclusion", "BLOCKED"));
            return new Verdict(!"BLOCKED".equals(conclusion), "闭包结论=" + conclusion
                    + ("BLOCKED".equals(conclusion) ? "。补充能产出缺失参数的工具或标注用户输入" : ""));
        }
    }

    @Component
    public static final class SensitiveMaskingRule extends EvidenceRule {
        public SensitiveMaskingRule(ControlPlaneRepository repository) { super(repository); }
        public String id() { return "sensitive-masking"; }
        public String severity() { return "BLOCK"; }
        public Verdict evaluate(UUID releaseId, Map<String, Object> config) {
            List<String> unmasked = tools(releaseId).stream().filter(tool -> !list(tool.get("sensitivityFlags")).isEmpty())
                    .filter(tool -> !map(tool.get("inputSchema")).containsKey("x-masking"))
                    .map(tool -> String.valueOf(tool.get("name"))).toList();
            return new Verdict(unmasked.isEmpty(), unmasked.isEmpty() ? "敏感字段均已标注脱敏"
                    : "敏感工具未标注 x-masking：" + unmasked + "。完成脱敏标注后重新冻结");
        }
    }

    @Component
    public static final class L2AccuracyRule extends EvidenceRule {
        public L2AccuracyRule(ControlPlaneRepository repository) { super(repository); }
        public String id() { return "l2-accuracy"; }
        public String severity() { return "WARN"; }
        public Verdict evaluate(UUID releaseId, Map<String, Object> config) {
            var reports = repository.testReports(releaseId, "L2");
            double accuracy = reports.isEmpty() ? 0 : reports.stream().filter(r -> "pass".equals(r.result())).count()
                    / (double) reports.size();
            double threshold = number(config.get("l2Threshold"), 0.9);
            return new Verdict(accuracy >= threshold, "L2 选工具准确率=" + String.format("%.2f", accuracy)
                    + "，目标 ≥ " + threshold);
        }
    }

    @Component
    public static final class BudgetRule extends EvidenceRule {
        public BudgetRule(ControlPlaneRepository repository) { super(repository); }
        public String id() { return "budget"; }
        public String severity() { return "WARN"; }
        public Verdict evaluate(UUID releaseId, Map<String, Object> config) {
            List<Map<String, Object>> tools = tools(releaseId);
            int token = tools.stream().mapToInt(tool -> (int) number(tool.get("tokenCost"), 0)).sum();
            int maxTools = (int) number(config.get("maxTools"), 100);
            int maxTokens = (int) number(config.get("maxTokens"), 20000);
            boolean pass = tools.size() <= maxTools && token <= maxTokens;
            return new Verdict(pass, "工具数=" + tools.size() + "/" + maxTools + "，token=" + token + "/" + maxTokens);
        }
    }

    @Component
    public static final class LatencyRule extends EvidenceRule {
        public LatencyRule(ControlPlaneRepository repository) { super(repository); }
        public String id() { return "latency"; }
        public String severity() { return "WARN"; }
        public Verdict evaluate(UUID releaseId, Map<String, Object> config) {
            double max = repository.testReports(releaseId, "L1").stream()
                    .mapToDouble(report -> number(report.detail().get("durationMs"), 0)).max().orElse(0);
            double limit = number(config.get("latencyP95Ms"), 10000);
            return new Verdict(max <= limit, "L1 最大观测延迟=" + max + "ms，告警线=" + limit + "ms");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList() : List.of();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
