package com.zhuque.m5_release;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.zhuque.common.ApiException;

/**
 * GateForge 发布到 Nacos {@code toolSpecification.tools[]} 前的 MCP Tool 契约边界。
 *
 * <p>目标协议固定为 MCP 2025-06-18。该版本的官方 Tool 定义要求
 * {@code name} 与根类型为 {@code object} 的 {@code inputSchema}；description
 * 虽为协议可选字段，但 GateForge 为了保证模型可选择工具，将其提升为发布必填。
 *
 * <p>Nacos 的 {@code toolsMeta}、REST requestTemplate 以及 GateForge 的审核证据
 * 都不是 MCP Tool 字段，不能从这里泄漏到 {@code tools/list}。当前 Higress 2.2.3
 * 只支持对象式属性 schema，组合式 schema 和 union type 会在冻结时 fail closed，
 * 避免发布成功后被网关静默错误转换。
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-06-18/server/tools">MCP Tools 2025-06-18</a>
 * @see <a href="https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2025-06-18/schema.json">Official MCP schema</a>
 */
final class McpToolContract {

    static final String PROTOCOL_VERSION = "2025-06-18";
    static final String SCHEMA_PROFILE = "mcp-tool/2025-06-18+higress-2.2.3";

    private static final Set<String> INTERNAL_SCHEMA_KEYS = Set.of(
            "x-enrichment", "x-review", "x-semantic", "x-zhuque-l1", "x-gateforge-l1");
    private static final Set<String> LOSSY_COMPOSITION_KEYS = Set.of(
            "oneOf", "anyOf", "allOf", "not", "if", "then", "else", "dependentSchemas");
    private static final Set<String> JSON_SCHEMA_TYPES = Set.of(
            "object", "array", "string", "number", "integer", "boolean", "null");

    private McpToolContract() {
    }

    static Map<String, Object> compile(String name, String description, Map<String, Object> inputSchema) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty()) {
            throw incompatible("MCP Tool.name 不能为空", "重新导入接口并为工具生成稳定名称");
        }
        String normalizedDescription = description == null ? "" : description.trim();
        if (normalizedDescription.isEmpty()) {
            throw incompatible("MCP Tool.description 不能为空", "先完成工具描述富化和人工复核，再冻结 Release");
        }
        if (inputSchema == null) {
            throw incompatible("MCP Tool.inputSchema 缺失", "重新导入 OpenAPI 并修复工具参数定义");
        }

        Map<String, Object> schema = copySchemaMap(inputSchema, "inputSchema");
        validateRoot(schema);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", normalizedName);
        tool.put("description", normalizedDescription);
        tool.put("inputSchema", schema);
        return tool;
    }

    private static void validateRoot(Map<String, Object> schema) {
        if (!"object".equals(schema.get("type"))) {
            throw incompatible("MCP 2025-06-18 要求 Tool.inputSchema.type 固定为 object",
                    "把接口参数包装成对象属性后重新导入；不要直接发布数组或标量根 schema");
        }
        validateSchema(schema, "inputSchema", true);
    }

    @SuppressWarnings("unchecked")
    private static void validateSchema(Map<String, Object> schema, String path, boolean root) {
        for (String key : LOSSY_COMPOSITION_KEYS) {
            if (schema.containsKey(key)) {
                throw incompatible(path + " 使用了 " + key + "，当前 Higress 2.2.3 无法无损转换",
                        "先把该参数显式建模为单一对象 schema，或升级运行面后再冻结 Release");
            }
        }
        if (schema.containsKey("nullable")) {
            throw incompatible(path + " 仍包含 OpenAPI nullable，不能当作标准 JSON Schema 发布",
                    "重新拉取并转换为标准 JSON Schema；当前运行面不支持 union type 时请拆成可选参数");
        }
        if (schema.containsKey("x-variant-of")) {
            throw incompatible(path + " 仍包含旧版 x-variant-of 降级标记",
                    "重新拉取 OpenAPI，让 oneOf/anyOf 保留标准语义后再处理运行面兼容性");
        }
        if (Boolean.TRUE.equals(schema.get("x-recursive"))) {
            throw incompatible(path + " 的递归 schema 在导入时被截断",
                    "展开或简化递归请求模型后重新导入，不能把截断 schema 发布给模型");
        }

        Object type = schema.get("type");
        if (type != null) {
            if (!(type instanceof String stringType) || !JSON_SCHEMA_TYPES.contains(stringType)) {
                throw incompatible(path + ".type 不是当前 MCP/Higress 可转换的单一 JSON Schema 类型",
                        "使用 object/array/string/number/integer/boolean/null 单一类型；不要使用类型数组");
            }
            if (root && !"object".equals(stringType)) {
                throw incompatible("MCP Tool.inputSchema 根类型必须是 object", "把所有参数放进 properties");
            }
        }

        Map<String, Object> properties = Map.of();
        Object propertiesValue = schema.get("properties");
        if (propertiesValue != null) {
            if (!(propertiesValue instanceof Map<?, ?> rawProperties)) {
                throw incompatible(path + ".properties 必须是 JSON object", "修复 OpenAPI schema 后重新导入");
            }
            properties = (Map<String, Object>) rawProperties;
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> child)) {
                    throw incompatible(path + ".properties." + entry.getKey() + " 必须是对象式 JSON Schema",
                            "当前 Higress 2.2.3 不支持 boolean schema；改为显式对象 schema");
                }
                validateSchema((Map<String, Object>) child, path + ".properties." + entry.getKey(), false);
            }
        }

        Object requiredValue = schema.get("required");
        if (requiredValue != null) {
            if (!(requiredValue instanceof List<?> requiredList)) {
                throw incompatible(path + ".required 必须是字符串数组", "修复 OpenAPI required 定义后重新导入");
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (Object item : requiredList) {
                if (!(item instanceof String required) || required.isBlank()) {
                    throw incompatible(path + ".required 只能包含非空字符串", "修复 OpenAPI required 定义后重新导入");
                }
                if (!unique.add(required)) {
                    throw incompatible(path + ".required 包含重复参数 " + required,
                            "去重 required 数组后重新导入");
                }
                if (!properties.containsKey(required)) {
                    throw incompatible(path + ".required 引用了不存在的属性 " + required,
                            "在 properties 中声明该参数，或从 required 中移除");
                }
            }
        }

        Object itemsValue = schema.get("items");
        if (itemsValue != null) {
            if (!(itemsValue instanceof Map<?, ?> items)) {
                throw incompatible(path + ".items 必须是对象式 JSON Schema",
                        "当前 Higress 2.2.3 不支持 tuple/boolean items；改为单一 items schema");
            }
            validateSchema((Map<String, Object>) items, path + ".items", false);
        }
    }

    private static Map<String, Object> copySchemaMap(Map<?, ?> source, String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (INTERNAL_SCHEMA_KEYS.contains(key)) {
                continue;
            }
            result.put(key, copySchemaValue(entry.getValue(), path + "." + key));
        }
        return result;
    }

    private static Object copySchemaValue(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            return copySchemaMap(map, path);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                copy.add(copySchemaValue(list.get(index), path + "[" + index + "]"));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw incompatible(path + " 包含不可序列化的 JSON Schema 值", "修复导入数据后重新冻结 Release");
    }

    private static ApiException incompatible(String what, String fix) {
        return ApiException.conflict("MCP Tool 契约不兼容：" + what, fix);
    }
}
