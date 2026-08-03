package com.zhuque.m7_gate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zhuque.common.ApiException;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M7 · 门禁引擎。
 *
 * 要求：
 * - 规则集本身版本化：Release 里要记录用的是哪个版本的规则集
 *   （规则开关/阈值一改，版本号 +1；judge 时把版本号写进结果）
 * - 判定结果逐条落 gate_decision，不要只存总结论
 * - BLOCK 允许人工豁免：必须填豁免人和理由，Release 详情页显著标出
 * - 只有全部 BLOCK 项 pass 或被豁免，Release 才能进 approved；
 *   WARN 不阻断但必须在审批界面显示
 */
@Service
public class GateEngine {

    private static final String RULE_SET_VERSION = "v1";

    private final List<GateRule> rules; // Spring 自动注入所有 @Component 规则
    private final ControlPlaneRepository repository;

    public GateEngine(List<GateRule> rules, ControlPlaneRepository repository) {
        this.rules = rules;
        this.repository = repository;
    }

    /**
     * 功能：对 Release 跑全部启用的规则，逐条落 gate_decision，
     * 返回汇总（含规则集版本号）。已有豁免记录的规则保持 waived 不重判。
     */
    @Transactional
    public GateSummary judge(UUID releaseId) {
        repository.lockReleaseGateMutable(releaseId);
        return judgeLocked(releaseId);
    }

    /** 调用方已持有 Release 行锁；同一事务内的判定只能追加，不能覆写历史。 */
    private GateSummary judgeLocked(UUID releaseId) {
        List<Decision> decisions = rules.stream().map(rule -> {
            var existing = repository.gateDecision(releaseId, rule.id()).orElse(null);
            if (existing != null && "waived".equals(existing.verdict())) {
                String detail = String.valueOf(existing.detail().getOrDefault("message", "人工豁免"));
                return new Decision(rule.id(), rule.severity(), "waived", detail,
                        existing.waivedBy(), existing.waiverReason());
            }
            GateRule.Verdict verdict = rule.evaluate(releaseId, defaultConfig());
            String value = verdict.pass() ? "pass" : "fail";
            repository.appendGateDecision(releaseId, rule.id(), value,
                    Map.of("message", verdict.detail(), "severity", rule.severity()), RULE_SET_VERSION, "system");
            return new Decision(rule.id(), rule.severity(), value, verdict.detail(), null, null);
        }).toList();
        return new GateSummary(RULE_SET_VERSION, decisions);
    }

    /**
     * 功能：人工豁免一条 BLOCK。waivedBy / reason 必填（空理由直接拒绝），
     * 更新对应 gate_decision 的 verdict=waived。
     */
    @Transactional
    public void waive(UUID releaseId, String ruleId, String waivedBy, String reason) {
        repository.lockReleaseGateMutable(releaseId);
        GateRule rule = rules.stream().filter(candidate -> candidate.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("门禁规则 " + ruleId));
        if (!"BLOCK".equals(rule.severity())) {
            throw ApiException.badRequest("WARN 规则无需豁免", "直接在审批界面确认风险即可");
        }
        if (waivedBy == null || waivedBy.isBlank() || reason == null || reason.isBlank()) {
            throw ApiException.badRequest("豁免人和理由不能为空", "填写可审计的责任人和业务理由");
        }
        repository.waiveGate(releaseId, ruleId, waivedBy.trim(), reason.trim(),
                Map.of("message", "人工豁免", "reason", reason.trim(), "severity", rule.severity()), RULE_SET_VERSION);
    }

    /** 功能：能否进入 approved：所有 BLOCK 规则 pass 或 waived。M5.approve 前置调用。 */
    @Transactional
    public boolean canApprove(UUID releaseId) {
        // 这里必须再次锁定并核验完整 L0/L1 run：不能只信任此前已写入的 pass。
        // 这样审批与测试重跑、门禁重判互斥，半途证据无法进入 approved。
        repository.lockReleaseGateMutable(releaseId);
        repository.requireCoreTestsCompleted(releaseId);
        GateSummary summary = judgeLocked(releaseId);
        Map<String, String> severities = rules.stream().collect(java.util.stream.Collectors.toMap(
            GateRule::id, GateRule::severity));
        return summary.decisions().stream()
            .filter(decision -> "BLOCK".equals(severities.get(decision.ruleId())))
            .allMatch(decision -> "pass".equals(decision.verdict()) || "waived".equals(decision.verdict()));
    }

    public record GateSummary(String ruleSetVersion, List<Decision> decisions) {}
    public record Decision(String ruleId, String severity, String verdict, String detail,
                           String waivedBy, String waiverReason) {}

    private static Map<String, Object> defaultConfig() {
        return Map.of("l0Threshold", 0.9, "l2Threshold", 0.9, "maxTools", 100,
                "maxTokens", 20000, "latencyP95Ms", 10000);
    }
}
