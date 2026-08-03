package com.zhuque.m6_testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;
import com.zhuque.common.JobRegistry;
import com.zhuque.persistence.ControlPlaneRepository;

/** 正式 L1 不应把调用参数或真实响应内容变成长期审计数据，也不应自动写业务数据。 */
class L1ContractTesterTest {

    @Test
    void readContractEvidenceStoresOnlyDigestAndOrigin() {
        Map<String, Object> stored = L1ContractTester.responseEvidence("GET",
                "http://127.0.0.1:19080/orders/zhuque-test?probe=private-query", 200,
                "{\"customer\":\"Alice\",\"token\":\"super-secret\"}".getBytes());

        assertEquals("http://127.0.0.1:19080", stored.get("endpointOrigin"));
        assertNotNull(stored.get("responseSha256"));
        assertFalse(stored.toString().contains("zhuque-test"), "渲染后的路径参数不能进入 L1 证据");
        assertFalse(stored.toString().contains("private-query"), "查询参数不能进入 L1 证据");
        assertFalse(stored.toString().contains("Alice"), "真实响应体不能进入 L1 证据");
        assertFalse(stored.toString().contains("super-secret"), "真实响应体中的敏感值不能进入 L1 证据");
    }

    @Test
    void responseBodyHasHardOneMiBBound() throws Exception {
        assertEquals(4, L1ContractTester.readBoundedBody(new ByteArrayInputStream(new byte[4])).length);
        assertEquals(1024 * 1024, L1ContractTester.readBoundedBody(
                new ByteArrayInputStream(new byte[1024 * 1024])).length);

        ApiException error = assertThrows(ApiException.class, () -> L1ContractTester.readBoundedBody(
                new ByteArrayInputStream(new byte[1024 * 1024 + 1])));
        assertEquals(503, error.status().value());
    }

    @Test
    void allowedOriginsAreExplicitNormalizedAndDenyByDefault() {
        Set<String> allowed = L1ContractTester.parseAllowedOrigins(
                " https://Staging.Example.com , http://localhost:19080/ ");
        assertEquals(Set.of("https://staging.example.com:443", "http://localhost:19080"), allowed);

        L1ContractTester.requireAllowedOrigin(URI.create("https://staging.example.com/orders?fixture=1"), allowed);

        ApiException empty = assertThrows(ApiException.class, () -> L1ContractTester.requireAllowedOrigin(
                URI.create("https://staging.example.com/orders"), Set.of()));
        assertEquals(503, empty.status().value());
        ApiException unlisted = assertThrows(ApiException.class, () -> L1ContractTester.requireAllowedOrigin(
                URI.create("https://prod.example.com/orders"), allowed));
        assertEquals(503, unlisted.status().value());
        assertThrows(IllegalArgumentException.class,
                () -> L1ContractTester.parseAllowedOrigins("https://staging.example.com/v1"));
    }

    @Test
    void writeContractNeverAutoCallsUpstreamWithoutControlledFixture() {
        RecordingRepository repository = new RecordingRepository(writeManifest());

        tester(repository).run(repository.release.id(), "live");

        assertEquals("fail", repository.report("L1-live-orders_create").result());
        assertEquals("skip", repository.report("L1-required-error-orders_create").result());
        assertEquals("fail", repository.report("L1-idempotency-orders_create").result());
        assertEquals("completed", repository.run.state(), "失败 case 也是完整的正式测试证据，不是半途任务");
    }

    @Test
    void unknownOrUnreviewedReadNeverLooksUpOrCallsSource() {
        RecordingRepository unknown = new RecordingRepository(readManifest("unknown", "reviewed", true));
        tester(unknown).run(unknown.release.id(), "live");

        assertEquals("fail", unknown.report("L1-live-orders_read").result());
        assertTrue(String.valueOf(unknown.report("L1-live-orders_read").detail().get("message"))
                .contains("effect 不是 read"));
        assertEquals(0, unknown.sourceLookups, "unknown 工具必须在获取来源/出网前被拒绝");

        RecordingRepository unreviewed = new RecordingRepository(readManifest("read", "enriched", true));
        tester(unreviewed).run(unreviewed.release.id(), "live");

        assertEquals("fail", unreviewed.report("L1-live-orders_read").result());
        assertTrue(String.valueOf(unreviewed.report("L1-live-orders_read").detail().get("message"))
                .contains("工具未人工复核"));
        assertEquals(0, unreviewed.sourceLookups, "未人工复核的读工具不得自动调用");
    }

    @Test
    void reviewedReadStillRequiresExplicitControlledFixtureMarker() {
        RecordingRepository repository = new RecordingRepository(readManifest("read", "reviewed", false));

        tester(repository).run(repository.release.id(), "live");

        assertEquals("fail", repository.report("L1-live-orders_read").result());
        assertTrue(String.valueOf(repository.report("L1-live-orders_read").detail().get("message"))
                .contains("x-zhuque-l1.testSafe:true"));
        assertEquals(0, repository.sourceLookups, "没有 fixture 标记时不得触达上游来源");
    }

    @Test
    void reviewedReadWithFixturePassesPolicyBeforeEnvironmentGuard() {
        // 让来源保持 prod，便可在不建立 HTTP server 的情况下证明这三个显式条件已通过；
        // 随后的环境门禁会拒绝出网。
        RecordingRepository repository = new RecordingRepository(readManifest("read", "reviewed", true), "prod");

        tester(repository).run(repository.release.id(), "live");

        assertEquals(1, repository.sourceLookups);
        assertTrue(String.valueOf(repository.report("L1-live-orders_read").detail().get("message"))
                .contains("env_profile=prod"));
    }

    @Test
    void executorRejectionMarksPersistentRunAndJobFailed() {
        RecordingRepository repository = new RecordingRepository(writeManifest());
        JobRegistry jobs = new JobRegistry();
        L1ContractTester tester = new L1ContractTester(repository, jobs,
                task -> { throw new RejectedExecutionException("capacity exhausted"); });

        ApiException error = assertThrows(ApiException.class, () -> tester.run(repository.release.id(), "live"));

        assertEquals(503, error.status().value());
        assertEquals("failed", repository.run.state());
        assertEquals("failed", jobs.get(repository.run.jobId()).state());
    }

    private L1ContractTester tester(RecordingRepository repository) {
        Executor direct = Runnable::run;
        return new L1ContractTester(repository, new JobRegistry(), direct);
    }

    private static Map<String, Object> writeManifest() {
        return manifest("orders_create", "write", "POST", "http://127.0.0.1:1/orders",
                List.of(Map.of("key", "Idempotency-Key", "value", "fixture-key")), "reviewed", true);
    }

    private static Map<String, Object> readManifest(String effect, String review, boolean controlledFixture) {
        return manifest("orders_read", effect, "GET", "http://127.0.0.1:1/orders",
                List.of(), review, controlledFixture);
    }

    private static Map<String, Object> manifest(String name, String effect, String method, String url,
                                                 List<Map<String, Object>> headers, String enrichmentStatus,
                                                 boolean controlledFixture) {
        UUID sourceId = SOURCE_ID;
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "orderId", Map.of("type", "string", "description", "order", "example", "ORD-1")),
                "required", List.of("orderId"));
        Map<String, Object> template = controlledFixture
                ? Map.of("method", method, "url", url, "headers", headers,
                        "x-zhuque-l1", Map.of("testSafe", true, "fixture", "orders-read-fixture"))
                : Map.of("method", method, "url", url, "headers", headers);
        Map<String, Object> tool = Map.of("name", name, "effect", effect, "method", method,
                "enrichmentStatus", enrichmentStatus, "apiSourceId", sourceId.toString(),
                "inputSchema", schema, "requestTemplate", template);
        return Map.of("tools", List.of(tool));
    }

    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");

    private static final class RecordingRepository extends ControlPlaneRepository {
        private final ReleaseRow release;
        private final ApiSourceRow source;
        private final List<TestReportRow> reports = new ArrayList<>();
        private TestRunRow run;
        private int sourceLookups;

        RecordingRepository(Map<String, Object> manifest) {
            this(manifest, "test");
        }

        RecordingRepository(Map<String, Object> manifest, String envProfile) {
            super(null, new ObjectMapper());
            source = new ApiSourceRow(SOURCE_ID, "订单测试 API", null,
                    "sha256:spec", Instant.now(), envProfile);
            release = new ReleaseRow(UUID.randomUUID(), UUID.randomUUID(), "v1", "candidate", manifest,
                    "sha256:manifest", Map.of(), Map.of(), Map.of(), Map.of(), Instant.now());
        }

        @Override
        public ReleaseRow requireReleaseTestable(UUID id) {
            assertEquals(release.id(), id);
            return release;
        }

        @Override
        public ApiSourceRow requireApiSource(UUID id) {
            assertEquals(SOURCE_ID, id);
            sourceLookups++;
            return source;
        }

        @Override
        public Optional<TestRunRow> testRun(UUID releaseId, String layer) {
            return run == null ? Optional.empty() : Optional.of(run);
        }

        @Override
        public void beginTestRun(UUID releaseId, String layer, String jobId, int expectedCases) {
            reports.clear();
            run = new TestRunRow(releaseId, layer, jobId, expectedCases, "running", null, Instant.now(), null);
        }

        @Override
        public void completeTestRun(UUID releaseId, String layer, String jobId) {
            assertEquals(run.expectedCases(), reports.size());
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

        TestReportRow report(String caseId) {
            return reports.stream().filter(report -> caseId.equals(report.caseId())).findFirst().orElseThrow();
        }
    }
}
