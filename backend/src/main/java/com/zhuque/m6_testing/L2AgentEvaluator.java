package com.zhuque.m6_testing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.zhuque.ai.AiModelClient;
import com.zhuque.common.ApiException;
import com.zhuque.common.JobRegistry;
import com.zhuque.persistence.ControlPlaneRepository;

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

    private final ControlPlaneRepository repository;
    private final TestCaseService cases;
    private final AiModelClient model;
    private final JobRegistry jobs;
    private final Executor executor;

    public L2AgentEvaluator(ControlPlaneRepository repository, TestCaseService cases, AiModelClient model,
                            JobRegistry jobs, @Qualifier("testingExecutor") Executor executor) {
        this.repository = repository;
        this.cases = cases;
        this.model = model;
        this.jobs = jobs;
        this.executor = executor;
    }

    public record L2Config(String model, String modelVersion, double temperature, String promptTemplateVersion) {}

    /**
     * 功能：跑选工具准确率评测。
     * 前置：评测环境可用（模型可达）。不可用时抛出的异常要引导
     * 「测试环境缺失 → 可先用内置 mock 跑 L0+L1，L2 走门禁豁免」。
     * 结果按 case 落 test_report（layer=L2，model_meta 全量记录），
     * 汇总准确率供 M7 的 WARN 规则（< 0.9 告警）。
     */
    public String run(UUID releaseId, L2Config config) {
        repository.requireRelease(releaseId);
        if (config == null || blank(config.model()) || blank(config.modelVersion())
                || blank(config.promptTemplateVersion())) {
            throw ApiException.badRequest("L2 model_meta 不完整", "填写模型名、版本、温度和 prompt 模板版本");
        }
        if (!model.available()) {
            throw ApiException.unavailable("L2 评测模型不可达", "可先用内置 mock 跑 L0+L1，L2 走门禁豁免");
        }
        List<TestCaseService.CaseDef> definitions = cases.generateDrafts(releaseId).stream()
                .filter(item -> "L2".equals(item.layer())).limit(20).toList();
        String jobId = jobs.start(Math.max(1, definitions.size() * 3), "准备 L2 选工具评测");
        executor.execute(() -> {
            try {
                repository.deleteTestReports(releaseId, "L2");
                int done = 0;
                Map<String, Object> meta = Map.of("model", config.model(), "modelVersion", config.modelVersion(),
                        "temperature", config.temperature(), "promptTemplateVersion", config.promptTemplateVersion());
                String catalog = repository.requireRelease(releaseId).manifest().get("tools").toString();
                for (var definition : definitions) {
                    for (int run = 1; run <= 3; run++) {
                        var response = model.completeJson("只返回 {\"tool\":\"工具名\"}。从目录选择最适合的工具：" + catalog,
                                definition.input());
                        String selected = response.map(node -> node.path("tool").asText()).orElse("");
                        boolean pass = definition.golden().equals(selected);
                        repository.insertTestReport(releaseId, "L2", definition.caseId() + "-run" + run,
                                pass ? "pass" : "fail", Map.of("golden", definition.golden(), "selected", selected), meta);
                        jobs.update(jobId, ++done, "评测 " + definition.caseId() + " 第 " + run + " 次");
                    }
                }
                jobs.done(jobId, "L2 评测完成");
            } catch (Throwable error) {
                jobs.fail(jobId, "L2 评测失败", error);
            }
        });
        return jobId;
    }

    /** 功能：汇总某 Release 的 L2 通过率（每条 case 3 次运行的通过率再平均）。 */
    public Map<String, Double> summarize(UUID releaseId) {
        var reports = repository.testReports(releaseId, "L2");
        Map<String, long[]> grouped = new LinkedHashMap<>();
        for (var report : reports) {
            String caseId = report.caseId().replaceFirst("-run\\d+$", "");
            long[] counts = grouped.computeIfAbsent(caseId, ignored -> new long[2]);
            counts[1]++;
            if ("pass".equals(report.result())) counts[0]++;
        }
        Map<String, Double> result = new LinkedHashMap<>();
        grouped.forEach((id, counts) -> result.put(id, counts[1] == 0 ? 0 : (double) counts[0] / counts[1]));
        double overall = result.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        result.put("overall", overall);
        return Map.copyOf(result);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
