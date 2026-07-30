package com.zhuque.m6_testing;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M6-L2 · Agent 评测。v1 只做一个指标：选工具准确率。
 *
 * 方法：20 条自然语言任务，装载该 pack 的工具，用真实 MCP client
 * 调用被测模型，看它是否选中 golden tool。
 * 每条 case 跑 3 次取通过率——评测有随机性，单次结果不可信。
 *
 * ！！test_report.model_meta 必填：模型名/版本/温度/prompt 模板版本。
 * 否则三个月后无法解释分数变化，整套评测的公信力会崩。
 * model_meta 缺失时本层结果一律拒绝落库。
 *
 * v2 再加：参数填充准确率、越权诱导、多轮组合。v1 不做。
 */
@Component
public class L2AgentEvaluator {

    public record L2Config(String model, String modelVersion, double temperature, String promptTemplateVersion) {}

    /**
     * 功能：跑选工具准确率评测。
     * 前置：评测环境可用（模型可达）。不可用时抛出的异常要引导
     * 「测试环境缺失 → 可先用内置 mock 跑 L0+L1，L2 走门禁豁免」。
     * 结果按 case 落 test_report（layer=L2，model_meta 全量记录），
     * 汇总准确率供 M7 的 WARN 规则（< 0.9 告警）。
     */
    public String run(UUID releaseId, L2Config config) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：汇总某 Release 的 L2 通过率（每条 case 3 次运行的通过率再平均）。 */
    public Map<String, Double> summarize(UUID releaseId) {
        throw new UnsupportedOperationException("TODO");
    }
}
