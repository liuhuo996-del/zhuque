package com.zhuque.common;

/** 数字员工线上标识的唯一生成/解析位置。 */
public final class AgentNames {

    private AgentNames() {}

    public static String serviceName(String departmentSlug, String agentSlug) {
        return "mcp-" + departmentSlug + "-" + agentSlug;
    }

    public static String mcpUrl(String publicBaseUrl, String departmentSlug, String agentSlug) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        String name = serviceName(departmentSlug, agentSlug);
        String path = name + "/sse";
        return base.isBlank() ? path : base + "/" + path;
    }

    public static String serviceNameFromUrl(String mcpUrl) {
        if (mcpUrl == null || mcpUrl.isBlank()) {
            throw new IllegalArgumentException("agent.mcp_url 为空");
        }
        String value = mcpUrl.trim();
        int suffix = firstSuffixIndex(value);
        if (suffix >= 0) {
            value = value.substring(0, suffix);
        }
        value = value.replaceAll("/+$", "");
        if (value.endsWith("/sse")) {
            value = value.substring(0, value.length() - "/sse".length()).replaceAll("/+$", "");
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static int firstSuffixIndex(String value) {
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query < 0) {
            return fragment;
        }
        if (fragment < 0) {
            return query;
        }
        return Math.min(query, fragment);
    }
}
