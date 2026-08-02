package com.zhuque.m1_toolpool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zhuque.common.CanonicalJson;

/**
 * M1-步骤5 · 静态标注（不调模型，纯规则，入库前跑一遍即可）。
 */
@Component
public class StaticAnnotator {

    @Value("${zhuque.security.sensitive-words:phone,mobile,tel,id_number,identity,身份证,amount,money,金额,bank,card,token,secret,password,address,email,地址}")
    private String configuredWords;

    /**
     * 功能：计算 token_cost = 该 tool（name + description + inputSchema）
     * 序列化后的 token 数。用于 M4/M7 的包预算。
     * 实现提示：不必精确到某个 tokenizer，选一种估算方式（如 cl100k 近似
     * 或 字节数/4）并全局一致——预算是相对量，一致性比精确性重要。
     */
    public int tokenCost(String name, String description, Map<String, Object> inputSchema) {
        String serialized = safe(name) + "\n" + safe(description) + "\n"
            + CanonicalJson.canonicalize(inputSchema == null ? Map.of() : inputSchema);
        int bytes = serialized.getBytes(StandardCharsets.UTF_8).length;
        // 中英文混合内容用 bytes/4 作为稳定近似；至少计一个 token。
        return Math.max(1, (int) Math.ceil(bytes / 4.0));
    }

    /**
     * 功能：敏感字段标注。扫描 inputSchema 属性名 + outputFields 路径，
     * 与敏感词典匹配（手机/phone、身份证/id_number、金额/amount、
     * token/secret/key、地址/address 等，词典要可配置可扩展）。
     * 返回命中的字段路径列表，落 tool.sensitivity_flags。
     * v1 只到标注为止：不做风险评分体系、不做 owner 归属治理。
     */
    public List<String> sensitivityFlags(Map<String, Object> inputSchema, List<String> outputFields) {
        Set<String> candidates = new LinkedHashSet<>();
        collectProperties(inputSchema == null ? Map.of() : inputSchema, "", candidates, 0);
        if (outputFields != null) {
            candidates.addAll(outputFields);
        }
        Set<String> words = sensitiveWords();
        return candidates.stream().filter(field -> matchesAny(field, words)).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private void collectProperties(Map<String, Object> schema, String prefix, Set<String> result, int depth) {
        if (depth > 10) {
            return;
        }
        Object propertiesValue = schema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            return;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String path = prefix.isBlank() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            result.add(path);
            if (entry.getValue() instanceof Map<?, ?> child) {
                collectProperties((Map<String, Object>) child, path, result, depth + 1);
            }
        }
    }

    private Set<String> sensitiveWords() {
        String source = configuredWords == null || configuredWords.isBlank()
                ? "phone,mobile,tel,id_number,identity,身份证,amount,money,金额,bank,card,token,secret,password,address,email,地址"
                : configuredWords;
        Set<String> words = new LinkedHashSet<>();
        Arrays.stream(source.split(",")).map(String::trim).filter(word -> !word.isEmpty())
                .map(String::toLowerCase).forEach(words::add);
        return words;
    }

    private static boolean matchesAny(String field, Set<String> words) {
        String normalized = field.toLowerCase().replace('-', '_');
        List<String> tokens = new ArrayList<>(List.of(normalized.split("[^a-z0-9_\\u4e00-\\u9fff]+")));
        tokens.add(normalized);
        for (String word : words) {
            if (tokens.stream().anyMatch(token -> token.equals(word) || token.contains("_" + word)
                    || token.contains(word + "_") || (containsCjk(word) && token.contains(word)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(code -> code >= 0x4e00 && code <= 0x9fff);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
