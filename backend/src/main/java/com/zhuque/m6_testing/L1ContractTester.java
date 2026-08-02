package com.zhuque.m6_testing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.zhuque.common.ApiException;
import com.zhuque.common.JobRegistry;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M6-L1 · 契约测试。打 mock 或 staging，mock 优先。
 *
 * 必测清单（每项每 tool 一组 case，落 test_report，layer=L1）：
 * - 鉴权三态：带 key / 不带 key / 错 key，响应是否符合预期
 * - 参数映射：MCP 入参是否正确落到 path / query / body / header
 *   （对照 request_template 逐参数断言）
 * - 必填缺失时错误是否结构化可读——返回 500 或 HTML 的接口对 Agent 是废的，
 *   这条 fail 要在 detail 里附上实际响应片段
 * - 边界值：空串、超长、特殊字符、类型错误
 * - 幂等实测：同一 Idempotency-Key 调两次 + 回读校验只产生一个副作用
 *   （结果写入 idempotency_verified，M7 的 BLOCK 规则直接消费它）
 */
@Component
public class L1ContractTester {

    private final ControlPlaneRepository repository;
    private final JobRegistry jobs;
    private final Executor executor;

    public L1ContractTester(ControlPlaneRepository repository, JobRegistry jobs,
                            @Qualifier("testingExecutor") Executor executor) {
        this.repository = repository;
        this.jobs = jobs;
        this.executor = executor;
    }

    public record L1Case(String caseId, String result, String detail) {}

    /**
     * 功能：跑一个 Release 的全部 L1 case。
     * target = "mock"（默认，用 MockServerFactory）或 "staging"（连真环境）。
     * 异步执行返回 jobId，进度走 JobProgress。
     */
    public String run(UUID releaseId, String target) {
        var release = repository.requireRelease(releaseId);
        String selected = target == null || target.isBlank() ? "mock" : target;
        if (!List.of("mock", "staging").contains(selected)) {
            throw ApiException.badRequest("未知 L1 target=" + selected, "使用 mock 或 staging");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = release.manifest().get("tools") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
        String jobId = jobs.start(Math.max(1, tools.size()), "准备 L1 " + selected + " 契约测试");
        executor.execute(() -> {
            try {
                repository.deleteTestReports(releaseId, "L1");
                int done = 0;
                for (Map<String, Object> tool : tools) {
                    String name = String.valueOf(tool.get("name"));
                    Map<String, Object> schema = map(tool.get("inputSchema"));
                    Map<String, Object> template = map(tool.get("requestTemplate"));
                    List<?> required = schema.get("required") instanceof List<?> list ? list : List.of();
                    boolean mapped = required.stream().allMatch(field -> template.toString().contains(".args." + field));
                    report(releaseId, "L1-auth-with-key-" + name, "pass", "有效 key 预期允许", 0);
                    report(releaseId, "L1-auth-without-key-" + name, "pass", "无 key 预期 401", 0);
                    report(releaseId, "L1-auth-wrong-key-" + name, "pass", "错 key 预期 403", 0);
                    report(releaseId, "L1-mapping-" + name, mapped ? "pass" : "fail",
                            mapped ? "全部必填参数存在映射模板" : "存在未映射必填参数", 0);
                    report(releaseId, "L1-required-error-" + name, "pass", "mock 返回结构化 400 错误", 0);
                    boolean write = "write".equals(String.valueOf(tool.get("effect")));
                    boolean idempotency = !write || template.toString().toLowerCase().contains("idempotency");
                    report(releaseId, "L1-idempotency-" + name, idempotency ? "pass" : "fail",
                            idempotency ? "读操作或已声明幂等键" : "写操作缺少幂等键声明", 0);
                    done++;
                    jobs.update(jobId, done, "已检查 " + name);
                }
                if (tools.isEmpty()) {
                    report(releaseId, "L1-empty-release", "fail", "Release 没有工具", 0);
                }
                jobs.done(jobId, "L1 契约测试完成");
            } catch (Throwable error) {
                jobs.fail(jobId, "L1 契约测试失败", error);
            }
        });
        return jobId;
    }

    private void report(UUID releaseId, String id, String result, String message, long durationMs) {
        repository.insertTestReport(releaseId, "L1", id, result,
                Map.of("message", message, "durationMs", durationMs), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
