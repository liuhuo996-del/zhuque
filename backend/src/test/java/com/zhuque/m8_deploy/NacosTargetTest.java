package com.zhuque.m8_deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zhuque.common.ApiException;

class NacosTargetTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Test
    void firstApplyUsesNacos3McpRegistryEndpointAndForm() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(404, "{\"code\":20004,\"message\":\"mcp server not found\"}");
            nacos.enqueue(200, "{\"code\":0,\"message\":\"success\"}");
            NacosTarget target = target(nacos);
            Map<String, Object> service = service("new description");

            target.apply(UUID.randomUUID(), Map.of(
                    "mcpName", "mcp-dept-agent",
                    "service", service));

            assertEquals(2, nacos.requests.size());
            CapturedRequest read = nacos.requests.get(0);
            assertEquals("GET", read.method());
            assertEquals("/nacos/v3/admin/ai/mcp", read.uri().getPath());
            assertEquals(Map.of("namespaceId", "prod", "mcpName", "mcp-dept-agent"),
                    parameters(read.uri().getRawQuery()));

            CapturedRequest create = nacos.requests.get(1);
            assertEquals("POST", create.method());
            assertEquals("/nacos/v3/admin/ai/mcp", create.uri().getPath());
            assertTrue(create.contentType().startsWith("application/x-www-form-urlencoded"));
            Map<String, String> form = parameters(create.body());
            assertEquals(Set.of("namespaceId", "mcpName", "serverSpecification",
                    "toolSpecification", "endpointSpecification"), form.keySet());
            assertEquals("prod", form.get("namespaceId"));
            assertEquals("mcp-dept-agent", form.get("mcpName"));
            assertEquals(service.get("serverSpecification"), jsonMap(form.get("serverSpecification")));
            assertEquals(service.get("toolSpecification"), jsonMap(form.get("toolSpecification")));
            assertEquals(service.get("endpointSpecification"), jsonMap(form.get("endpointSpecification")));
        }
    }

    @Test
    void updateUsesPutAndCarriesRegistryIdAndOverrideFlags() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(200, detailResponse("old description"));
            nacos.enqueue(200, endpointResponse());
            nacos.enqueue(200, "{\"code\":0,\"message\":\"success\"}");
            NacosTarget target = target(nacos);
            Map<String, Object> desired = service("new description");

            target.apply(UUID.randomUUID(), Map.of(
                    "mcpName", "mcp-dept-agent",
                    "service", desired));

            assertEquals(3, nacos.requests.size());
            CapturedRequest update = nacos.requests.get(2);
            assertEquals("PUT", update.method());
            assertEquals("/nacos/v3/admin/ai/mcp", update.uri().getPath());
            Map<String, String> form = parameters(update.body());
            assertEquals("true", form.get("latest"));
            assertEquals("true", form.get("overrideExisting"));
            Map<String, Object> server = jsonMap(form.get("serverSpecification"));
            assertEquals("mcp-id-1", server.get("id"));
            assertEquals("new description", server.get("description"));
        }
    }

    @Test
    void onlyNacosResourceNotFoundCodeIsTreatedAsAbsent() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(404, "{\"code\":20004,\"message\":\"not found\"}");
            nacos.enqueue(404, "{\"code\":30000,\"message\":\"namespace rejected\"}");
            nacos.enqueue(200, "{\"code\":30000,\"message\":\"permission rejected\"}");
            NacosTarget target = target(nacos);

            assertNull(target.read("mcp-missing"));

            ApiException httpBusinessError = assertThrows(ApiException.class,
                    () -> target.read("mcp-not-missing"));
            assertEquals(503, httpBusinessError.status().value());
            assertTrue(httpBusinessError.what().contains("HTTP 404"));
            assertTrue(httpBusinessError.what().contains("namespace rejected"));

            ApiException nacosBusinessError = assertThrows(ApiException.class,
                    () -> target.read("mcp-business-error"));
            assertEquals(503, nacosBusinessError.status().value());
            assertTrue(nacosBusinessError.what().contains("Nacos code=30000"));
            assertTrue(nacosBusinessError.what().contains("permission rejected"));
        }
    }

    @Test
    void probeUsesNacos3AdminStateEndpoint() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(200, "{\"code\":0,\"data\":{\"version\":\"3.0.1\"}}");
            NacosTarget target = target(nacos);

            assertEquals("3.0.1", target.probeVersion());

            assertEquals(1, nacos.requests.size());
            CapturedRequest probe = nacos.requests.get(0);
            assertEquals("GET", probe.method());
            assertEquals("/nacos/v3/admin/core/state", probe.uri().getPath());
            assertNull(probe.uri().getRawQuery());
        }
    }

    @Test
    void readNormalizesMcpDetailAndGeneratedDirectEndpoint() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(200, detailResponse("old description"));
            nacos.enqueue(200, endpointResponse());
            NacosTarget target = target(nacos);

            Map<String, Object> snapshot = target.read("mcp-dept-agent.json");

            assertEquals("mcp-id-1", snapshot.get("mcpId"));
            assertEquals(service("old description"), snapshot.get("service"));
            assertEquals(2, nacos.requests.size());
            assertEquals(Map.of("namespaceId", "prod", "mcpName", "mcp-dept-agent"),
                    parameters(nacos.requests.get(0).uri().getRawQuery()));
            CapturedRequest endpoint = nacos.requests.get(1);
            assertEquals("/nacos/v3/admin/ns/instance/list", endpoint.uri().getPath());
            assertEquals(Map.of(
                    "namespaceId", "prod",
                    "groupName", "mcp-endpoints",
                    "serviceName", "generated-service-id"),
                    parameters(endpoint.uri().getRawQuery()));
        }
    }

    @Test
    void awaitVisibleReturnsNormalizedRegistrySnapshot() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(200, detailResponse("current"));
            nacos.enqueue(200, endpointResponse());
            NacosTarget target = target(nacos);

            Map<String, Object> snapshot = target.awaitMcpVisible("mcp-dept-agent");

            assertEquals("mcp-id-1", snapshot.get("mcpId"));
            assertEquals(service("current"), snapshot.get("service"));
            assertEquals(2, nacos.requests.size());
            assertEquals(Map.of("namespaceId", "prod", "mcpName", "mcp-dept-agent"),
                    parameters(nacos.requests.get(0).uri().getRawQuery()));
        }
    }

    @Test
    void awaitVisiblePollsUntilPostCreateDetailBecomesVisible() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(404, "{\"code\":20004,\"message\":\"not visible yet\"}");
            nacos.enqueue(404, "{\"code\":20004,\"message\":\"not visible yet\"}");
            nacos.enqueue(200, detailResponse("current"));
            nacos.enqueue(200, endpointResponse());
            NacosTarget target = target(nacos);

            assertEquals("mcp-id-1", target.awaitMcpVisible("mcp-dept-agent").get("mcpId"));

            assertEquals(4, nacos.requests.size());
            assertTrue(nacos.requests.stream().allMatch(request -> "GET".equals(request.method())));
        }
    }

    @Test
    void awaitVisibleRetriesUntilReferencedEndpointIsReady() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(200, detailResponse("current"));
            nacos.enqueue(404, "{\"code\":30000,\"message\":\"cluster DEFAULT is not found\"}");
            nacos.enqueue(200, detailResponse("current"));
            nacos.enqueue(200, endpointResponse());
            NacosTarget target = target(nacos);

            assertEquals("mcp-id-1", target.awaitMcpVisible("mcp-dept-agent").get("mcpId"));

            assertEquals(4, nacos.requests.size());
            assertEquals("/nacos/v3/admin/ns/instance/list", nacos.requests.get(1).uri().getPath());
            assertEquals("/nacos/v3/admin/ai/mcp", nacos.requests.get(2).uri().getPath());
        }
    }

    @Test
    void rollbackKeepsDeletingWhenPendingCreateIsInitiallyInvisible() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(404, "{\"code\":20004,\"message\":\"not visible yet\"}");
            nacos.enqueue(404, "{\"code\":20004,\"message\":\"not visible yet\"}");
            nacos.enqueue(200, "{\"code\":0,\"message\":\"deleted\"}");
            nacos.repeat(404, "{\"code\":20004,\"message\":\"not found\"}");
            NacosTarget target = target(nacos);

            target.restore("mcp-dept-agent", null);

            assertTrue(nacos.requests.size() >= 5,
                    "观察到延迟 create 后必须继续确认稳定缺失");
            assertTrue(nacos.requests.stream().allMatch(request -> "DELETE".equals(request.method())));
        }
    }

    @Test
    void applySkipsWriteWhenNormalizedRegistryStateAlreadyMatches() throws Exception {
        try (FakeNacos nacos = new FakeNacos()) {
            nacos.enqueue(200, detailResponse("same description"));
            nacos.enqueue(200, endpointResponse());
            NacosTarget target = target(nacos);

            target.apply(UUID.randomUUID(), Map.of(
                    "mcpName", "mcp-dept-agent",
                    "service", service("same description")));

            assertEquals(2, nacos.requests.size());
            assertTrue(nacos.requests.stream().allMatch(request -> "GET".equals(request.method())));
            assertFalse(nacos.hasQueuedResponses(), "幂等命中不应再发送 POST/PUT");
        }
    }

    private static NacosTarget target(FakeNacos nacos) {
        NacosTarget target = new NacosTarget();
        ReflectionTestUtils.setField(target, "serverUrl", nacos.baseUrl());
        ReflectionTestUtils.setField(target, "contextPath", "/nacos");
        ReflectionTestUtils.setField(target, "namespace", "prod");
        ReflectionTestUtils.setField(target, "username", "");
        ReflectionTestUtils.setField(target, "password", "");
        ReflectionTestUtils.setField(target, "mcpVisibleTimeout", Duration.ofMillis(50));
        ReflectionTestUtils.setField(target, "mcpPollInterval", Duration.ofMillis(1));
        ReflectionTestUtils.setField(target, "mcpDeleteSettleWindow", Duration.ofMillis(3));
        return target;
    }

    private static Map<String, Object> service(String description) {
        Map<String, Object> server = linked(
                "name", "mcp-dept-agent",
                "protocol", "http",
                "frontProtocol", "mcp-sse",
                "description", description,
                "versionDetail", Map.of("version", "1.0.0-deadbeef"),
                "remoteServerConfig", Map.of("exportPath", ""),
                "enabled", true);
        Map<String, Object> toolSpecification = linked(
                "tools", List.of(Map.of(
                        "name", "smoke_status",
                        "description", "read smoke status",
                        "inputSchema", Map.of("type", "object"))),
                "toolsMeta", Map.of("smoke_status", Map.of(
                        "enabled", true,
                        "templates", Map.of("json-go-template", Map.of(
                                "method", "GET",
                                "url", "/smoke")))));
        Map<String, Object> endpoint = linked(
                "type", "DIRECT",
                "data", linked("address", "192.0.2.10", "port", "19090"));
        return linked(
                "serverSpecification", server,
                "toolSpecification", toolSpecification,
                "endpointSpecification", endpoint);
    }

    private static String detailResponse(String description) {
        return """
                {"code":0,"data":{
                  "id":"mcp-id-1",
                  "name":"mcp-dept-agent",
                  "protocol":"http",
                  "frontProtocol":"mcp-sse",
                  "description":"%s",
                  "versionDetail":{"version":"1.0.0-deadbeef"},
                  "remoteServerConfig":{
                    "exportPath":"",
                    "serviceRef":{
                      "namespaceId":"prod",
                      "groupName":"mcp-endpoints",
                      "serviceName":"generated-service-id"
                    }
                  },
                  "enabled":true,
                  "toolSpec":{
                    "tools":[{
                      "name":"smoke_status",
                      "description":"read smoke status",
                      "inputSchema":{"type":"object"},
                      "ignoredNull":null
                    }],
                    "toolsMeta":{
                      "smoke_status":{
                        "enabled":true,
                        "templates":{
                          "json-go-template":{"method":"GET","url":"/smoke","ignoredNull":null}
                        }
                      }
                    }
                  }
                }}
                """.formatted(description);
    }

    private static String endpointResponse() {
        return """
                {"code":0,"data":[
                  {"ip":"192.0.2.10","port":19090,"healthy":true}
                ]}
                """;
    }

    private static Map<String, Object> jsonMap(String json) throws Exception {
        return JSON.readValue(json, MAP);
    }

    private static Map<String, String> parameters(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : value.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(decode(parts[0]), parts.length == 1 ? "" : decode(parts[1]));
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    private record CapturedRequest(String method, URI uri, String contentType, String body) {}

    private record Reply(int status, String body) {}

    private static final class FakeNacos implements AutoCloseable {
        private final HttpServer server;
        private final Deque<Reply> replies = new ArrayDeque<>();
        private final List<CapturedRequest> requests = new ArrayList<>();
        private Reply fallbackReply;

        private FakeNacos() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void enqueue(int status, String body) {
            replies.addLast(new Reply(status, body));
        }

        private void repeat(int status, String body) {
            fallbackReply = new Reply(status, body);
        }

        private boolean hasQueuedResponses() {
            return !replies.isEmpty();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body));
            Reply reply = replies.pollFirst();
            if (reply == null) {
                reply = fallbackReply == null
                        ? new Reply(500, "{\"code\":500,\"message\":\"unexpected request\"}")
                        : fallbackReply;
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
