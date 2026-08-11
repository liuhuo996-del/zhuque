package com.zhuque.m5_release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.zhuque.common.ApiException;

class McpToolContractTest {

    @Test
    void emitsOnlyOfficialMcpToolFieldsAndKeepsVendorConfigurationOut() {
        Map<String, Object> orderId = new LinkedHashMap<>();
        orderId.put("type", "string");
        orderId.put("description", "Order identifier");
        orderId.put("format", "uuid");
        orderId.put("x-semantic", "business_key");

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("orderId", orderId));
        schema.put("required", List.of("orderId"));
        schema.put("additionalProperties", false);
        schema.put("x-enrichment", Map.of("model", "must-not-leak"));
        schema.put("x-review", Map.of("reviewedBy", "must-not-leak"));

        Map<String, Object> tool = McpToolContract.compile("get_order", "Query one order", schema);

        assertEquals(Set.of("name", "description", "inputSchema"), tool.keySet());
        assertEquals("get_order", tool.get("name"));
        assertEquals("Query one order", tool.get("description"));

        Map<String, Object> publishedSchema = map(tool.get("inputSchema"));
        assertEquals("object", publishedSchema.get("type"));
        assertFalse(publishedSchema.containsKey("x-enrichment"));
        assertFalse(publishedSchema.containsKey("x-review"));
        Map<String, Object> publishedOrderId = map(map(publishedSchema.get("properties")).get("orderId"));
        assertFalse(publishedOrderId.containsKey("x-semantic"));
        assertEquals("uuid", publishedOrderId.get("format"));

        // 发布编译必须是防御性复制，内部证据仍留在 GateForge manifest/数据库中。
        assertTrue(schema.containsKey("x-enrichment"));
        assertTrue(orderId.containsKey("x-semantic"));
    }

    @Test
    void rejectsShapesThatAreNotValidMcpToolInputSchemas() {
        assertContractFailure(Map.of("type", "array", "items", Map.of("type", "string")),
                "固定为 object");
        assertContractFailure(Map.of(
                "type", "object",
                "properties", Map.of("orderId", Map.of("type", "string")),
                "required", List.of("missing")), "不存在的属性");
        assertContractFailure(Map.of(
                "type", "object",
                "properties", Map.of("orderId", true)), "对象式 JSON Schema");
    }

    @Test
    void failsClosedForStandardFeaturesThatPinnedHigressCannotConvertLosslessly() {
        for (Map<String, Object> property : List.of(
                Map.<String, Object>of("oneOf", List.of(
                        Map.of("type", "string"), Map.of("type", "integer"))),
                Map.<String, Object>of("type", List.of("string", "null")),
                Map.<String, Object>of("type", "string", "nullable", true),
                Map.<String, Object>of("type", "object", "x-recursive", true))) {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of("value", property));

            ApiException error = assertThrows(ApiException.class,
                    () -> McpToolContract.compile("example", "Example tool", schema));

            assertTrue(error.what().startsWith("MCP Tool 契约不兼容"), error.what());
            assertFalse(error.fix().isBlank());
        }
    }

    @Test
    void requiresDescriptionsAsGateForgePublishingPolicy() {
        ApiException error = assertThrows(ApiException.class,
                () -> McpToolContract.compile("get_order", "  ", Map.of("type", "object")));

        assertTrue(error.what().contains("description"), error.what());
        assertTrue(error.fix().contains("描述"), error.fix());
    }

    private static void assertContractFailure(Map<String, Object> schema, String expected) {
        ApiException error = assertThrows(ApiException.class,
                () -> McpToolContract.compile("example", "Example tool", schema));
        assertTrue(error.what().contains(expected), error.what());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
