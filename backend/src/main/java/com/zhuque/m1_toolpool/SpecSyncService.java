package com.zhuque.m1_toolpool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;
import com.zhuque.m1_toolpool.ToolDraftGenerator.ToolDraft;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.ApiSourceRow;
import com.zhuque.persistence.ControlPlaneRepository.ToolRow;

/**
 * M1 · spec 的拉取与「条目级 diff」再同步。
 *
 * 核心纪律：重新拉取 spec 时做条目级 diff，绝不全表重建——
 * 全表重建会丢掉人工审核状态（reviewed）和富化结果，这是不可接受的。
 */
@Service
public class SpecSyncService {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ControlPlaneRepository repository;
    private final OpenApiParser parser;
    private final ToolDraftGenerator draftGenerator;
    private final StaticAnnotator annotator;
    private final ConcurrentMap<UUID, PendingSync> pending = new ConcurrentHashMap<>();

    public SpecSyncService(ControlPlaneRepository repository, OpenApiParser parser,
                           ToolDraftGenerator draftGenerator, StaticAnnotator annotator) {
        this.repository = repository;
        this.parser = parser;
        this.draftGenerator = draftGenerator;
        this.annotator = annotator;
    }

    /** 条目级 diff 结果（同时也是 M9 spec 漂移的 detail 数据结构） */
    public record SpecDiff(
            List<String> addedEndpoints,          // 新增：生成新 tool 草稿（raw）
            List<UUID> removedToolIds,            // 删除：标记而非物理删（可能被 pack 引用）
            List<UUID> changedToolIds,            // 修改：更新 schema，enrichment 降回 enriched 待复核
            boolean breaking) {                   // 删 endpoint/删必填参数/改类型/改必填性 = breaking
    }

    /**
     * 功能：拉取 spec_url，算 hash。hash 未变直接返回 null（无事发生）。
     * 拉取失败（404/超时/非 JSON）时抛带指引的异常：
     * 「拉取 spec 失败：GET ... 返回 404。检查 spec_url 是否变更，或改为手动上传。」
     */
    public SpecDiff refetch(UUID apiSourceId) {
        ApiSourceRow source = repository.requireApiSource(apiSourceId);
        if (repository.isTrashed("api_source", apiSourceId)) {
            throw ApiException.conflict("REST API 已在回收站中，不能重新拉取或修改", "先在回收站恢复来源；恢复后再重新拉取并人工确认变更");
        }
        if (source.specUrl() == null || source.specUrl().isBlank()) {
            throw ApiException.badRequest("API 来源没有 spec_url", "改为重新上传 OpenAPI 原文，或先补全 spec_url");
        }
        String specText = fetch(source.specUrl());
        String hash = CanonicalJson.sha256(specText);
        if (hash.equals(source.specHash())) {
            pending.remove(apiSourceId);
            return null;
        }

        OpenApiParser.ParseResult parsed;
        try {
            // 重新拉取不能再把 spec URL 的 origin 当作导入时的 baseUrl：初次导入可能
            // 使用了完全不同的测试环境。api_source 的冻结字段没有保存该 baseUrl，宁可
            // 拒绝不自包含的 spec，也不能静默改写已经审核过的请求模板。
            parsed = parser.parse(specText);
            requireSelfContainedServers(parsed);
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw ApiException.badRequest("拉取到的 OpenAPI 无法解析：" + error.getMessage(),
                    "检查 spec_url 是否仍返回 OpenAPI 3.x JSON/YAML，或改为手动上传");
        }
        String sourceSlug = slug(source.name());
        List<ToolDraft> drafts = draftGenerator.generateAll(sourceSlug, parsed.endpoints());
        Map<String, ToolDraft> nextByEndpoint = new LinkedHashMap<>();
        for (ToolDraft draft : drafts) {
            nextByEndpoint.put(endpointKey(draft.method(), draft.path()), draft);
        }
        List<ToolRow> current = repository.toolsBySource(apiSourceId);
        Map<String, ToolRow> currentByEndpoint = new LinkedHashMap<>();
        current.forEach(tool -> currentByEndpoint.put(endpointKey(tool.method(), tool.path()), tool));

        List<String> added = new ArrayList<>();
        List<UUID> removed = new ArrayList<>();
        List<UUID> changed = new ArrayList<>();
        boolean breaking = false;
        for (Map.Entry<String, ToolDraft> entry : nextByEndpoint.entrySet()) {
            ToolRow old = currentByEndpoint.get(entry.getKey());
            if (old == null) {
                added.add(entry.getKey());
            } else {
                boolean definitionChanged = definitionChanged(old, entry.getValue());
                if (old.deprecatedAt() == null && !definitionChanged) {
                    continue;
                }
                // 上游重新提供此前移除的 endpoint 时复用原 tool id，保留其历史关联；
                // updateToolDefinition 会清除弃用标记，并让 reviewed 回到 enriched 待复核。
                changed.add(old.id());
                if (definitionChanged) {
                    breaking |= breakingChange(old.inputSchema(), entry.getValue().inputSchema());
                }
            }
        }
        for (Map.Entry<String, ToolRow> entry : currentByEndpoint.entrySet()) {
            if (!nextByEndpoint.containsKey(entry.getKey()) && entry.getValue().deprecatedAt() == null) {
                removed.add(entry.getValue().id());
                breaking = true;
            }
        }
        SpecDiff diff = new SpecDiff(List.copyOf(added), List.copyOf(removed), List.copyOf(changed), breaking);
        pending.put(apiSourceId, new PendingSync(hash, nextByEndpoint, diff, parsed.errors()));
        return diff;
    }

    /**
     * 功能：把 diff 应用到 tool 表。
     * - added → ToolDraftGenerator 走全流程生成草稿
     * - removed → 打删除标记；若被 pack 引用，同时生成 drift_event 提醒（交给 M9 落库）
     * - changed → 只更新 input_schema/output_fields/request_template，
     *   保留 description 等富化字段，enrichment_status: reviewed → enriched（需重新复核）
     */
    @Transactional
    public void applyDiff(UUID apiSourceId, SpecDiff diff) {
        if (diff == null) {
            return;
        }
        if (repository.isTrashed("api_source", apiSourceId)) {
            throw ApiException.conflict("REST API 已在回收站中，不能应用 spec 变更", "先在回收站恢复来源；恢复后重新拉取最新 spec");
        }
        PendingSync sync = pending.get(apiSourceId);
        if (sync == null || !sync.diff().equals(diff)) {
            throw ApiException.conflict("spec diff 已过期或不是本次拉取结果", "重新拉取 spec 后立即应用最新 diff");
        }
        ApiSourceRow source = repository.requireApiSource(apiSourceId);
        Map<UUID, ToolRow> oldById = new LinkedHashMap<>();
        repository.toolsBySource(apiSourceId).forEach(tool -> oldById.put(tool.id(), tool));

        for (String endpoint : diff.addedEndpoints()) {
            ToolDraft draft = sync.draftsByEndpoint().get(endpoint);
            if (draft != null) {
                insertDraft(apiSourceId, uniqueName(draft.name()), draft);
            }
        }
        for (UUID toolId : diff.changedToolIds()) {
            ToolRow old = oldById.get(toolId);
            if (old == null) {
                continue;
            }
            ToolDraft draft = sync.draftsByEndpoint().get(endpointKey(old.method(), old.path()));
            if (draft == null) {
                continue;
            }
            List<String> sensitivity = annotator.sensitivityFlags(draft.inputSchema(), draft.outputFields());
            int tokenCost = annotator.tokenCost(old.name(), old.description(), draft.inputSchema());
            repository.updateToolDefinition(toolId, draft.inputSchema(), draft.requestTemplate(),
                    draft.outputFields(), sensitivity, tokenCost);
        }
        for (UUID toolId : diff.removedToolIds()) {
            ToolRow removed = oldById.get(toolId);
            if (removed == null) {
                continue;
            }
            markRemovedEndpoint(apiSourceId, removed);
        }
        repository.updateApiSourceHash(apiSourceId, sync.specHash());
        pending.remove(apiSourceId);
    }

    /**
     * OpenAPI 移除了 endpoint 时只弃用 tool，不删除它。这样冻结 Release 的 manifest
     * 仍可追溯到原始工具定义，同时 tools()/toolsByIds() 不会再把它交给新的配置。
     * 包可见以便回归测试验证这条数据保留边界。
     */
    void markRemovedEndpoint(UUID apiSourceId, ToolRow removed) {
        int references = repository.packReferenceCount(removed.id());
        String reason = "endpoint 已从最新 OpenAPI 移除：" + removed.method() + " " + removed.path();
        repository.deprecateTool(removed.id(), reason);
        repository.insertDriftEvent("api_source", apiSourceId, "spec", Map.of(
                "change", "endpoint_removed",
                "toolId", removed.id().toString(),
                "toolName", removed.name(),
                "packReferences", references,
                "toolDeprecated", true,
                "action", references > 0
                        ? "工具已弃用但仍被能力包引用；请人工迁移后再创建新的 Release"
                        : "工具已弃用并从普通工具池隐藏；历史 Release 快照不受影响"));
    }

    public List<OpenApiParser.ParseError> pendingParseErrors(UUID apiSourceId) {
        PendingSync sync = pending.get(apiSourceId);
        return sync == null ? List.of() : sync.parseErrors();
    }

    private String fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw ApiException.unavailable("拉取 spec 失败：GET " + url + " 返回 " + response.statusCode(),
                        "检查 spec_url 是否变更，或改为手动上传");
            }
            if (response.body() == null || response.body().isBlank()) {
                throw ApiException.unavailable("拉取 spec 失败：响应为空", "检查上游 OpenAPI 地址或改为手动上传");
            }
            return response.body();
        } catch (ApiException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("拉取 spec 被中断", "稍后重试");
        } catch (Exception error) {
            throw ApiException.unavailable("拉取 spec 失败：" + error.getMessage(),
                    "检查网络、证书和 spec_url，或改为手动上传");
        }
    }

    private void insertDraft(UUID sourceId, String name, ToolDraft draft) {
        List<String> sensitivity = annotator.sensitivityFlags(draft.inputSchema(), draft.outputFields());
        int tokenCost = annotator.tokenCost(name, draft.description(), draft.inputSchema());
        repository.insertTool(sourceId, name, draft.description(), draft.inputSchema(), draft.requestTemplate(),
                draft.method(), draft.path(), "unknown", "raw", draft.outputFields(), sensitivity, tokenCost);
    }

    private String uniqueName(String requested) {
        if (!repository.toolNameExists(requested)) {
            return requested;
        }
        for (int suffix = 2; suffix < 10_000; suffix++) {
            String tail = "_" + suffix;
            String base = requested.substring(0, Math.min(requested.length(), 80 - tail.length()));
            String candidate = base + tail;
            if (!repository.toolNameExists(candidate)) {
                return candidate;
            }
        }
        throw ApiException.conflict("无法为工具生成唯一名称", "调整 API 来源名称或 operationId 后重新导入");
    }

    private static boolean definitionChanged(ToolRow old, ToolDraft next) {
        return !CanonicalJson.sha256(Map.of(
                "input", old.inputSchema(), "request", old.requestTemplate(), "output", old.outputFields()))
                .equals(CanonicalJson.sha256(Map.of(
                        "input", next.inputSchema(), "request", next.requestTemplate(), "output", next.outputFields())));
    }

    @SuppressWarnings("unchecked")
    private static boolean breakingChange(Map<String, Object> oldSchema, Map<String, Object> nextSchema) {
        Set<String> oldRequired = stringSet(oldSchema.get("required"));
        Set<String> nextRequired = stringSet(nextSchema.get("required"));
        if (!oldRequired.equals(nextRequired)) {
            return true;
        }
        Map<String, Object> oldProperties = oldSchema.get("properties") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.of();
        Map<String, Object> nextProperties = nextSchema.get("properties") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.of();
        for (String name : oldProperties.keySet()) {
            if (!nextProperties.containsKey(name)) {
                return true;
            }
            Object oldType = oldProperties.get(name) instanceof Map<?, ?> map ? map.get("type") : null;
            Object nextType = nextProperties.get(name) instanceof Map<?, ?> map ? map.get("type") : null;
            if (!java.util.Objects.equals(oldType, nextType)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    private static String endpointKey(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    private static String slug(String value) {
        String slug = value.toLowerCase().replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isBlank() ? "source" : slug;
    }

    /**
     * refetch 只接受 document 自己能给出稳定绝对请求地址的 spec。否则它的行为会依赖
     * 初次导入时仅存在于请求体中的 baseUrl，重新拉取会产生不可审计的模板漂移。
     */
    static void requireSelfContainedServers(OpenApiParser.ParseResult parsed) {
        if (parsed.endpoints().isEmpty()) {
            throw ApiException.badRequest("重新拉取的 OpenAPI 没有可导入 endpoint",
                    "检查 paths 和 operation 是否完整；系统未应用任何变更");
        }
        List<String> unresolved = parsed.endpoints().stream()
                .filter(endpoint -> !absoluteHttpServer(endpoint.serverUrl()))
                .map(endpoint -> endpoint.method().toUpperCase() + " " + endpoint.path())
                .limit(5).toList();
        if (!unresolved.isEmpty()) {
            throw ApiException.conflict("无法安全重新拉取：OpenAPI 没有自包含的绝对 servers（"
                    + String.join("、", unresolved) + "）",
                    "该来源初次导入可能依赖了 REST baseUrl。为避免静默改变请求模板，请在 spec 中配置绝对 "
                            + "http(s) servers 后重试，或以正确 baseUrl 重新导入为新的来源");
        }
    }

    private static boolean absoluteHttpServer(String value) {
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute() && uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private record PendingSync(String specHash, Map<String, ToolDraft> draftsByEndpoint,
                               SpecDiff diff, List<OpenApiParser.ParseError> parseErrors) {
    }
}
