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
    @SuppressWarnings("unchecked")
    void preservesOneOfAndAnyOfAsStandardJsonSchemaBranches() {
        String spec = """
                openapi: 3.0.3
                info: { title: Composition, version: 1.0.0 }
                components:
                  schemas:
                    ById:
                      type: object
                      required: [id]
                      properties:
                        id: { type: string }
                    ByCode:
                      type: object
                      required: [code]
                      properties:
                        code: { type: integer }
                paths:
                  /composition:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  choice:
                                    oneOf:
                                      - $ref: '#/components/schemas/ById'
                                      - $ref: '#/components/schemas/ByCode'
                                  filter:
                                    anyOf:
                                      - { type: string, minLength: 2 }
                                      - { type: integer, minimum: 1 }
                """;

        Map<String, Object> response = new OpenApiParser().parse(spec).endpoints().get(0).responseSchema();
        Map<String, Object> properties = (Map<String, Object>) response.get("properties");
        Map<String, Object> choice = (Map<String, Object>) properties.get("choice");
        List<Map<String, Object>> oneOf = (List<Map<String, Object>>) choice.get("oneOf");
        Map<String, Object> filter = (Map<String, Object>) properties.get("filter");
        List<Map<String, Object>> anyOf = (List<Map<String, Object>>) filter.get("anyOf");

        assertFalse(choice.containsKey("properties"));
        assertFalse(choice.containsKey("required"));
        assertFalse(choice.containsKey("x-variant-of"));
        assertEquals(2, oneOf.size());
        assertEquals(List.of("id"), oneOf.get(0).get("required"));
        assertEquals(List.of("code"), oneOf.get(1).get("required"));
        assertEquals(2, anyOf.size());
        assertEquals("string", anyOf.get(0).get("type"));
        assertEquals(2, anyOf.get(0).get("minLength"));
        assertEquals("integer", anyOf.get(1).get("type"));
        assertEquals("1", String.valueOf(anyOf.get(1).get("minimum")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allOfKeepsEveryBranchConstraintWhileMergingObjectShape() {
        String spec = """
                openapi: 3.0.3
                info: { title: AllOf, version: 1.0.0 }
                components:
                  schemas:
                    Identity:
                      type: object
                      additionalProperties: false
                      required: [id]
                      properties:
                        id: { type: string, pattern: '^[A-Z]+$' }
                    Counted:
                      type: object
                      required: [count]
                      properties:
                        count: { type: integer, minimum: 1, maximum: 10 }
                paths:
                  /combined:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                allOf:
                                  - $ref: '#/components/schemas/Identity'
                                  - $ref: '#/components/schemas/Counted'
                """;

        Map<String, Object> schema = new OpenApiParser().parse(spec).endpoints().get(0).responseSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<Map<String, Object>> allOf = (List<Map<String, Object>>) schema.get("allOf");
        List<String> required = (List<String>) schema.get("required");

        assertEquals("object", schema.get("type"));
        assertEquals(2, required.size());
        assertTrue(required.containsAll(List.of("id", "count")));
        assertTrue(properties.containsKey("id"));
        assertTrue(properties.containsKey("count"));
        assertTrue(schema.containsKey("allOf"), schema::toString);
        assertEquals(2, allOf.size());
        assertEquals(false, allOf.get(0).get("additionalProperties"));
        Map<String, Object> firstProperties = (Map<String, Object>) allOf.get(0).get("properties");
        assertEquals("^[A-Z]+$", ((Map<String, Object>) firstProperties.get("id")).get("pattern"));
        Map<String, Object> secondProperties = (Map<String, Object>) allOf.get(1).get("properties");
        Map<String, Object> count = (Map<String, Object>) secondProperties.get("count");
        assertEquals("1", String.valueOf(count.get("minimum")));
        assertEquals("10", String.valueOf(count.get("maximum")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsNullableWithoutPublishingTheOpenApiNullableKeyword() {
        String spec = """
                openapi: 3.0.3
                info: { title: Nullable, version: 1.0.0 }
                paths:
                  /nullable:
                    get:
                      parameters:
                        - name: nickname
                          in: query
                          schema: { type: string, nullable: true, minLength: 1 }
                        - name: plain
                          in: query
                          schema: { type: string, nullable: false }
                        - name: composed
                          in: query
                          schema:
                            nullable: true
                            oneOf:
                              - { type: string }
                              - { type: integer }
                      responses:
                        '204': { description: ok }
                """;

        Map<String, Object> parameters = new OpenApiParser().parse(spec).endpoints().get(0).parameters();
        Map<String, Object> nickname = (Map<String, Object>)
                ((Map<String, Object>) parameters.get("query:nickname")).get("schema");
        Map<String, Object> plain = (Map<String, Object>)
                ((Map<String, Object>) parameters.get("query:plain")).get("schema");
        Map<String, Object> composed = (Map<String, Object>)
                ((Map<String, Object>) parameters.get("query:composed")).get("schema");

        assertEquals(List.of("string", "null"), nickname.get("type"));
        assertEquals(1, nickname.get("minLength"));
        assertFalse(nickname.containsKey("nullable"));
        assertEquals("string", plain.get("type"));
        assertFalse(plain.containsKey("nullable"));

        assertFalse(composed.containsKey("nullable"));
        List<Map<String, Object>> nullableAnyOf = (List<Map<String, Object>>) composed.get("anyOf");
        assertEquals(2, nullableAnyOf.size());
        assertTrue(nullableAnyOf.get(0).containsKey("oneOf"));
        assertEquals("null", nullableAnyOf.get(1).get("type"));
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
