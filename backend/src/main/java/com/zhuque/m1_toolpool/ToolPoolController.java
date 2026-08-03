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
import org.springframework.web.bind.annotation.DeleteMapping;
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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final OpenApiParser parser;
    private final ToolDraftGenerator draftGenerator;
    private final ApiSourceLifecycleService lifecycle;
    private final EnrichmentService enrichmentService;
    private final SpecSyncService specSyncService;
    private final StaticAnnotator annotator;
    private final ControlPlaneRepository repository;

    public ToolPoolController(OpenApiParser parser, ToolDraftGenerator draftGenerator,
                              ApiSourceLifecycleService lifecycle,
                              EnrichmentService enrichmentService, SpecSyncService specSyncService,
                              StaticAnnotator annotator, ControlPlaneRepository repository) {
        this.parser = parser;
        this.draftGenerator = draftGenerator;
        this.lifecycle = lifecycle;
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
        boolean uploaded = request.specText() != null && !request.specText().isBlank();
        boolean remote = request.specUrl() != null && !request.specUrl().isBlank();
        if (!uploaded && !remote) {
            throw ApiException.badRequest("specUrl 与 specText 不能同时为空", "提供 OpenAPI URL 或上传后的原文");
        }
        if (uploaded && remote) {
            throw ApiException.badRequest("一次导入只能使用一个 OpenAPI 来源", "URL 与上传文件二选一，避免后续重新拉取到与已导入内容不同的文档");
        }
        String specText = uploaded ? request.specText() : fetch(request.specUrl());
        OpenApiParser.ParseResult result;
        try {
            result = parser.parse(specText, fallbackServerUrl(request));
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw ApiException.badRequest("OpenAPI 文档无法解析：" + safeMessage(error),
                    "检查 OpenAPI 3.x JSON/YAML、$ref 和 servers；修正后重新导入");
        }
        if (result.endpoints().isEmpty()) {
            throw ApiException.badRequest("OpenAPI 中没有可导入的 endpoint",
                    result.errors().isEmpty() ? "检查 paths 是否为空" : result.errors().get(0).message());
        }
        validateEndpointUrls(result.endpoints());
        String sourceSlug = request.slug() == null || request.slug().isBlank()
                ? slug(request.name()) : slug(request.slug());
        List<ToolDraft> drafts = draftGenerator.generateAll(sourceSlug, result.endpoints());
        UUID sourceId = repository.insertApiSource(request.name().trim(), blankToNull(request.specUrl()),
                CanonicalJson.sha256(specText), environmentProfile(request.envProfile()));
        int inserted = 0;
        for (ToolDraft draft : drafts) {
            String name = uniqueName(draft.name());
            List<String> sensitivity = annotator.sensitivityFlags(draft.inputSchema(), draft.outputFields());
            int tokenCost = annotator.tokenCost(name, draft.description(), draft.inputSchema());
            repository.insertTool(sourceId, name, draft.description(), draft.inputSchema(), draft.requestTemplate(),
                    draft.method(), draft.path(), "unknown", "raw", draft.outputFields(), sensitivity, tokenCost);
            inserted++;
        }
        String actor = request.operator() == null || request.operator().isBlank()
                ? "console-user" : request.operator().trim();
        repository.insertAuditEvent(actor, "import", "api_source", sourceId, Map.of(
                "name", request.name().trim(), "specHash", CanonicalJson.sha256(specText),
                "importedTools", inserted, "parseErrors", result.errors().size()));
        return new ImportResult(sourceId, inserted, result.errors());
    }

    @GetMapping("/sources")
    public List<SourceView> sources(@RequestParam(defaultValue = "false") boolean trash) {
        var sourceRows = trash ? repository.trashedApiSources() : repository.apiSources();
        return sourceRows.stream().map(source -> {
            List<ToolRow> tools = repository.toolsBySource(source.id());
            long raw = tools.stream().filter(tool -> "raw".equals(tool.enrichmentStatus())).count();
            var lifecycleRow = repository.lifecycle("api_source", source.id()).orElse(null);
            return new SourceView(source.id(), source.name(), source.specUrl(), source.specHash(),
                    source.lastFetchedAt() == null ? null : source.lastFetchedAt().toString(),
                    source.envProfile(), tools.size(), raw,
                    lifecycleRow == null ? null : lifecycleRow.changedAt().toString(),
                    lifecycleRow == null ? null : lifecycleRow.changedBy(),
                    lifecycleRow == null ? null : lifecycleRow.reason());
        }).toList();
    }

    /** DELETE 只移入回收站；核心来源、工具和历史 Release 证据全部保留。 */
    @DeleteMapping("/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trashSource(@PathVariable UUID id, @RequestBody(required = false) LifecycleRequest request) {
        lifecycle.trash(id, operator(request), request == null ? null : request.reason());
    }

    @PostMapping("/sources/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreSource(@PathVariable UUID id, @RequestBody(required = false) LifecycleRequest request) {
        lifecycle.restore(id, operator(request));
    }

    @DeleteMapping("/sources/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purgeSource(@PathVariable UUID id, @RequestBody(required = false) LifecycleRequest request) {
        lifecycle.purge(id, operator(request));
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
            URI uri = absoluteHttpUri(url, "OpenAPI URL", "使用 http:// 或 https:// 开头的可访问 OpenAPI 地址");
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
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

    private static String fallbackServerUrl(ImportSourceRequest request) {
        String configured = blankToNull(request.baseUrl());
        if (configured != null) {
            return trimTrailingSlash(absoluteHttpUri(configured, "REST API baseUrl",
                    "使用 http://host:port 或 https://host 形式").toString());
        }
        String specUrl = blankToNull(request.specUrl());
        if (specUrl == null) {
            return null;
        }
        URI uri = absoluteHttpUri(specUrl, "OpenAPI URL", "使用 http:// 或 https:// 开头的可访问 OpenAPI 地址");
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
    }

    /** 正式 L1 契约测试只能使用可发起真实请求的 HTTP(S) endpoint。 */
    private static void validateEndpointUrls(List<OpenApiParser.ParsedEndpoint> endpoints) {
        List<String> invalid = endpoints.stream().filter(endpoint -> {
            try {
                absoluteHttpUri(endpoint.serverUrl(), "endpoint server", "");
                return false;
            } catch (ApiException error) {
                return true;
            }
        }).map(endpoint -> endpoint.method().toUpperCase() + " " + endpoint.path()).limit(5).toList();
        if (!invalid.isEmpty()) {
            throw ApiException.badRequest("OpenAPI 缺少可正式测试的 REST baseUrl：" + String.join("、", invalid),
                    "在 OpenAPI servers 中配置绝对 http(s) 地址，或在导入表单填写 REST baseUrl；不会导入无法正式验证的模板");
        }
    }

    private static URI absoluteHttpUri(String value, String label, String fix) {
        try {
            if (value == null || value.isBlank()) {
                throw ApiException.badRequest(label + " 不能为空", fix);
            }
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw ApiException.badRequest(label + " 不是绝对 HTTP(S) 地址", fix);
            }
            return uri;
        } catch (ApiException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest(label + " 格式不合法", fix);
        }
    }

    private static String environmentProfile(String value) {
        String profile = defaultValue(value, "prod").toLowerCase(java.util.Locale.ROOT);
        if (!List.of("test", "staging", "prod").contains(profile)) {
            throw ApiException.badRequest("正式测试环境不支持：" + profile,
                    "选择 test、staging 或 prod；正式 L1 契约测试仅在 test/staging 执行，prod 仅用于来源登记与追溯");
        }
        return profile;
    }

    private static String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record ImportSourceRequest(String name, String slug, String specUrl, String specText,
                                      String baseUrl, String envProfile, String operator) {}
    public record ImportResult(UUID sourceId, int importedTools, List<OpenApiParser.ParseError> parseErrors) {}
    public record RefetchResult(SpecSyncService.SpecDiff diff, List<OpenApiParser.ParseError> parseErrors,
                                boolean unchanged, boolean applied) {}
    public record ToolIdsRequest(List<UUID> toolIds) {}
    public record ReviewRequest(String reviewer) {}
    public record SourceView(UUID id, String name, String specUrl, String specHash, String lastFetchedAt,
                             String envProfile, int toolTotal, long rawCount,
                             String trashedAt, String trashedBy, String trashReason) {}
    public record ToolView(UUID id, UUID apiSourceId, String name, String description, String method, String path,
                           String effect, String enrichmentStatus, Map<String, Object> inputSchema,
                           Map<String, Object> requestTemplate, List<String> outputFields,
                           List<String> sensitivityFlags, int tokenCost, int refCount) {}
    public record LifecycleRequest(String operator, String reason) {}

    private static String operator(LifecycleRequest request) {
        return request == null ? null : request.operator();
    }
}
