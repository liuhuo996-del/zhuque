package com.zhuque.m1_toolpool;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

/**
 * M1-步骤1 · OpenAPI 3.x 解析。
 *
 * 输入：OpenAPI 文档（URL 拉取或上传的原文）
 * 输出：一组「已展开的 endpoint」——后续草稿生成不再需要回头看原文档
 *
 * 必须处理：
 * - 展开 $ref（含跨文件 ref 可先不支持，报清晰错误）
 * - 合并 allOf
 * - oneOf/anyOf：取属性并集，并在结果上标注 variantOf 信息（富化和 L0 检查要用）
 * - 继承 path 级 parameters 到每个 operation
 *
 * 容错纪律：不合规文档逐 endpoint 报错，不整体失败。
 * 一个 500 endpoint 的文档里有 3 个坏的，应该得到 497 条结果 + 3 条 ParseError，
 * 每条 ParseError 要指出位置（path + method + 哪个 $ref / 哪行），
 * 前端会把它渲染成「OpenAPI 解析失败：第 N 行 $ref ... 无法解析」。
 */
@Component
public class OpenApiParser {

    /** 解析成功的单个 endpoint（已完全展开，自包含） */
    public record ParsedEndpoint(
            String operationId,      // 可能为空，为空时草稿生成用 method+path 造名
            String method,
            String path,
            String serverUrl,       // 已按 operation/path/root 优先级解析，并展开变量
            String summary,
            Map<String, Object> parameters,     // 已合并 path 级参数，含 in/required/schema
            Map<String, Object> requestBodySchema,
            Map<String, Object> responseSchema, // 主成功响应（2xx）的 schema
            Map<String, Object> examples,
            Map<String, Object> l1ControlledFixture) {
    }

    /** 单个 endpoint 的解析失败记录（逐条报错，不整体失败） */
    public record ParseError(String path, String method, String location, String message) {
    }

    public record ParseResult(List<ParsedEndpoint> endpoints, List<ParseError> errors) {
    }

    /**
     * 功能：解析入口。specText 为文档原文（JSON 或 YAML）。
     * 实现提示：swagger-parser 做底座（ResolveFully 展开 $ref），
     * allOf 合并与 oneOf 并集要自己遍历 schema 树完成。
     */
    public ParseResult parse(String specText) {
        return parse(specText, null);
    }

    /** fallbackServerUrl 用于 OpenAPI 未声明 servers 时补全 REST 请求的绝对地址。 */
    public ParseResult parse(String specText, String fallbackServerUrl) {
        if (specText == null || specText.isBlank()) {
            throw new IllegalArgumentException("OpenAPI 文档为空");
        }

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        options.setFlatten(false);
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(specText, null, options);
        OpenAPI api = parsed.getOpenAPI();
        if (api == null) {
            throw new IllegalArgumentException("OpenAPI 解析失败：" + String.join("；", parsed.getMessages()));
        }

        List<ParsedEndpoint> endpoints = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();
        if (parsed.getMessages() != null) {
            for (String message : parsed.getMessages()) {
                errors.add(new ParseError("*", "*", "document", message));
            }
        }
        if (api.getPaths() == null) {
            return new ParseResult(List.of(), List.copyOf(errors));
        }

        api.getPaths().forEach((path, item) -> {
            if (item == null) {
                errors.add(new ParseError(path, "*", "path", "PathItem 为空"));
                return;
            }
            item.readOperationsMap().forEach((method, operation) -> {
                String methodName = method.name().toLowerCase();
                try {
                    endpoints.add(parseEndpoint(path, methodName, item, operation, api, fallbackServerUrl));
                } catch (RuntimeException error) {
                    errors.add(new ParseError(path, methodName, "operation", safeMessage(error)));
                }
            });
        });
        return new ParseResult(List.copyOf(endpoints), List.copyOf(errors));
    }

    private ParsedEndpoint parseEndpoint(String path, String method, PathItem item, Operation operation,
                                         OpenAPI api, String fallbackServerUrl) {
        if (operation == null) {
            throw new IllegalArgumentException("operation 为空");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        mergeParameters(parameters, item.getParameters());
        mergeParameters(parameters, operation.getParameters());

        Map<String, Object> requestSchema = schemaFromRequest(operation.getRequestBody());
        Map<String, Object> responseSchema = schemaFromResponse(operation.getResponses());
        Map<String, Object> examples = collectExamples(operation.getRequestBody(), operation.getResponses());
        String summary = firstNonBlank(operation.getSummary(), operation.getDescription(), "");
        String serverUrl = resolveServerUrl(operation, item, api, fallbackServerUrl);
        return new ParsedEndpoint(operation.getOperationId(), method, path, serverUrl, summary,
                parameters, requestSchema, responseSchema, examples, l1ControlledFixture(operation));
    }

    /**
     * L1 的自动出网必须是 endpoint 级的显式选择。OpenAPI 的 vendor extension 可能携带
     * 任意 JSON，不能把它原样冻结到请求模板；只提取这两个经过校验的最小字段。
     */
    private static Map<String, Object> l1ControlledFixture(Operation operation) {
        if (operation.getExtensions() == null) {
            return Map.of();
        }
        Object extension = operation.getExtensions().get("x-zhuque-l1");
        if (!(extension instanceof Map<?, ?> raw)
                || !Boolean.TRUE.equals(raw.get("testSafe"))
                || !(raw.get("fixture") instanceof String fixture)
                || fixture.isBlank()) {
            return Map.of();
        }
        return Map.of("testSafe", true, "fixture", fixture.trim());
    }

    private static String resolveServerUrl(Operation operation, PathItem item, OpenAPI api,
                                           String fallbackServerUrl) {
        List<Server> servers = operation.getServers();
        if (servers == null || servers.isEmpty()) {
            servers = item.getServers();
        }
        if ((servers == null || servers.isEmpty()) && api.getServers() != null) {
            servers = api.getServers();
        }
        String value = servers == null || servers.isEmpty() ? null : expandServer(servers.get(0));
        if (value == null || value.isBlank() || "/".equals(value)) {
            return trimTrailingSlash(fallbackServerUrl);
        }
        try {
            URI server = URI.create(value);
            if (server.isAbsolute()) {
                return trimTrailingSlash(server.toString());
            }
            if (fallbackServerUrl != null && !fallbackServerUrl.isBlank()) {
                String base = fallbackServerUrl.endsWith("/") ? fallbackServerUrl : fallbackServerUrl + "/";
                return trimTrailingSlash(URI.create(base).resolve(value).toString());
            }
        } catch (IllegalArgumentException ignored) {
            // 下方保留原值，L0 会把非绝对 URL 明确判为失败，而不是导入阶段静默改写。
        }
        return trimTrailingSlash(value);
    }

    private static String expandServer(Server server) {
        if (server == null || server.getUrl() == null) {
            return null;
        }
        String result = server.getUrl();
        if (server.getVariables() != null) {
            for (var entry : server.getVariables().entrySet()) {
                String replacement = entry.getValue() == null ? null : entry.getValue().getDefault();
                if (replacement != null) {
                    result = result.replace("{" + entry.getKey() + "}", replacement);
                }
            }
        }
        return result;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("/+$", "");
    }

    private void mergeParameters(Map<String, Object> target, List<Parameter> parameters) {
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || parameter.getIn() == null) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", parameter.getName());
            value.put("in", parameter.getIn());
            value.put("required", Boolean.TRUE.equals(parameter.getRequired()) || "path".equals(parameter.getIn()));
            if (parameter.getDescription() != null) {
                value.put("description", parameter.getDescription());
            }
            value.put("schema", schemaToMap(parameter.getSchema(), 0,
                    Collections.newSetFromMap(new IdentityHashMap<>())));
            // operation 级同名参数覆盖 path 级参数。
            target.put(parameter.getIn() + ":" + parameter.getName(), value);
        }
    }

    private Map<String, Object> schemaFromRequest(RequestBody body) {
        if (body == null || body.getContent() == null) {
            return Map.of();
        }
        MediaType media = preferredMedia(body.getContent());
        if (media == null || media.getSchema() == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(schemaToMap(media.getSchema(), 0,
                Collections.newSetFromMap(new IdentityHashMap<>())));
        result.put("x-content-type", preferredContentType(body.getContent()));
        result.put("x-body-required", Boolean.TRUE.equals(body.getRequired()));
        return result;
    }

    private Map<String, Object> schemaFromResponse(Map<String, ApiResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return Map.of();
        }
        ApiResponse selected = responses.entrySet().stream()
                .filter(entry -> entry.getKey().matches("2\\d\\d|2XX"))
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(responses.get("default"));
        if (selected == null || selected.getContent() == null) {
            return Map.of();
        }
        MediaType media = preferredMedia(selected.getContent());
        return media == null ? Map.of() : schemaToMap(media.getSchema(), 0,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private Map<String, Object> collectExamples(RequestBody request, Map<String, ApiResponse> responses) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (request != null && request.getContent() != null) {
            MediaType media = preferredMedia(request.getContent());
            Object example = firstExample(media);
            if (example != null) {
                result.put("request", example);
            }
        }
        if (responses != null) {
            responses.entrySet().stream().filter(entry -> entry.getKey().startsWith("2"))
                    .sorted(Map.Entry.comparingByKey()).findFirst().ifPresent(entry -> {
                        Content content = entry.getValue().getContent();
                        Object example = firstExample(content == null ? null : preferredMedia(content));
                        if (example != null) {
                            result.put("response", example);
                        }
                    });
        }
        return result;
    }

    private Map<String, Object> schemaToMap(Schema<?> schema, int depth, Set<Schema<?>> visiting) {
        if (schema == null) {
            return Map.of();
        }
        if (depth > 20 || !visiting.add(schema)) {
            return Map.of("type", "object", "x-recursive", true);
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            put(result, "type", schema.getType());
            put(result, "format", schema.getFormat());
            put(result, "description", schema.getDescription());
            put(result, "title", schema.getTitle());
            put(result, "default", schema.getDefault());
            put(result, "example", schema.getExample());
            put(result, "nullable", schema.getNullable());
            put(result, "readOnly", schema.getReadOnly());
            put(result, "writeOnly", schema.getWriteOnly());
            put(result, "minimum", schema.getMinimum());
            put(result, "maximum", schema.getMaximum());
            put(result, "minLength", schema.getMinLength());
            put(result, "maxLength", schema.getMaxLength());
            put(result, "pattern", schema.getPattern());
            if (schema.getEnum() != null) {
                result.put("enum", List.copyOf(schema.getEnum()));
            }
            if (schema.getRequired() != null) {
                result.put("required", List.copyOf(new LinkedHashSet<>(schema.getRequired())));
            }
            if (schema.getProperties() != null) {
                Map<String, Object> properties = new LinkedHashMap<>();
                schema.getProperties().forEach((name, child) -> properties.put(name,
                        schemaToMap((Schema<?>) child, depth + 1, visiting)));
                result.put("properties", properties);
                result.putIfAbsent("type", "object");
            }
            if (schema.getItems() != null) {
                result.put("items", schemaToMap(schema.getItems(), depth + 1, visiting));
                result.putIfAbsent("type", "array");
            }
            if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
                result.put("additionalProperties", schemaToMap(additional, depth + 1, visiting));
            } else if (schema.getAdditionalProperties() != null) {
                result.put("additionalProperties", schema.getAdditionalProperties());
            }
            mergeCompositions(result, schema.getAllOf(), "allOf", depth, visiting, true);
            mergeCompositions(result, schema.getOneOf(), "oneOf", depth, visiting, false);
            mergeCompositions(result, schema.getAnyOf(), "anyOf", depth, visiting, false);
            if (result.isEmpty() && schema.get$ref() != null) {
                throw new IllegalArgumentException("$ref 无法展开：" + schema.get$ref());
            }
            return result;
        } finally {
            visiting.remove(schema);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeCompositions(Map<String, Object> target, List<Schema> variants, String kind,
                                   int depth, Set<Schema<?>> visiting, boolean allOf) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        List<String> variantNames = new ArrayList<>();
        for (int index = 0; index < variants.size(); index++) {
            Schema<?> variant = variants.get(index);
            Map<String, Object> expanded = schemaToMap(variant, depth + 1, visiting);
            Object properties = expanded.get("properties");
            if (properties instanceof Map<?, ?> propertyMap) {
                Map<String, Object> merged = (Map<String, Object>) target.computeIfAbsent(
                        "properties", ignored -> new LinkedHashMap<>());
                propertyMap.forEach((key, value) -> merged.put(String.valueOf(key), value));
                target.putIfAbsent("type", "object");
            }
            Object required = expanded.get("required");
            if (required instanceof List<?> values) {
                LinkedHashSet<String> merged = new LinkedHashSet<>((List<String>)
                        target.getOrDefault("required", List.of()));
                values.forEach(value -> merged.add(String.valueOf(value)));
                target.put("required", List.copyOf(merged));
            }
            variantNames.add(firstNonBlank(variant.getName(), variant.getTitle(), kind + "[" + index + "]"));
            if (allOf) {
                expanded.forEach(target::putIfAbsent);
            }
        }
        if (!allOf) {
            target.put("x-variant-of", variantNames);
        }
    }

    private static MediaType preferredMedia(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        for (String type : List.of("application/json", "application/*+json", "*/*")) {
            if (content.containsKey(type)) {
                return content.get(type);
            }
        }
        return content.values().iterator().next();
    }

    private static String preferredContentType(Content content) {
        if (content.containsKey("application/json")) {
            return "application/json";
        }
        return content.keySet().iterator().next();
    }

    private static Object firstExample(MediaType media) {
        if (media == null) {
            return null;
        }
        if (media.getExample() != null) {
            return media.getExample();
        }
        if (media.getExamples() != null && !media.getExamples().isEmpty()) {
            io.swagger.v3.oas.models.examples.Example example = media.getExamples().values().iterator().next();
            return example.getValue();
        }
        return null;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
