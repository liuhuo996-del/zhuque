package com.zhuque.m5_release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;
import com.zhuque.m4_closure.ClosureChecker;
import com.zhuque.m4_closure.FieldNormalizer;
import com.zhuque.persistence.ControlPlaneRepository;

class ManifestCompilerTest {

    @Test
    void compilesNativeNacos3McpRegistryPayload() {
        Map<String, Object> requestTemplate = new LinkedHashMap<>();
        requestTemplate.put("method", "GET");
        requestTemplate.put("url", "https://api.example.com:9443/v1/orders/{orderId}?verbose=true");
        requestTemplate.put("headers", Map.of("accept", "application/json"));
        requestTemplate.put("x-arg-locations", Map.of("orderId", "path", "verbose", "query"));
        requestTemplate.put("x-zhuque-l1", Map.of("fixture", "must-not-be-published"));

        ToolFixture tool = new ToolFixture("get_order", requestTemplate);
        ManifestCompiler.CompiledRelease compiled = compiler(new FixtureRepository(List.of(tool))).compile(
                FixtureRepository.AGENT_ID);

        Map<String, Object> payload = compiled.nacosPayload();
        assertEquals("mcp-finance-order-agent", payload.get("mcpName"));

        Map<String, Object> service = map(payload.get("service"));
        Map<String, Object> server = map(service.get("serverSpecification"));
        assertEquals("https", server.get("protocol"));
        assertEquals("mcp-sse", server.get("frontProtocol"));
        assertEquals(Map.of("exportPath", ""), server.get("remoteServerConfig"));
        assertEquals(List.of("TOOL"), server.get("capabilities"));
        assertEquals(true, server.get("enabled"));

        String version = String.valueOf(map(server.get("versionDetail")).get("version"));
        assertTrue(version.matches("1\\.0\\.0-[0-9a-f]{12}"), version);
        assertFalse(version.contains(":"), version);

        Map<String, Object> toolSpecification = map(service.get("toolSpecification"));
        List<?> toolDefinitions = (List<?>) toolSpecification.get("tools");
        assertEquals(1, toolDefinitions.size());
        Map<String, Object> publishedTool = map(toolDefinitions.get(0));
        assertEquals(Set.of("name", "description", "inputSchema"), publishedTool.keySet());
        assertEquals("get_order", publishedTool.get("name"));

        Map<String, Object> toolMeta = map(map(toolSpecification.get("toolsMeta")).get("get_order"));
        Map<String, Object> templates = map(toolMeta.get("templates"));
        assertEquals(1, templates.size());
        Map<String, Object> jsonGoTemplate = map(templates.get("json-go-template"));
        Map<String, Object> publishedRequest = map(jsonGoTemplate.get("requestTemplate"));
        assertEquals("/v1/orders/{orderId}?verbose=true", publishedRequest.get("url"));
        assertEquals("GET", publishedRequest.get("method"));
        assertFalse(publishedRequest.containsKey("x-arg-locations"));
        assertFalse(publishedRequest.containsKey("x-zhuque-l1"));
        assertEquals(Map.of("orderId", "path", "verbose", "query"),
                jsonGoTemplate.get("argsPosition"));
        assertEquals(Map.of(), jsonGoTemplate.get("responseTemplate"));

        Map<String, Object> endpoint = map(service.get("endpointSpecification"));
        assertEquals("DIRECT", endpoint.get("type"));
        assertEquals(Map.of("address", "api.example.com", "port", "9443"), endpoint.get("data"));
        assertEquals(Map.of(), compiled.higressAuthPayload());
        assertEquals(Map.of(
                "nacosMinVersion", "3.0.1",
                "mcpProtocolVersion", "2025-06-18",
                "mcpToolSchemaProfile", "mcp-tool/2025-06-18+higress-2.2.3"),
                compiled.targetConstraints());

        // 编译器必须复制模板再清理运行时字段，不能污染仓库中的冻结输入。
        assertEquals("https://api.example.com:9443/v1/orders/{orderId}?verbose=true",
                requestTemplate.get("url"));
        assertTrue(requestTemplate.containsKey("x-zhuque-l1"));
    }

    @Test
    void rejectsToolsFromDifferentRestOrigins() {
        ToolFixture first = new ToolFixture("get_order",
                Map.of("method", "GET", "url", "https://api.example.com/orders/1"));
        ToolFixture second = new ToolFixture("get_customer",
                Map.of("method", "GET", "url", "https://customers.example.com/customers/1"));

        ApiException error = assertThrows(ApiException.class,
                () -> compiler(new FixtureRepository(List.of(first, second))).compile(FixtureRepository.AGENT_ID));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertTrue(error.what().contains("不支持跨多个 REST origin"), error.what());
        assertTrue(error.what().contains("https://api.example.com"), error.what());
        assertTrue(error.what().contains("https://customers.example.com"), error.what());
    }

    private static ManifestCompiler compiler(ControlPlaneRepository repository) {
        ManifestCompiler compiler = new ManifestCompiler(repository, new ClosureChecker(new FieldNormalizer()));
        ReflectionTestUtils.setField(compiler, "minNacosVersion", "3.0.1");
        return compiler;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private record ToolFixture(String name, Map<String, Object> requestTemplate) {
    }

    private static final class FixtureRepository extends ControlPlaneRepository {
        private static final UUID DEPARTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
        private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
        private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");

        private final DepartmentRow department = new DepartmentRow(DEPARTMENT_ID, "Finance", "finance",
                "consumer-finance", Instant.EPOCH);
        private final AgentRow agent = new AgentRow(AGENT_ID, DEPARTMENT_ID, "Order Agent", "order-agent",
                "Order lookup tools", "", "draft",
                "http://localhost:8080/mcp/mcp-finance-order-agent/sse", Instant.EPOCH);
        private final ApiSourceRow source = new ApiSourceRow(SOURCE_ID, "Order API",
                "https://api.example.com/openapi.json", "sha256:source", Instant.EPOCH, "test");
        private final List<ToolRow> tools;

        FixtureRepository(List<ToolFixture> fixtures) {
            super(null, new ObjectMapper());
            this.tools = fixtures.stream().map(this::tool).toList();
        }

        @Override
        public AgentRow requireAgent(UUID id) {
            assertEquals(AGENT_ID, id);
            return agent;
        }

        @Override
        public DepartmentRow requireDepartment(UUID id) {
            assertEquals(DEPARTMENT_ID, id);
            return department;
        }

        @Override
        public List<IntentRow> intentsForAgent(UUID agentId) {
            assertEquals(AGENT_ID, agentId);
            return List.of();
        }

        @Override
        public List<PackRow> packsForAgent(UUID agentId) {
            assertEquals(AGENT_ID, agentId);
            return List.of();
        }

        @Override
        public List<ToolRow> trashedToolsForAgent(UUID agentId) {
            assertEquals(AGENT_ID, agentId);
            return List.of();
        }

        @Override
        public List<ToolRow> deprecatedToolsForAgent(UUID agentId) {
            assertEquals(AGENT_ID, agentId);
            return List.of();
        }

        @Override
        public List<ToolRow> toolsForAgent(UUID agentId) {
            assertEquals(AGENT_ID, agentId);
            return tools;
        }

        @Override
        public List<ToolRow> tools() {
            return tools;
        }

        @Override
        public ApiSourceRow requireApiSource(UUID id) {
            assertEquals(SOURCE_ID, id);
            return source;
        }

        @Override
        public List<AgentKeyRow> agentKeys(UUID agentId, boolean activeOnly) {
            assertEquals(AGENT_ID, agentId);
            assertTrue(activeOnly);
            return List.of();
        }

        private ToolRow tool(ToolFixture fixture) {
            return new ToolRow(UUID.randomUUID(), SOURCE_ID, fixture.name(), "Lookup " + fixture.name(),
                    Map.of("type", "object", "properties", Map.of()), fixture.requestTemplate(),
                    "GET", "/" + fixture.name(), "read", "reviewed", List.of("id"), List.of(),
                    1, Instant.EPOCH, null, null);
        }
    }
}
