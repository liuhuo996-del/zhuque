package com.zhuque.m5_release;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zhuque.common.AgentNames;
import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;
import com.zhuque.m4_closure.ClosureChecker;
import com.zhuque.m4_closure.ClosureChecker.ClosureInput;
import com.zhuque.m4_closure.ClosureChecker.ToolNode;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.ToolRow;

/**
 * M5 · 冻结（draft → candidate）时的编译器。冻结做五件事，全部在一个事务里：
 *
 * 1. 序列化完整 manifest：pack 组成、每个 tool 的完整定义、参数映射模板、
 *    projection、意图列表、闭包检查结论
 * 2. manifest_hash = CanonicalJson.sha256(manifest)（字段顺序稳定是前提）
 * 3. 编译 Nacos MCP Registry 部署载荷（全量，不是 diff）。
 *    higress_auth_payload 仅作为历史 API/数据库兼容字段保留，新 Release 固定为空；
 *    Higress 的服务来源、路由与鉴权由运行平台独立维护。
 * 4. source_spec_hashes：编译自哪几份 spec 的哪一版
 * 5. target_constraints：最低 Nacos 版本
 *
 * 硬规则：candidate 之后 manifest 不可变。任何修改请求 → 报错并引导开新 Release。
 */
@Component
public class ManifestCompiler {

    private final ControlPlaneRepository repository;
    private final ClosureChecker closureChecker;

    @Value("${zhuque.nacos.min-version:3.0.1}")
    private String minNacosVersion;

    public ManifestCompiler(ControlPlaneRepository repository, ClosureChecker closureChecker) {
        this.repository = repository;
        this.closureChecker = closureChecker;
    }

    public record CompiledRelease(
            Map<String, Object> manifest,
            String manifestHash,
            Map<String, Object> nacosPayload,
            Map<String, Object> higressAuthPayload,
            Map<String, Object> sourceSpecHashes,
            Map<String, Object> targetConstraints) {
    }

    /**
     * 功能：编译入口。从 agent 当前的 pack/intent/闭包结论组装 manifest 并产出双 payload。
     * 注意：nacos_payload 里的 MCP service 名固定为 mcp-{dept}-{slug}（agent 上已固化），
     * Higress 通过其手工配置的 Nacos3 source 自动发现，不在此编译网关载荷。
     */
    public CompiledRelease compile(UUID agentId) {
        var agent = repository.requireAgent(agentId);
        var department = repository.requireDepartment(agent.departmentId());
        var intents = repository.intentsForAgent(agentId);
        var packs = repository.packsForAgent(agentId);
        List<ToolRow> trashedTools = repository.trashedToolsForAgent(agentId);
        if (!trashedTools.isEmpty()) {
            String toolNames = trashedTools.stream().map(ToolRow::name).limit(8)
                    .collect(java.util.stream.Collectors.joining("、"));
            if (trashedTools.size() > 8) {
                toolNames += " 等 " + trashedTools.size() + " 个工具";
            }
            throw ApiException.conflict("能力包仍引用已归档 REST API 的工具：" + toolNames,
                    "在工具池回收站恢复来源，或从能力包显式移除/替换这些工具后再新建 Release；"
                            + "已冻结的历史 Release 不会被改写");
        }
        List<ToolRow> deprecatedTools = repository.deprecatedToolsForAgent(agentId);
        if (!deprecatedTools.isEmpty()) {
            String toolNames = deprecatedTools.stream().map(ToolRow::name).limit(8)
                    .collect(java.util.stream.Collectors.joining("、"));
            if (deprecatedTools.size() > 8) {
                toolNames += " 等 " + deprecatedTools.size() + " 个工具";
            }
            throw ApiException.conflict("能力包仍引用已弃用的 REST endpoint 工具：" + toolNames,
                    "这些 endpoint 已从最新 OpenAPI 移除。请在能力包中显式移除/替换后再新建 Release；"
                            + "已冻结的历史 Release 不会被改写");
        }
        List<ToolRow> tools = repository.toolsForAgent(agentId);

        List<ToolNode> nodes = tools.stream().map(tool -> new ToolNode(tool.id(), tool.name(),
            required(tool.inputSchema()), tool.outputFields())).toList();
        var closure = closureChecker.check(new ClosureInput(nodes, Set.of(),
            Set.of("tenant_id", "current_user_id", "current_time")), repository.tools().stream()
            .map(tool -> new ToolNode(tool.id(), tool.name(), required(tool.inputSchema()), tool.outputFields()))
            .toList());

        List<Map<String, Object>> manifestTools = tools.stream().map(this::manifestTool).toList();
        List<Map<String, Object>> manifestPacks = packs.stream().map(pack -> {
            var projection = repository.projectionForPack(pack.id());
            return ordered("id", pack.id().toString(), "name", pack.name(), "scope", pack.scope(),
                "toolIds", repository.toolIdsForPack(pack.id()).stream().map(UUID::toString).toList(),
                "projection", ordered("id", projection.id().toString(), "name", projection.name(),
                    "visibilityCondition", projection.visibilityCondition()));
        }).toList();
        Map<String, Object> manifest = ordered(
            "schemaVersion", "zhuque.release/v1",
            "agent", ordered("id", agent.id().toString(), "name", agent.name(), "slug", agent.slug(),
                "description", agent.description(), "forbiddenNotes", agent.forbiddenNotes(),
                "mcpUrl", agent.mcpUrl()),
            "department", ordered("id", department.id().toString(), "name", department.name(),
                "slug", department.slug(), "consumerGroupRef", department.consumerGroupRef()),
            "intents", intents.stream().map(intent -> ordered("id", intent.id().toString(),
                "text", intent.text(), "orderNo", intent.orderNo(), "source", intent.source())).toList(),
            "packs", manifestPacks,
            "tools", manifestTools,
            "closure", ordered("conclusion", closure.conclusion(), "missing", closure.missing(),
                "unreachableTools", closure.unreachableTools(), "orphanTools", closure.orphanTools(),
                "fuzzyMatches", closure.fuzzyMatches()));

        String manifestHash = CanonicalJson.sha256(manifest);
        String serviceName = AgentNames.serviceNameFromUrl(agent.mcpUrl());
        Map<String, Object> nacosService = compileNacosMcpService(serviceName, agent.description(),
                manifestHash, manifestTools);
        Map<String, Object> nacosPayload = ordered("mcpName", serviceName, "service", nacosService);
        Map<String, Object> higressPayload = Map.of();

        Map<String, Object> sourceHashes = new LinkedHashMap<>();
        for (ToolRow tool : tools) {
            var source = repository.requireApiSource(tool.apiSourceId());
            sourceHashes.put(source.id().toString(), ordered("name", source.name(), "specHash", source.specHash()));
        }
        Map<String, Object> constraints = ordered(
                "nacosMinVersion", minNacosVersion,
                "mcpProtocolVersion", McpToolContract.PROTOCOL_VERSION,
                "mcpToolSchemaProfile", McpToolContract.SCHEMA_PROFILE);
        return new CompiledRelease(manifest, manifestHash, nacosPayload, higressPayload,
            sourceHashes, constraints);
        }

        /**
         * 编译 Nacos 3.x AI MCP Registry 的原生三段式载荷。
         *
         * Higress 的 Nacos3 MCP watcher 只发现 frontProtocol=mcp-sse/mcp-streamable
         * 的 Registry 资源；普通 Config Center JSON 不会生成 MCP 路由。REST 转 MCP
         * 又要求一个 MCP Server 只引用一个后端 endpoint，因此同一个数字员工的工具
         * 必须来自同一 REST origin。多 origin 时在冻结阶段显式拒绝，避免发布后把请求
         * 静默发往错误上游。
         */
        private Map<String, Object> compileNacosMcpService(String serviceName, String description,
                                                            String manifestHash,
                                                            List<Map<String, Object>> tools) {
        Endpoint endpoint = null;
        List<Map<String, Object>> toolDefinitions = new java.util.ArrayList<>();
        Map<String, Object> toolsMeta = new LinkedHashMap<>();
        Set<String> toolNames = new LinkedHashSet<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> sourceTemplate = map(tool.get("requestTemplate"));
            String absoluteUrl = String.valueOf(sourceTemplate.getOrDefault("url", ""));
            Endpoint current = endpoint(absoluteUrl);
            if (endpoint == null) {
                endpoint = current;
            } else if (!endpoint.sameOrigin(current)) {
                throw ApiException.conflict("一个 MCP Server 暂不支持跨多个 REST origin："
                                + endpoint.origin() + " 与 " + current.origin(),
                        "把不同上游拆成不同数字员工/Release，或先通过统一 API 网关收敛到同一 origin");
            }

            Map<String, Object> requestTemplate = new LinkedHashMap<>(sourceTemplate);
            requestTemplate.put("url", current.relativeUrl());
            Map<String, Object> argsPosition = mapOrEmpty(requestTemplate.remove("x-arg-locations"));
            // L1 fixture 只供 GateForge 测试器使用，不能进入线上执行模板。
            requestTemplate.remove("x-zhuque-l1");

            String toolName = text(tool.get("name"));
            Map<String, Object> mcpTool = McpToolContract.compile(
                    toolName,
                    text(tool.get("description")),
                    inputSchema(tool.get("inputSchema")));
            String publishedName = String.valueOf(mcpTool.get("name"));
            if (!toolNames.add(publishedName)) {
                throw ApiException.conflict("同一 MCP Server 出现重复 Tool.name：" + publishedName,
                        "保证能力包中的工具名称唯一后重新冻结 Release");
            }
            toolDefinitions.add(mcpTool);
            toolsMeta.put(publishedName, ordered("enabled", true,
                    "templates", ordered("json-go-template", ordered(
                            "requestTemplate", requestTemplate,
                            "argsPosition", argsPosition,
                            "responseTemplate", Map.of()))));
        }
        if (endpoint == null) {
            throw ApiException.conflict("Release 没有可发布的工具", "先给数字员工挂载至少一个已审核能力包工具");
        }

        String digest = manifestHash.startsWith("sha256:") ? manifestHash.substring("sha256:".length())
                : manifestHash;
        String version = "1.0.0-" + digest.substring(0, Math.min(12, digest.length()));
        Map<String, Object> serverSpecification = ordered(
                "name", serviceName,
                "protocol", endpoint.protocol(),
                "frontProtocol", "mcp-sse",
                "description", description == null ? "" : description,
                "versionDetail", ordered("version", version),
                "remoteServerConfig", ordered("exportPath", ""),
                "capabilities", List.of("TOOL"),
                "enabled", true);
        Map<String, Object> toolSpecification = ordered("tools", toolDefinitions, "toolsMeta", toolsMeta);
        Map<String, Object> endpointSpecification = ordered("type", "DIRECT", "data", ordered(
                "address", endpoint.address(), "port", String.valueOf(endpoint.port())));
        return ordered("serverSpecification", serverSpecification,
                "toolSpecification", toolSpecification,
                "endpointSpecification", endpointSpecification);
        }

        private Map<String, Object> manifestTool(ToolRow tool) {
        return ordered("id", tool.id().toString(), "name", tool.name(), "description", tool.description(),
            "inputSchema", tool.inputSchema(), "requestTemplate", tool.requestTemplate(),
            "method", tool.method(), "path", tool.path(), "effect", tool.effect(),
            "enrichmentStatus", tool.enrichmentStatus(), "outputFields", tool.outputFields(),
            "sensitivityFlags", tool.sensitivityFlags(), "tokenCost", tool.tokenCost(),
            "apiSourceId", tool.apiSourceId().toString());
        }

        @SuppressWarnings("unchecked")
        private static List<String> required(Map<String, Object> schema) {
        Object value = schema.get("required");
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        }

        private static Endpoint endpoint(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw ApiException.conflict("requestTemplate.url 不是绝对 HTTP(S) 地址：" + url,
                    "在 OpenAPI servers 或导入 baseUrl 中配置可被 Higress 访问的绝对地址");
        }
        int schemeEnd = url.indexOf("://") + 3;
        int pathStart = firstDelimiter(url, schemeEnd);
        String origin = pathStart < 0 ? url : url.substring(0, pathStart);
        String relative = pathStart < 0 ? "/" : url.substring(pathStart);
        if (relative.startsWith("?")) {
            relative = "/" + relative;
        }
        if (relative.indexOf('#') >= 0) {
            throw ApiException.conflict("REST 请求地址不能包含 fragment：" + url,
                    "从 OpenAPI servers/path 中移除 #fragment");
        }
        try {
            URI parsed = URI.create(origin);
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
            String host = parsed.getHost();
            if (host == null || host.isBlank() || !("http".equals(scheme) || "https".equals(scheme))) {
                throw new IllegalArgumentException("origin 不合法");
            }
            int port = parsed.getPort() > 0 ? parsed.getPort() : ("https".equals(scheme) ? 443 : 80);
            String normalizedOrigin = scheme + "://" + host + (parsed.getPort() > 0 ? ":" + port : "");
            return new Endpoint(scheme, host, port, normalizedOrigin, relative);
        } catch (RuntimeException error) {
            throw ApiException.conflict("无法解析 REST origin：" + origin,
                    "检查 OpenAPI servers 地址，只使用 http(s)://host[:port]");
        }
        }

        private static int firstDelimiter(String value, int from) {
        int result = -1;
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = value.indexOf(delimiter, from);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw ApiException.conflict("工具缺少 requestTemplate", "重新导入 OpenAPI 并完成工具审核");
        }
        return (Map<String, Object>) map;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> inputSchema(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw ApiException.conflict("工具缺少 MCP inputSchema", "重新导入 OpenAPI 并完成工具审核");
        }
        return (Map<String, Object>) map;
        }

        private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
        }

        private record Endpoint(String protocol, String address, int port, String origin, String relativeUrl) {
        boolean sameOrigin(Endpoint other) {
            return other != null && protocol.equals(other.protocol)
                    && address.equalsIgnoreCase(other.address) && port == other.port;
        }
        }

        private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }
}
