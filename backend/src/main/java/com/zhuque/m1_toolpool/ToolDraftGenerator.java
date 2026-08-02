package com.zhuque.m1_toolpool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.zhuque.m1_toolpool.OpenApiParser.ParsedEndpoint;

/**
 * M1-步骤2 · 从解析结果生成 tool 草稿（enrichment_status = raw）。
 */
@Component
public class ToolDraftGenerator {

    private static final int MAX_NAME_LENGTH = 80;
    private static final Pattern ILLEGAL = Pattern.compile("[^a-z0-9_]+");

    private final OutputFieldExtractor outputFieldExtractor;

    public ToolDraftGenerator(OutputFieldExtractor outputFieldExtractor) {
        this.outputFieldExtractor = outputFieldExtractor;
    }

    /** tool 表条目的草稿形态（未落库，未富化） */
    public record ToolDraft(
            String name,
            String description,          // 草稿阶段 = summary 原文，富化后重写
            Map<String, Object> inputSchema,
            Map<String, Object> requestTemplate,
            String method,
            String path,
            java.util.List<String> outputFields) {
    }

    /**
     * 功能：生成全局唯一的 tool 名。
     * 规则：{source_slug}_{operationId}；无 operationId 时从 method+path 生成
     * （如 get_orders_order_id），并处理非法字符和长度上限。
     * 唯一性冲突（同一 source 两个同名 operationId）时加序号后缀并记 warning。
     */
    public String buildName(String sourceSlug, ParsedEndpoint ep) {
        if (ep == null) {
            throw new IllegalArgumentException("endpoint 不能为空");
        }
        String source = sanitize(sourceSlug == null ? "source" : sourceSlug);
        String operation = ep.operationId();
        if (operation == null || operation.isBlank()) {
            operation = ep.method() + "_" + ep.path();
        }
        String name = source + "_" + sanitize(operation);
        name = name.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH).replaceAll("_+$", "");
        }
        return name.isBlank() ? "source_tool" : name;
    }

    /**
     * 功能：生成 input_schema（JSON Schema）+ request_template。
     * request_template 结构：method / url 模板（path 参数占位）/ headers /
     * query 映射 / argsToJsonBody 的映射。占位符命名必须与 input_schema
     * 的属性名严格一致——L0 会检查两边一致性，M8 的 Nacos payload 直接引用它。
     */
    public ToolDraft generate(String sourceSlug, ParsedEndpoint ep) {
        return generateWithName(buildName(sourceSlug, ep), ep);
    }

    /** 同一文档批量生成时在本地解决重复 operationId，保证结果可重跑且顺序稳定。 */
    public List<ToolDraft> generateAll(String sourceSlug, List<ParsedEndpoint> endpoints) {
        Map<String, Integer> counters = new LinkedHashMap<>();
        List<ToolDraft> drafts = new ArrayList<>();
        for (ParsedEndpoint endpoint : endpoints) {
            String base = buildName(sourceSlug, endpoint);
            int number = counters.merge(base, 1, Integer::sum);
            String name = number == 1 ? base : withSuffix(base, "_" + number);
            drafts.add(generateWithName(name, endpoint));
        }
        return List.copyOf(drafts);
    }

    @SuppressWarnings("unchecked")
    private ToolDraft generateWithName(String name, ParsedEndpoint ep) {
        Map<String, Object> properties = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        Map<String, String> locations = new LinkedHashMap<>();
        List<Map<String, Object>> headers = new ArrayList<>();
        List<String> query = new ArrayList<>();
        String url = ep.path();

        for (Object descriptorValue : ep.parameters().values()) {
            if (!(descriptorValue instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> descriptor = (Map<String, Object>) raw;
            String paramName = String.valueOf(descriptor.get("name"));
            String location = String.valueOf(descriptor.get("in"));
            Object schemaValue = descriptor.get("schema");
            Map<String, Object> property = schemaValue instanceof Map<?, ?> schema
                    ? new LinkedHashMap<>((Map<String, Object>) schema) : new LinkedHashMap<>();
            if (descriptor.get("description") != null && !property.containsKey("description")) {
                property.put("description", descriptor.get("description"));
            }
            property.putIfAbsent("description", paramName + "（" + location + " 参数）");
            properties.put(paramName, property);
            locations.put(paramName, location);
            if (Boolean.TRUE.equals(descriptor.get("required"))) {
                required.add(paramName);
            }
            String expression = "{{.args." + paramName + "}}";
            switch (location) {
                case "path" -> url = replacePathPlaceholder(url, paramName, expression);
                case "query" -> query.add(paramName + "=" + expression);
                case "header" -> headers.add(Map.of("key", paramName, "value", expression));
                default -> { /* cookie 参数当前不映射到后端请求。 */ }
            }
        }

        String bodyTemplate = null;
        Map<String, Object> body = ep.requestBodySchema();
        Object bodyProperties = body.get("properties");
        if (bodyProperties instanceof Map<?, ?> bodyMap) {
            for (Map.Entry<?, ?> entry : bodyMap.entrySet()) {
                String paramName = String.valueOf(entry.getKey());
                Map<String, Object> schema = entry.getValue() instanceof Map<?, ?> value
                        ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
                schema.putIfAbsent("description", paramName + "（JSON body 字段）");
                properties.put(paramName, schema);
                locations.put(paramName, "body");
            }
            Object bodyRequired = body.get("required");
            if (bodyRequired instanceof List<?> list) {
                list.forEach(item -> required.add(String.valueOf(item)));
            }
            bodyTemplate = jsonBodyTemplate(bodyMap.keySet().stream().map(String::valueOf).toList());
        } else if (!body.isEmpty()) {
            Map<String, Object> bodyParam = new LinkedHashMap<>(body);
            bodyParam.keySet().removeIf(key -> key.startsWith("x-"));
            bodyParam.putIfAbsent("description", "请求体");
            properties.put("body", bodyParam);
            locations.put("body", "body");
            if (Boolean.TRUE.equals(body.get("x-body-required"))) {
                required.add("body");
            }
            bodyTemplate = "{{ toJson .args.body }}";
        }

        if (!query.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + String.join("&", query);
        }
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.put("required", List.copyOf(required));
        }
        inputSchema.put("additionalProperties", false);

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("method", ep.method().toUpperCase());
        template.put("url", url);
        template.put("headers", headers);
        if (bodyTemplate != null) {
            template.put("body", bodyTemplate);
            if (body.get("x-content-type") != null && headers.stream()
                    .noneMatch(header -> "content-type".equalsIgnoreCase(String.valueOf(header.get("key"))))) {
                headers.add(Map.of("key", "Content-Type", "value", String.valueOf(body.get("x-content-type"))));
            }
        }
        template.put("x-arg-locations", locations);
        return new ToolDraft(name, ep.summary() == null ? "" : ep.summary(), inputSchema,
                template, ep.method().toUpperCase(), ep.path(), outputFieldExtractor.extract(ep.responseSchema()));
    }

    private static String replacePathPlaceholder(String url, String name, String expression) {
        return url.replace("{" + name + "}", expression)
                .replace("{" + name + "+}", expression);
    }

    private static String jsonBodyTemplate(List<String> fields) {
        List<String> entries = fields.stream()
                .map(field -> "\"" + escapeJson(field) + "\":{{ toJson .args." + field + " }}")
                .toList();
        return "{" + String.join(",", entries) + "}";
    }

    private static String sanitize(String value) {
        String snake = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        return ILLEGAL.matcher(snake).replaceAll("_").replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private static String withSuffix(String base, String suffix) {
        int maxBase = Math.max(1, MAX_NAME_LENGTH - suffix.length());
        String trimmed = base.length() > maxBase ? base.substring(0, maxBase) : base;
        return trimmed.replaceAll("_+$", "") + suffix;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
