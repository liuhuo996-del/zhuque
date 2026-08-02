package com.zhuque.common;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON 规范化 + 哈希。M5 冻结、M8 幂等比对、M9 配置漂移比对共用同一套实现——
 * 三处必须用同一个规范化算法，否则 hash 对不上会产生假漂移。
 */
public final class CanonicalJson {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalJson() {}

    /**
     * 功能：把任意 JSON 结构规范化成确定性的字符串。
     * 规则：
     * - object 的 key 按字典序排序（递归）
     * - 去掉所有格式空白
     * - 数字统一表示（1.0 与 1 视为同值的策略要定死并写测试）
     * - null 字段保留还是剔除，选一种并全局一致（建议剔除）
     * 这是 manifest_hash 稳定性的根基：字段顺序不稳，审批绑定 hash 的机制就废了。
     */
    public static String canonicalize(Object jsonValue) {
        try {
            JsonNode source = toNode(jsonValue);
            return JSON.writeValueAsString(normalize(source));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("无法规范化 JSON：" + error.getOriginalMessage(), error);
        }
    }

    /**
     * 功能：canonicalize 后取 SHA-256，返回 "sha256:xxxx" 格式。
     * manifest_hash / payload_hash / spec_hash 全走这里。
     */
    public static String sha256(Object jsonValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalize(jsonValue).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", impossible);
        }
    }

    private static JsonNode toNode(Object value) throws JsonProcessingException {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return JSON.readTree(trimmed);
            }
        }
        return JSON.valueToTree(value);
    }

    private static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                // null object fields are deliberately omitted globally. Null array entries are preserved.
                if (field.getValue() != null && !field.getValue().isNull()) {
                    sorted.put(field.getKey(), normalize(field.getValue()));
                }
            }
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (node.isNumber()) {
            BigDecimal normalized = node.decimalValue().stripTrailingZeros();
            if (normalized.scale() <= 0) {
                return BigIntegerNode.valueOf(normalized.toBigIntegerExact());
            }
            return DecimalNode.valueOf(normalized);
        }
        return node.deepCopy();
    }
}
