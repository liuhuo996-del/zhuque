package com.zhuque.m6_testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.ai.AiModelClient;
import com.zhuque.common.ApiException;
import com.zhuque.common.JobRegistry;
import com.zhuque.persistence.ControlPlaneRepository;

class L2AgentEvaluatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sendsOnlySafeCatalogAndRedactsUnknownModelSelection() {
        RecordingRepository repository = new RecordingRepository();
        RecordingModel model = new RecordingModel("untrusted output containing token=super-secret");
        JobRegistry jobs = new JobRegistry();
        L2AgentEvaluator evaluator = evaluator(repository, model, jobs, Runnable::run);

        evaluator.run(repository.release.id(), legacyClientMetadata());

        assertEquals(3, repository.reports.size());
        assertTrue(repository.reports.stream().allMatch(report -> "<invalid>".equals(report.detail().get("selected"))));
        assertTrue(repository.reports.stream().allMatch(report -> "server-evaluation-model".equals(report.modelMeta().get("model"))));
        assertTrue(repository.reports.stream().allMatch(report -> "provider-unreported".equals(report.modelMeta().get("modelVersion"))));
        assertTrue(repository.reports.stream().allMatch(report -> Double.valueOf(0.1).equals(report.modelMeta().get("temperature"))));
        assertTrue(repository.reports.stream().allMatch(report -> "l2-tool-selection-v1"
                .equals(report.modelMeta().get("promptTemplateVersion"))));

        assertEquals(3, model.systemPrompts.size());
        String prompt = model.systemPrompts.get(0);
        assertTrue(prompt.contains("orders_get"));
        assertTrue(prompt.contains("查询订单"));
        assertTrue(prompt.contains("orderId"));
        assertTrue(prompt.contains("string"));
        assertFalse(prompt.contains("https://private.example.test"));
        assertFalse(prompt.contains("Authorization"));
        assertFalse(prompt.contains("super-secret"));
        assertFalse(prompt.contains("requestTemplate"));
        assertTrue(repository.testableChecks >= 8, "模型调用前后和每个 case 都应重新校验 Release 生命周期");
    }

    @Test
    void marksRunFailedWhenExecutorRejectsWork() {
        RecordingRepository repository = new RecordingRepository();
        JobRegistry jobs = new JobRegistry();
        Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };
        L2AgentEvaluator evaluator = evaluator(repository, new RecordingModel("orders_get"), jobs, rejecting);

        assertThrows(ApiException.class, () -> evaluator.run(repository.release.id(), legacyClientMetadata()));

        assertEquals("failed", repository.run.state());
        assertEquals("failed", jobs.get(repository.run.jobId()).state());
    }

    private static L2AgentEvaluator evaluator(RecordingRepository repository, AiModelClient model,
                                                JobRegistry jobs, Executor executor) {
        return new L2AgentEvaluator(repository, new TestCaseService(repository), model, jobs, executor);
    }

    private static L2AgentEvaluator.L2Config legacyClientMetadata() {
        return new L2AgentEvaluator.L2Config("client-supplied-model", "client-supplied-version", 9.9,
                "client-supplied-prompt");
    }

    private static final class RecordingModel implements AiModelClient {
        private final String selected;
        private final List<String> systemPrompts = new ArrayList<>();

        RecordingModel(String selected) {
            this.selected = selected;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String modelName() {
            return "server-evaluation-model";
        }

        @Override
        public Optional<JsonNode> completeJson(String systemPrompt, String userPrompt) {
            systemPrompts.add(systemPrompt);
            try {
                return Optional.of(JSON.readTree("{\"tool\":\"" + selected + "\"}"));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }
    }

    private static final class RecordingRepository extends ControlPlaneRepository {
        private final ReleaseRow release;
        private final List<TestReportRow> reports = new ArrayList<>();
        private TestRunRow run;
        private int testableChecks;

        RecordingRepository() {
            super(null, JSON);
            release = new ReleaseRow(UUID.randomUUID(), UUID.randomUUID(), "v1", "candidate", manifest(),
                    "sha256:manifest", Map.of(), Map.of(), Map.of(), Map.of(), Instant.now());
        }

        @Override
        public ReleaseRow requireReleaseTestable(UUID id) {
            assertEquals(release.id(), id);
            testableChecks++;
            return release;
        }

        @Override
        public ReleaseRow requireRelease(UUID id) {
            assertEquals(release.id(), id);
            return release;
        }

        @Override
        public Optional<TestRunRow> testRun(UUID releaseId, String layer) {
            return run == null ? Optional.empty() : Optional.of(run);
        }

        @Override
        public void beginTestRun(UUID releaseId, String layer, String jobId, int expectedCases) {
            run = new TestRunRow(releaseId, layer, jobId, expectedCases, "running", null, Instant.now(), null);
        }

        @Override
        public void completeTestRun(UUID releaseId, String layer, String jobId) {
            assertEquals(3, reports.size());
            run = new TestRunRow(releaseId, layer, jobId, run.expectedCases(), "completed", null,
                    run.startedAt(), Instant.now());
        }

        @Override
        public void failTestRun(UUID releaseId, String layer, String jobId, String failure) {
            run = new TestRunRow(releaseId, layer, jobId, run.expectedCases(), "failed", failure,
                    run.startedAt(), Instant.now());
        }

        @Override
        public void insertTestReport(UUID releaseId, String layer, String jobId, String caseId, String result,
                                     Map<String, Object> detail, Map<String, Object> modelMeta) {
            assertEquals(run.jobId(), jobId);
            reports.add(new TestReportRow(UUID.randomUUID(), releaseId, layer, caseId, result, detail, modelMeta));
        }
    }

    private static Map<String, Object> manifest() {
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000222");
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "orderId", Map.of("type", "string", "description", "订单编号")));
        Map<String, Object> requestTemplate = Map.of(
                "method", "GET",
                "url", "https://private.example.test/orders?token=super-secret",
                "headers", List.of(Map.of("key", "Authorization", "value", "Bearer super-secret")),
                "body", "{\"token\":\"super-secret\"}");
        Map<String, Object> tool = Map.of(
                "id", toolId.toString(),
                "name", "orders_get",
                "description", "查询订单",
                "inputSchema", schema,
                "requestTemplate", requestTemplate);
        return Map.of("tools", List.of(tool));
    }
}
