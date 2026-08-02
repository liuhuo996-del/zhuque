package com.zhuque.m1_toolpool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * M1-步骤3 · 从 response schema 递归抽取字段路径列表。
 *
 * 输出形如：orders[].id、data.order.status、customer.phone
 *
 * ！！这是 M4 闭包检查的唯一数据来源，必须做全：
 * - 数组用 [] 标记层级
 * - 展开嵌套 object 到叶子
 * - oneOf 并集的字段全部保留（宁多勿漏——闭包检查漏一个字段就是假 BLOCKED）
 * - 设一个深度上限（如 6 层）防循环引用，触达上限记 warning
 */
@Component
public class OutputFieldExtractor {

    /**
     * 功能：递归遍历 schema 树，产出叶子字段的完整路径列表。
     * 落库到 tool.output_fields（jsonb 数组）。
     */
    public List<String> extract(Map<String, Object> responseSchema) {
        if (responseSchema == null || responseSchema.isEmpty()) {
            return List.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        walk(responseSchema, "", 0, fields);
        return List.copyOf(fields);
    }

    @SuppressWarnings("unchecked")
    private void walk(Map<String, Object> schema, String path, int depth, Set<String> fields) {
        if (depth >= 6) {
            if (!path.isBlank()) {
                fields.add(path + ".*");
            }
            return;
        }

        Object propertiesValue = schema.get("properties");
        if (propertiesValue instanceof Map<?, ?> properties && !properties.isEmpty()) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                String childPath = path.isBlank() ? String.valueOf(entry.getKey())
                        : path + "." + entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> child) {
                    walk((Map<String, Object>) child, childPath, depth + 1, fields);
                } else {
                    fields.add(childPath);
                }
            }
            return;
        }

        Object itemsValue = schema.get("items");
        if (itemsValue instanceof Map<?, ?> items) {
            String arrayPath = path.isBlank() ? "[]" : path + "[]";
            walk((Map<String, Object>) items, arrayPath, depth + 1, fields);
            return;
        }

        boolean traversedVariant = false;
        for (String key : List.of("oneOf", "anyOf", "allOf")) {
            Object variantsValue = schema.get(key);
            if (!(variantsValue instanceof List<?> variants)) {
                continue;
            }
            for (Object variant : variants) {
                if (variant instanceof Map<?, ?> child) {
                    walk((Map<String, Object>) child, path, depth + 1, fields);
                    traversedVariant = true;
                }
            }
        }
        if (!traversedVariant && !path.isBlank()) {
            fields.add(path);
        }
    }
}
