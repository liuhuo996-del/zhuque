package com.zhuque.m6_testing;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.ToolRow;

/**
 * M6-L1 · 内置 mock server。v1 必须有，不是可选项——
 * 企业常常没有 staging，L1 默认打 mock。
 *
 * 生成规则：从 OpenAPI 的 example 生成响应；无 example 时按 schema 造
 * （string 用字段名占位、number 用边界内随机、enum 取第一个值）。
 * mock 要能表达三种鉴权响应（带 key / 不带 / 错 key），
 * 以及"必填缺失时返回结构化错误"的正反例。
 */
@Component
public class MockServerFactory {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ControlPlaneRepository repository;

    public MockServerFactory(ControlPlaneRepository repository) {
        this.repository = repository;
    }

    public record MockHandle(String baseUrl, AutoCloseable shutdown) {}

    /**
     * 功能：为一个 api_source 启动临时 mock server（随机端口），
     * 返回 baseUrl 供 L1 把 request_template 的 host 替换过去。
     * 用完必须关（try-with-resources 语义）。
     */
    public MockHandle start(UUID apiSourceId) {
        List<ToolRow> tools = repository.toolsBySource(apiSourceId);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (ToolRow tool : tools) {
                server.createContext(tool.path(), exchange -> handle(exchange, tool));
            }
            server.start();
            return new MockHandle("http://127.0.0.1:" + server.getAddress().getPort(), () -> server.stop(0));
        } catch (Exception error) {
            throw com.zhuque.common.ApiException.unavailable("无法启动内置 mock：" + error.getMessage(),
                    "检查本机随机端口权限后重试");
        }
    }

    private static void handle(HttpExchange exchange, ToolRow tool) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        String key = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (key == null || key.isBlank()) {
            respond(exchange, 401, Map.of("error", "missing_api_key"));
            return;
        }
        if (!"mock-valid-key".equals(key)) {
            respond(exchange, 403, Map.of("error", "invalid_api_key"));
            return;
        }
        Map<String, Object> body = readBody(exchange);
        List<String> required = required(tool.inputSchema());
        List<String> missing = required.stream().filter(field -> !body.containsKey(field)
                && !query(exchange).containsKey(field) && !pathContains(tool.path(), exchange.getRequestURI().getPath(), field))
                .toList();
        if (!missing.isEmpty()) {
            respond(exchange, 400, Map.of("error", "missing_required_parameters", "fields", missing));
            return;
        }
        respond(exchange, 200, response(tool));
    }

    private static Map<String, Object> response(ToolRow tool) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : tool.outputFields()) {
            String leaf = field.replace("[]", "");
            leaf = leaf.substring(leaf.lastIndexOf('.') + 1);
            result.putIfAbsent(leaf, leaf + "_example");
        }
        if (result.isEmpty()) result.put("ok", true);
        return result;
    }

    private static Map<String, Object> readBody(HttpExchange exchange) {
        try {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            if (bytes.length == 0) return Map.of();
            return JSON.readValue(bytes, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static boolean pathContains(String template, String actual, String field) {
        return template.contains("{" + field + "}") && !actual.contains("{" + field + "}");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> schema) {
        Object value = schema.get("required");
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static void respond(HttpExchange exchange, int status, Object value) throws java.io.IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.sendResponseHeaders(status, body.length);
        try (var stream = exchange.getResponseBody()) { stream.write(body); }
    }
}
