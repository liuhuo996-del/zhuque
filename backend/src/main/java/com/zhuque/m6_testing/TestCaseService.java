package com.zhuque.m6_testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * M6 · 用例管理。用例来源三条路：
 * 1. AI 从 OpenAPI 生成草稿（generateDrafts）
 * 2. 人工补关键 case
 * 3. （有日志的客户）从真实调用日志回放导入——v1 留接口
 *
 * 用例本身要版本化，并绑定 tool 版本：tool 的 schema 变了，
 * 绑定旧版的用例要标记"待更新"而不是悄悄失效。
 */
@Service
public class TestCaseService {

    private final com.zhuque.persistence.ControlPlaneRepository repository;
    private final Map<UUID, List<CaseDef>> drafts = new ConcurrentHashMap<>();

    public TestCaseService(com.zhuque.persistence.ControlPlaneRepository repository) {
        this.repository = repository;
    }

    public record CaseDef(String caseId, String layer, UUID toolId, String toolVersion,
                          String input, String golden, String origin) {} // origin: ai|human|replay

    /** 功能：AI 生成用例草稿（L1 参数边界 + L2 自然语言任务与 golden tool）。 */
    public List<CaseDef> generateDrafts(UUID releaseId) {
        var release = repository.requireRelease(releaseId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = release.manifest().get("tools") instanceof List<?> list
            ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
            : List.of();
        List<CaseDef> generated = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            UUID toolId = UUID.fromString(String.valueOf(tool.get("id")));
            String name = String.valueOf(tool.get("name"));
            String version = com.zhuque.common.CanonicalJson.sha256(tool);
            generated.add(new CaseDef("L1-boundary-" + name, "L1", toolId, version,
                "空串、超长、特殊字符、类型错误", "结构化 4xx 错误", "ai"));
            generated.add(new CaseDef("L2-select-" + name, "L2", toolId, version,
                "请完成：" + tool.getOrDefault("description", name), name, "ai"));
        }
        drafts.put(releaseId, List.copyOf(generated));
        return List.copyOf(generated);
    }

    /** 功能：tool schema 变更时，把绑定旧版本的用例标记为"待更新"。M1 的 SpecSyncService 在 applyDiff 后调用。 */
    public void markStaleCases(UUID toolId, String newToolVersion) {
        drafts.replaceAll((releaseId, cases) -> cases.stream().map(item -> item.toolId().equals(toolId)
                && !item.toolVersion().equals(newToolVersion)
                ? new CaseDef(item.caseId() + "-待更新", item.layer(), item.toolId(), item.toolVersion(),
                        item.input(), item.golden(), item.origin()) : item).toList());
    }
}
