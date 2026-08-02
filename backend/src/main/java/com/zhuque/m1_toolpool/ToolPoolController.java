package com.zhuque.m1_toolpool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;
import com.zhuque.common.JobProgress;
import com.zhuque.m1_toolpool.ToolDraftGenerator.ToolDraft;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.ToolRow;

/**
 * M1 · 工具池 HTTP 入口。
 *
 * 路由规划（与前端 /tools 页对应）：
 *   POST /api/sources                 导入 OpenAPI（URL 或上传原文）→ 解析 → 生成草稿 → 落库
 *   POST /api/sources/{id}/refetch    重新拉取 spec（条目级 diff，见 SpecSyncService）
 *   POST /api/tools/enrich            批量富化，body 传 toolIds，返回 jobId
 *   GET  /api/jobs/{jobId}            查询长任务进度
 *   POST /api/tools/{id}/review       人工确认 → reviewed
 *   GET  /api/tools?source=&effect=&enrichment=&sensitive=&referenced=   列表 + 筛选
 *
 * 错误响应纪律：全部返回「发生了什么 + 怎么修」两段结构
 * （前端 ErrorState 组件直接渲染 what/fix 两个字段）。
 */
@RestController
@RequestMapping("/api")
public class ToolPoolController {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final OpenApiParser parser;
    private final ToolDraftGenerator draftGenerator;
    private final EnrichmentService enrichmentService;
    private final SpecSyncService specSyncService;
    private final StaticAnnotator annotator;
    private final ControlPlaneRepository repository;

    public ToolPoolController(OpenApiParser parser, ToolDraftGenerator draftGenerator,
                              EnrichmentService enrichmentService, SpecSyncService specSyncService,
                              StaticAnnotator annotator, ControlPlaneRepository repository) {
        this.parser = parser;
        this.draftGenerator = draftGenerator;
        this.enrichmentService = enrichmentService;
        this.specSyncService = specSyncService;
        this.annotator = annotator;
        this.repository = repository;
    }

    /** 导入入口：解析（容错，逐 endpoint 报错）→ 草稿 → output_fields → 静态标注 → 落库 */
    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ImportResult importSource(@RequestBody ImportSourceRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("API 来源名称不能为空", "填写一个稳定的公司内来源名称");
        }
        String specText = request.specText();
        if (specText == null || specText.isBlank()) {
            if (request.specUrl() == null || request.specUrl().isBlank()) {
                throw ApiException.badRequest("specUrl 与 specText 不能同时为空", "提供 OpenAPI URL 或上传后的原文");
            }
            specText = fetch(request.specUrl());
        }
        OpenApiParser.ParseResult result = parser.parse(specText);
        if (result.endpoints().isEmpty()) {
            throw ApiException.badRequest("OpenAPI 中没有可导入的 endpoint",
                    result.errors().isEmpty() ? "检查 paths 是否为空" : result.errors().get(0).message());
        }
        String sourceSlug = request.slug() == null || request.slug().isBlank()
                ? slug(request.name()) : slug(request.slug());
        List<ToolDraft> drafts = draftGenerator.generateAll(sourceSlug, result.endpoints());
        UUID sourceId = repository.insertApiSource(request.name().trim(), blankToNull(request.specUrl()),
                CanonicalJson.sha256(specText), defaultValue(request.envProfile(), "prod"));
        int inserted = 0;
        for (ToolDraft draft : drafts) {
            String name = uniqueName(draft.name());
            List<String> sensitivity = annotator.sensitivityFlags(draft.inputSchema(), draft.outputFields());
            int tokenCost = annotator.tokenCost(name, draft.description(), draft.inputSchema());
            repository.insertTool(sourceId, name, draft.description(), draft.inputSchema(), draft.requestTemplate(),
                    draft.method(), draft.path(), "unknown", "raw", draft.outputFields(), sensitivity, tokenCost);
            inserted++;
        }
        return new ImportResult(sourceId, inserted, result.errors());
    }

    @GetMapping("/sources")
    public List<SourceView> sources() {
        return repository.apiSources().stream().map(source -> {
            List<ToolRow> tools = repository.toolsBySource(source.id());
            long raw = tools.stream().filter(tool -> "raw".equals(tool.enrichmentStatus())).count();
            return new SourceView(source.id(), source.name(), source.specUrl(), source.specHash(),
                    source.lastFetchedAt() == null ? null : source.lastFetchedAt().toString(),
                    source.envProfile(), tools.size(), raw);
        }).toList();
    }

    @PostMapping("/sources/{id}/refetch")
    public RefetchResult refetch(@PathVariable UUID id,
                                 @RequestParam(defaultValue = "false") boolean apply) {
        SpecSyncService.SpecDiff diff = specSyncService.refetch(id);
        if (diff != null && apply) {
            specSyncService.applyDiff(id, diff);
        }
        return new RefetchResult(diff, diff == null ? List.of() : specSyncService.pendingParseErrors(id),
                diff == null, apply && diff != null);
    }

    @PostMapping("/tools/enrich")
    public Map<String, String> enrich(@RequestBody ToolIdsRequest request) {
        return Map.of("jobId", enrichmentService.enrichBatch(request == null ? null : request.toolIds()));
    }

    @GetMapping("/jobs/{jobId}")
    public JobProgress job(@PathVariable String jobId) {
        return enrichmentService.progress(jobId);
    }

    @PostMapping("/tools/{id}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@PathVariable UUID id, @RequestBody ReviewRequest request) {
        enrichmentService.confirmReview(id, request == null ? null : request.reviewer());
    }

    @GetMapping("/tools")
    public List<ToolView> tools(@RequestParam(required = false) UUID source,
                                @RequestParam(required = false) String effect,
                                @RequestParam(required = false) String enrichment,
                                @RequestParam(required = false) Boolean sensitive,
                                @RequestParam(required = false) Boolean referenced) {
        return repository.tools().stream()
                .filter(tool -> source == null || source.equals(tool.apiSourceId()))
                .filter(tool -> effect == null || effect.equals(tool.effect()))
                .filter(tool -> enrichment == null || enrichment.equals(tool.enrichmentStatus()))
                .filter(tool -> sensitive == null || sensitive == !tool.sensitivityFlags().isEmpty())
                .filter(tool -> referenced == null || referenced == (repository.packReferenceCount(tool.id()) > 0))
                .map(tool -> new ToolView(tool.id(), tool.apiSourceId(), tool.name(), tool.description(),
                        tool.method(), tool.path(), tool.effect(), tool.enrichmentStatus(), tool.inputSchema(),
                        tool.requestTemplate(), tool.outputFields(), tool.sensitivityFlags(), tool.tokenCost(),
                        repository.packReferenceCount(tool.id())))
                .toList();
    }

    private String fetch(String url) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw ApiException.unavailable("拉取 spec 失败：GET " + url + " 返回 " + response.statusCode(),
                        "检查 URL、网络或改为上传原文");
            }
            return response.body();
        } catch (ApiException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("拉取 spec 被中断", "稍后重试");
        } catch (Exception error) {
            throw ApiException.unavailable("拉取 spec 失败：" + error.getMessage(), "检查 URL、网络和 TLS 证书");
        }
    }

    private String uniqueName(String requested) {
        if (!repository.toolNameExists(requested)) {
            return requested;
        }
        for (int suffix = 2; suffix < 10_000; suffix++) {
            String tail = "_" + suffix;
            String prefix = requested.substring(0, Math.min(requested.length(), 80 - tail.length()));
            String candidate = prefix + tail;
            if (!repository.toolNameExists(candidate)) {
                return candidate;
            }
        }
        throw ApiException.conflict("工具名称冲突过多", "调整来源 slug 或 OpenAPI operationId");
    }

    private static String slug(String value) {
        String result = value.toLowerCase().replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return result.isBlank() ? "source" : result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record ImportSourceRequest(String name, String slug, String specUrl, String specText, String envProfile) {}
    public record ImportResult(UUID sourceId, int importedTools, List<OpenApiParser.ParseError> parseErrors) {}
    public record RefetchResult(SpecSyncService.SpecDiff diff, List<OpenApiParser.ParseError> parseErrors,
                                boolean unchanged, boolean applied) {}
    public record ToolIdsRequest(List<UUID> toolIds) {}
    public record ReviewRequest(String reviewer) {}
    public record SourceView(UUID id, String name, String specUrl, String specHash, String lastFetchedAt,
                             String envProfile, int toolTotal, long rawCount) {}
    public record ToolView(UUID id, UUID apiSourceId, String name, String description, String method, String path,
                           String effect, String enrichmentStatus, Map<String, Object> inputSchema,
                           Map<String, Object> requestTemplate, List<String> outputFields,
                           List<String> sensitivityFlags, int tokenCost, int refCount) {}
}
