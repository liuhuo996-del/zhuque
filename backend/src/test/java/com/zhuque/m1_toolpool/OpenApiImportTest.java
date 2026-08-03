package com.zhuque.m1_toolpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;
import com.zhuque.m1_toolpool.ToolDraftGenerator.ToolDraft;
import com.zhuque.persistence.ControlPlaneRepository;

class OpenApiImportTest {

    private static final String UPLOADED_YAML = """
            openapi: 3.0.3
            info:
              title: Orders
              version: 1.0.0
            paths:
              /orders/{orderId}:
                get:
                  operationId: getOrder
                  summary: Query one order
                  x-zhuque-l1:
                    testSafe: true
                    fixture: ORD-1
                    ignored: must-not-be-persisted
                  parameters:
                    - name: orderId
                      in: path
                      required: true
                      schema: { type: string, example: ORD-1 }
                    - name: include
                      in: query
                      schema: { type: string, default: items }
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id: { type: string }
                              status: { type: string }
            """;

    @Test
    void uploadedYamlWithBaseUrlProducesAnAbsoluteFormalRequestTemplate() {
        OpenApiParser parser = new OpenApiParser();
        var parsed = parser.parse(UPLOADED_YAML, "https://orders.test.example.com/v1");

        assertTrue(parsed.errors().isEmpty());
        assertEquals(1, parsed.endpoints().size());
        var endpoint = parsed.endpoints().get(0);
        assertEquals("https://orders.test.example.com/v1", endpoint.serverUrl());

        ToolDraft draft = new ToolDraftGenerator(new OutputFieldExtractor()).generate("orders", endpoint);
        assertEquals("https://orders.test.example.com/v1/orders/{{.args.orderId}}?include={{.args.include}}",
                draft.requestTemplate().get("url"));
        assertEquals(List.of("orderId"), draft.inputSchema().get("required"));
        assertTrue(draft.outputFields().contains("id"));
        assertTrue(draft.outputFields().contains("status"));
        assertEquals(Map.of("testSafe", true, "fixture", "ORD-1"),
                draft.requestTemplate().get("x-zhuque-l1"));
    }

    @Test
    void controllerImportsUploadedYamlAsRawToolsAndRecordsAuditEvidence() {
        RecordingRepository repository = new RecordingRepository();
        ToolPoolController controller = controller(repository);

        ToolPoolController.ImportResult result = controller.importSource(
                new ToolPoolController.ImportSourceRequest("订单测试", "orders", null, UPLOADED_YAML,
                        "https://orders.test.example.com/v1", "test", "auditor@example.com"));

        assertEquals(repository.sourceId, result.sourceId());
        assertEquals(1, result.importedTools());
        assertTrue(result.parseErrors().isEmpty());
        assertEquals("orders_get_order", repository.toolName);
        assertEquals("GET", repository.method);
        assertEquals("/orders/{orderId}", repository.path);
        assertEquals("raw", repository.enrichmentStatus);
        assertEquals("https://orders.test.example.com/v1/orders/{{.args.orderId}}?include={{.args.include}}",
                repository.requestTemplate.get("url"));
        assertEquals(Map.of("testSafe", true, "fixture", "ORD-1"),
                repository.requestTemplate.get("x-zhuque-l1"));
        assertEquals("auditor@example.com", repository.auditActor);
        assertEquals("import", repository.auditAction);
    }

    @Test
    void invalidL1AnnotationIsNotCopiedIntoImportedRequestTemplate() {
        for (String invalid : List.of(
                UPLOADED_YAML.replace("testSafe: true", "testSafe: false"),
                UPLOADED_YAML.replace("fixture: ORD-1", "fixture: '   '"))) {
            var parsed = new OpenApiParser().parse(invalid, "https://orders.test.example.com/v1");
            ToolDraft draft = new ToolDraftGenerator(new OutputFieldExtractor()).generate("orders",
                    parsed.endpoints().get(0));

            assertFalse(draft.requestTemplate().containsKey("x-zhuque-l1"));
        }
    }

    @Test
    void importRejectsAmbiguousOrUntestableInputsBeforePersistingAnything() {
        RecordingRepository repository = new RecordingRepository();
        ToolPoolController controller = controller(repository);

        ApiException ambiguous = assertThrows(ApiException.class, () -> controller.importSource(
                new ToolPoolController.ImportSourceRequest("Orders", null, "https://example.com/openapi.yaml",
                        UPLOADED_YAML, null, "test", "auditor")));
        assertTrue(ambiguous.what().contains("只能使用一个"));

        ApiException noBaseUrl = assertThrows(ApiException.class, () -> controller.importSource(
                new ToolPoolController.ImportSourceRequest("Orders", null, null, UPLOADED_YAML,
                        null, "test", "auditor")));
        assertTrue(noBaseUrl.what().contains("REST baseUrl"));
        assertFalse(noBaseUrl.fix().isBlank());
        assertEquals(0, repository.apiSourceInsertions);
    }

    @Test
    void refetchRefusesSpecsThatWouldNeedTheInitialImportBaseUrl() {
        OpenApiParser parser = new OpenApiParser();
        ApiException rejected = assertThrows(ApiException.class,
                () -> SpecSyncService.requireSelfContainedServers(parser.parse(UPLOADED_YAML)));
        assertTrue(rejected.what().contains("无法安全重新拉取"));
        assertTrue(rejected.fix().contains("baseUrl"));

        String selfContained = UPLOADED_YAML.replace("paths:", """
                servers:
                  - url: https://orders.test.example.com/v1
                paths:""");
        assertDoesNotThrow(() -> SpecSyncService.requireSelfContainedServers(parser.parse(selfContained)));
    }

    private static ToolPoolController controller(RecordingRepository repository) {
        return new ToolPoolController(new OpenApiParser(), new ToolDraftGenerator(new OutputFieldExtractor()),
                null, null, null, new StaticAnnotator(), repository);
    }

    /** 仅覆盖 importSource 用到的持久化边界，避免把单元测试变成数据库测试。 */
    private static final class RecordingRepository extends ControlPlaneRepository {
        private final UUID sourceId = UUID.randomUUID();
        private int apiSourceInsertions;
        private String toolName;
        private String method;
        private String path;
        private String enrichmentStatus;
        private Map<String, Object> requestTemplate = Map.of();
        private String auditActor;
        private String auditAction;

        RecordingRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public UUID insertApiSource(String name, String specUrl, String specHash, String envProfile) {
            apiSourceInsertions++;
            assertEquals("订单测试", name);
            assertEquals(null, specUrl);
            assertEquals("test", envProfile);
            return sourceId;
        }

        @Override
        public boolean toolNameExists(String name) {
            return false;
        }

        @Override
        public UUID insertTool(UUID apiSourceId, String name, String description,
                               Map<String, Object> inputSchema, Map<String, Object> template,
                               String requestMethod, String requestPath, String effect, String status,
                               List<String> outputFields, List<String> sensitivityFlags, int tokenCost) {
            assertEquals(sourceId, apiSourceId);
            toolName = name;
            method = requestMethod;
            path = requestPath;
            enrichmentStatus = status;
            requestTemplate = template;
            return UUID.randomUUID();
        }

        @Override
        public void insertAuditEvent(String actor, String action, String resourceType,
                                     UUID resourceId, Map<String, Object> detail) {
            assertEquals(sourceId, resourceId);
            assertEquals("api_source", resourceType);
            auditActor = actor;
            auditAction = action;
        }
    }
}
