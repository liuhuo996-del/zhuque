package com.zhuque.m5_release;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * M5 · 版本号建议。语义写死（不许项目内各自发挥）：
 *
 *   major：删工具 / 删必填参数 / 改工具名 / 改参数语义
 *   minor：加工具 / 加可选参数
 *   patch：改描述 / 改错误信息
 *
 * 冻结时自动与上一版 manifest 比对给出建议版本号，人可覆盖
 * （覆盖要记录：建议是什么、人改成了什么）。
 */
@Component
public class VersionSuggester {

    public record VersionSuggestion(String suggested, String bumpLevel, java.util.List<String> reasons) {}

    /**
     * 功能：diff 两版 manifest，按上表判级并给出建议版本号。
     * 首个版本固定 v1。reasons 逐条列出触发判级的变更
     * （如 "删除了工具 cancel_order → major"）。
     */
    public VersionSuggestion suggest(Map<String, Object> previousManifest, Map<String, Object> nextManifest,
                                     String previousVersion) {
        if (previousManifest == null || previousManifest.isEmpty() || previousVersion == null
                || previousVersion.isBlank()) {
            return new VersionSuggestion("v1", "major", List.of("首个版本固定为 v1"));
        }
        Map<String, Map<String, Object>> previousTools = tools(previousManifest);
        Map<String, Map<String, Object>> nextTools = tools(nextManifest);
        List<String> major = new ArrayList<>();
        List<String> minor = new ArrayList<>();
        List<String> patch = new ArrayList<>();

        for (String name : previousTools.keySet()) {
            if (!nextTools.containsKey(name)) {
                major.add("删除了工具 " + name + " → major");
            }
        }
        for (String name : nextTools.keySet()) {
            if (!previousTools.containsKey(name)) {
                minor.add("增加了工具 " + name + " → minor");
            }
        }
        for (String name : previousTools.keySet()) {
            if (!nextTools.containsKey(name)) {
                continue;
            }
            Map<String, Object> before = previousTools.get(name);
            Map<String, Object> after = nextTools.get(name);
            Set<String> beforeRequired = required(before);
            Set<String> afterRequired = required(after);
            for (String field : beforeRequired) {
                if (!afterRequired.contains(field)) {
                    major.add("删除了工具 " + name + " 的必填参数 " + field + " → major");
                }
            }
            for (String field : afterRequired) {
                if (!beforeRequired.contains(field)) {
                    major.add("增加了工具 " + name + " 的必填参数 " + field + " → major");
                }
            }
            Set<String> beforeProperties = properties(before);
            Set<String> afterProperties = properties(after);
            for (String field : afterProperties) {
                if (!beforeProperties.contains(field) && !afterRequired.contains(field)) {
                    minor.add("增加了工具 " + name + " 的可选参数 " + field + " → minor");
                }
            }
            Map<String, Object> beforeSchemas = propertySchemas(before);
            Map<String, Object> afterSchemas = propertySchemas(after);
            for (String field : beforeSchemas.keySet()) {
                if (afterSchemas.containsKey(field) && !Objects.equals(beforeSchemas.get(field), afterSchemas.get(field))) {
                    major.add("修改了工具 " + name + " 的参数语义 " + field + " → major");
                }
            }
            if (!Objects.equals(before.get("description"), after.get("description"))) {
                patch.add("修改了工具 " + name + " 的描述 → patch");
            }
        }
        String level = !major.isEmpty() ? "major" : !minor.isEmpty() ? "minor" : "patch";
        List<String> reasons = !major.isEmpty() ? major : !minor.isEmpty() ? minor : patch;
        if (reasons.isEmpty()) {
            reasons = List.of("部署内容无功能变化 → patch");
        }
        return new VersionSuggestion(increment(previousVersion, level), level, List.copyOf(reasons));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> tools(Map<String, Object> manifest) {
        Object value = manifest.get("tools");
        if (!(value instanceof List<?> list)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> tool = (Map<String, Object>) raw;
                String name = String.valueOf(tool.getOrDefault("name", tool.get("id")));
                result.put(name, tool);
            }
        }
        return result;
    }

    private static Set<String> required(Map<String, Object> tool) {
        Object schema = tool.get("inputSchema");
        if (!(schema instanceof Map<?, ?> map) || !(map.get("required") instanceof List<?> list)) {
            return Set.of();
        }
        return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> properties(Map<String, Object> tool) {
        return propertySchemas(tool).keySet();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertySchemas(Map<String, Object> tool) {
        Object schema = tool.get("inputSchema");
        if (!(schema instanceof Map<?, ?> map) || !(map.get("properties") instanceof Map<?, ?> properties)) {
            return Map.of();
        }
        return (Map<String, Object>) properties;
    }

    private static String increment(String previousVersion, String level) {
        String[] parts = previousVersion.replaceFirst("^[vV]", "").split("\\.");
        int major = number(parts, 0);
        int minor = number(parts, 1);
        int patch = number(parts, 2);
        switch (level) {
            case "major" -> { major++; minor = 0; patch = 0; }
            case "minor" -> { minor++; patch = 0; }
            default -> patch++;
        }
        return "v" + major + "." + minor + "." + patch;
    }

    private static int number(String[] values, int index) {
        if (index >= values.length) {
            return 0;
        }
        try {
            return Integer.parseInt(values[index]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
