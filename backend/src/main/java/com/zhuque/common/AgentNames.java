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
        return base.isBlank() ? name : base + "/" + name;
    }

    public static String serviceNameFromUrl(String mcpUrl) {
        if (mcpUrl == null || mcpUrl.isBlank()) {
            throw new IllegalArgumentException("agent.mcp_url 为空");
        }
        String value = mcpUrl.replaceAll("/+$", "");
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }
}
