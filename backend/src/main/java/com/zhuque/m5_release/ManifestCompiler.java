package com.zhuque.m5_release;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zhuque.common.CanonicalJson;
import com.zhuque.m4_closure.ClosureChecker;
import com.zhuque.m4_closure.ClosureChecker.ClosureInput;
import com.zhuque.m4_closure.ClosureChecker.ToolNode;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.AgentKeyRow;
import com.zhuque.persistence.ControlPlaneRepository.ToolRow;

/**
 * M5 · 冻结（draft → candidate）时的编译器。冻结做五件事，全部在一个事务里：
 *
 * 1. 序列化完整 manifest：pack 组成、每个 tool 的完整定义、参数映射模板、
 *    projection、意图列表、闭包检查结论
 * 2. manifest_hash = CanonicalJson.sha256(manifest)（字段顺序稳定是前提）
 * 3. 编译两份部署载荷（全量，不是 diff）：
 *    - nacos_payload：Nacos MCP service 定义
 *    - higress_auth_payload：consumer group / key 策略 / 路由鉴权
 * 4. source_spec_hashes：编译自哪几份 spec 的哪一版
 * 5. target_constraints：最低 Nacos / Higress 版本 + Redis 依赖
 *
 * 硬规则：candidate 之后 manifest 不可变。任何修改请求 → 报错并引导开新 Release。
 */
@Component
public class ManifestCompiler {

    private final ControlPlaneRepository repository;
    private final ClosureChecker closureChecker;

    @Value("${zhuque.nacos.min-version:3.0.1}")
    private String minNacosVersion;
    @Value("${zhuque.higress.min-version:2.2.0}")
    private String minHigressVersion;

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
     * higress_auth_payload 引用 key_ref 而非明文。
     */
    public CompiledRelease compile(UUID agentId) {
        var agent = repository.requireAgent(agentId);
        var department = repository.requireDepartment(agent.departmentId());
        var intents = repository.intentsForAgent(agentId);
        var packs = repository.packsForAgent(agentId);
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

        String serviceName = serviceName(agent.mcpUrl());
        Map<String, Object> serviceDefinition = ordered("name", serviceName, "description", agent.description(),
            "type", "mcp", "tools", manifestTools);
        Map<String, Object> nacosPayload = ordered("dataId", serviceName + ".json", "group", "mcp-server",
            "service", serviceDefinition);
        List<String> keyRefs = repository.agentKeys(agentId, true).stream().map(AgentKeyRow::keyRef).toList();
        String consumerName = "agent-" + department.slug() + "-" + agent.slug();
        Map<String, Object> higressPayload = ordered("mcpServerName", serviceName,
            "consumers", List.of(consumerName), "credentials", List.of(ordered("name", consumerName,
                "type", "key-auth", "values", keyRefs)), "consumerGroupRef", department.consumerGroupRef());

        Map<String, Object> sourceHashes = new LinkedHashMap<>();
        for (ToolRow tool : tools) {
            var source = repository.requireApiSource(tool.apiSourceId());
            sourceHashes.put(source.id().toString(), ordered("name", source.name(), "specHash", source.specHash()));
        }
        Map<String, Object> constraints = ordered("nacosMinVersion", minNacosVersion,
            "higressMinVersion", minHigressVersion, "redisRequired", true, "mcpServerEnabled", true);
        return new CompiledRelease(manifest, CanonicalJson.sha256(manifest), nacosPayload, higressPayload,
            sourceHashes, constraints);
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

        private static String serviceName(String mcpUrl) {
        int slash = mcpUrl.lastIndexOf('/');
        return slash < 0 ? mcpUrl : mcpUrl.substring(slash + 1);
        }

        private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }
}
