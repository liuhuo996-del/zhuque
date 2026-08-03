package com.zhuque.m6_testing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zhuque.ai.AiModelClient;
import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;
import com.zhuque.common.JobRegistry;
import com.zhuque.common.JobProgress;
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

    private static final String INVALID_SELECTION = "<invalid>";

    private final ControlPlaneRepository repository;
    private final TestCaseService cases;
    private final AiModelClient model;
    private final JobRegistry jobs;
    private final Executor executor;

    /**
     * L2 的审计元数据必须来自控制面配置，而不是调用者提交的 JSON。默认值也写成字段
     * 初始值，方便脱离 Spring 容器的单元测试保持与生产默认配置一致。
     */
    @Value("${zhuque.ai.temperature:0.1}")
    private double configuredTemperature = 0.1;
    @Value("${zhuque.ai.model-version:provider-unreported}")
    private String configuredModelVersion = "provider-unreported";
    @Value("${zhuque.testing.l2.prompt-template-version:l2-tool-selection-v1}")
    private String configuredPromptTemplateVersion = "l2-tool-selection-v1";

    public L2AgentEvaluator(ControlPlaneRepository repository, TestCaseService cases, AiModelClient model,
                            JobRegistry jobs, @Qualifier("testingExecutor") Executor executor) {
        this.repository = repository;
        this.cases = cases;
        this.model = model;
        this.jobs = jobs;
        this.executor = executor;
    }

    /**
     * 兼容已有 API 请求体。model/modelVersion/temperature/promptTemplateVersion 均不再作为
     * 审计证据使用，真实值由服务端配置和 {@link AiModelClient#modelName()} 给出。
     */
    public record L2Config(String model, String modelVersion, double temperature, String promptTemplateVersion) {}

    /**
     * 功能：跑选工具准确率评测。
     * 前置：评测环境可用（模型可达）。不可用时必须明确失败，不能用模拟
     * 评测替代真实证据；是否豁免由责任人按门禁流程决定。
     * 结果按 case 落 test_report（layer=L2，model_meta 全量记录），
     * 汇总准确率供 M7 的 WARN 规则（< 0.9 告警）。
     */
    public String run(UUID releaseId, L2Config config) {
        repository.requireReleaseTestable(releaseId);
        if (config == null) {
            throw ApiException.badRequest("L2 评测配置缺失", "提交空 JSON 对象即可；模型与评测元数据统一由服务端配置");
        }
        if (!model.available()) {
            throw ApiException.unavailable("L2 评测模型不可达",
                    "检查评测模型连接；不得以模拟结果替代正式证据，必要时由责任人按门禁流程处理");
        }
        List<TestCaseService.CaseDef> definitions = cases.generateDrafts(releaseId).stream()
                .filter(item -> "L2".equals(item.layer())).limit(20).toList();
        // 冻结 manifest 中的 requestTemplate、headers、URL 和 body 都属于执行机密，绝不能
        // 进入模型上下文。L2 仅需要工具名称、描述和输入字段的名称/类型来评估选择准确率。
        List<Map<String, Object>> catalog = safeCatalog(repository.requireReleaseTestable(releaseId).manifest());
        Set<String> candidateNames = candidateNames(catalog);
        failStaleRunIfAny(releaseId);
        String jobId = jobs.start(Math.max(1, definitions.size() * 3), "准备 L2 选工具评测");
        try {
            repository.beginTestRun(releaseId, "L2", jobId, definitions.size() * 3);
        } catch (RuntimeException error) {
            jobs.fail(jobId, "无法启动 L2 评测", error);
            throw error;
        }
        try {
            executor.execute(() -> {
                try {
                    execute(releaseId, definitions, catalog, candidateNames, jobId);
                } catch (Throwable error) {
                    try {
                        repository.failTestRun(releaseId, "L2", jobId,
                                "L2 任务异常中止：" + error.getClass().getSimpleName());
                    } finally {
                        jobs.fail(jobId, "L2 评测失败", error);
                    }
                }
            });
        } catch (RuntimeException error) {
            // ThreadPoolExecutor 在队列满/已关闭时会同步抛 RejectedExecutionException。
            // beginTestRun 已落库，必须立刻终结该运行，不能留下永远 running 的假任务。
            try {
                repository.failTestRun(releaseId, "L2", jobId,
                        "L2 任务未能进入执行队列：" + error.getClass().getSimpleName());
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            jobs.fail(jobId, "L2 评测未能进入执行队列", error);
            throw ApiException.unavailable("L2 评测队列不可用", "稍后重试；该次运行已标记失败，不会阻塞 Release");
        }
        return jobId;
    }

    private void execute(UUID releaseId, List<TestCaseService.CaseDef> definitions,
                         List<Map<String, Object>> catalog, Set<String> candidateNames, String jobId) {
        int done = 0;
        Map<String, Object> meta = serverModelMeta();
        // 使用 JSON 编码而不是 Map#toString：描述中的引号/换行不会改变目录边界，也让
        // 模型看到的字段集合可审计、可复现。
        String safeCatalog = CanonicalJson.canonicalize(Map.of("tools", catalog));
        for (var definition : definitions) {
            // Release 在排队后可能被退役、恢复或完成测试；每个 case 都重新校验，避免
            // 在已经失效的冻结快照上继续向外部模型发送内容。
            repository.requireReleaseTestable(releaseId);
            for (int run = 1; run <= 3; run++) {
                // 紧贴模型调用再校验一次，缩小状态变更与外部调用之间的窗口。
                repository.requireReleaseTestable(releaseId);
                var response = model.completeJson(
                        "只返回 {\"tool\":\"工具名\"}。只能从以下安全工具目录选择，不要输出其他字段："
                                + safeCatalog,
                        definition.input());
                String selected = safeSelected(response.map(node -> node.path("tool").asText()).orElse(""),
                        candidateNames);
                boolean pass = definition.golden().equals(selected);
                // 如果模型刚返回时 Release 已失效，也不再写入新的测试证据。
                repository.requireReleaseTestable(releaseId);
                repository.insertTestReport(releaseId, "L2", jobId, definition.caseId() + "-run" + run,
                        pass ? "pass" : "fail", Map.of("golden", definition.golden(), "selected", selected), meta);
                jobs.update(jobId, ++done, "评测 " + definition.caseId() + " 第 " + run + " 次");
            }
        }
        repository.completeTestRun(releaseId, "L2", jobId);
        jobs.done(jobId, "L2 评测完成");
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

    /** 返回可发送给模型的最小目录，刻意不透传工具的 URL、headers、requestTemplate 或 body。 */
    private static List<Map<String, Object>> safeCatalog(Map<String, Object> manifest) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object rawTools = manifest == null ? null : manifest.get("tools");
        if (!(rawTools instanceof List<?> tools)) {
            return List.of();
        }
        for (Object rawTool : tools) {
            if (!(rawTool instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> tool = map(rawMap);
            String name = text(tool.get("name"));
            if (name.isBlank()) {
                continue;
            }
            Map<String, Object> schema = map(tool.get("inputSchema"));
            Map<String, Object> properties = map(schema.get("properties"));
            List<Map<String, String>> fields = new ArrayList<>();
            for (var entry : properties.entrySet()) {
                Map<String, Object> definition = map(entry.getValue());
                fields.add(Map.of("name", entry.getKey(),
                        "type", text(definition.getOrDefault("type", "string"))));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("description", text(tool.get("description")));
            item.put("inputFields", List.copyOf(fields));
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private static Set<String> candidateNames(List<Map<String, Object>> catalog) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> item : catalog) {
            String name = text(item.get("name"));
            if (!name.isBlank()) {
                result.add(name);
            }
        }
        return Set.copyOf(result);
    }

    /** 不保留模型任意输出，防止模型回显 prompt、注入内容或其他不受控文本进入审计库。 */
    private static String safeSelected(String selected, Set<String> candidateNames) {
        String value = selected == null ? "" : selected.trim();
        return candidateNames.contains(value) ? value : INVALID_SELECTION;
    }

    private Map<String, Object> serverModelMeta() {
        return Map.of(
                "model", nonBlank(model.modelName(), "provider-unreported"),
                "modelVersion", nonBlank(configuredModelVersion, "provider-unreported"),
                "temperature", configuredTemperature,
                "promptTemplateVersion", nonBlank(configuredPromptTemplateVersion, "l2-tool-selection-v1"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void failStaleRunIfAny(UUID releaseId) {
        var previous = repository.testRun(releaseId, "L2").orElse(null);
        if (previous == null || !"running".equals(previous.state())) {
            return;
        }
        JobProgress progress;
        try {
            progress = jobs.get(previous.jobId());
        } catch (ApiException missingJob) {
            repository.failTestRun(releaseId, "L2", previous.jobId(), "控制面重启或任务状态丢失，原 L2 运行未完成");
            return;
        }
        if ("running".equals(progress.state())) {
            throw ApiException.conflict("L2 评测仍在运行", "等待当前任务完成或失败后再重跑");
        }
        repository.failTestRun(releaseId, "L2", previous.jobId(), "L2 任务未正常完成，请重新运行");
    }
}
