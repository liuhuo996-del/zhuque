package com.zhuque.m7_gate;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

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

    private final List<GateRule> rules; // Spring 自动注入所有 @Component 规则

    public GateEngine(List<GateRule> rules) {
        this.rules = rules;
    }

    /**
     * 功能：对 Release 跑全部启用的规则，逐条落 gate_decision，
     * 返回汇总（含规则集版本号）。已有豁免记录的规则保持 waived 不重判。
     */
    public GateSummary judge(UUID releaseId) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：人工豁免一条 BLOCK。waivedBy / reason 必填（空理由直接拒绝），
     * 更新对应 gate_decision 的 verdict=waived。
     */
    public void waive(UUID releaseId, String ruleId, String waivedBy, String reason) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：能否进入 approved：所有 BLOCK 规则 pass 或 waived。M5.approve 前置调用。 */
    public boolean canApprove(UUID releaseId) {
        throw new UnsupportedOperationException("TODO");
    }

    public record GateSummary(String ruleSetVersion, List<Decision> decisions) {}
    public record Decision(String ruleId, String severity, String verdict, String detail,
                           String waivedBy, String waiverReason) {}
}
