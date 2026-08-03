package com.zhuque.m1_toolpool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.ai.AiModelClient;
import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;
import com.zhuque.common.JobProgress;
import com.zhuque.common.JobRegistry;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.ToolRow;

/**
 * M1-步骤4 · AI 富化。匹配质量的天花板在这里，不在匹配模型。
 *
 * 富化内容：
 * - description 重写：必须写清「何时用 / 何时别用 / 前置条件」三段
 * - 推断 effect（read/write/delete），method 语义只是先验，要结合描述判断
 * - 推断参数语义（哪个参数是业务主键、哪个是过滤条件）
 *
 * 纪律：
 * - AI 输出一律当草稿：写回 tool 后 enrichment_status 只到 enriched，
 *   人工确认才能到 reviewed（reviewed 的写入口在 confirmReview，别处不许改）
 * - 每条富化结果存置信度和依据（模型看到了什么才这么判断的）
 * - 幂等可重跑：重跑覆盖 enriched 的字段，但绝不覆盖 reviewed 的
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    private static final int BATCH_SIZE = 15;

    private final ControlPlaneRepository repository;
    private final AiModelClient ai;
    private final ObjectMapper json;
    private final StaticAnnotator annotator;
    private final JobRegistry jobs;
    private final Executor executor;

    public EnrichmentService(ControlPlaneRepository repository, AiModelClient ai, ObjectMapper json,
                             StaticAnnotator annotator, JobRegistry jobs,
                             @Qualifier("enrichmentExecutor") Executor executor) {
        this.repository = repository;
        this.ai = ai;
        this.json = json;
        this.annotator = annotator;
        this.jobs = jobs;
        this.executor = executor;
    }

    /** 单条富化结果（AI 草稿） */
    public record Enrichment(
            UUID toolId,
            String description,
            String effect,
            String rationale,   // 依据：模型基于哪些信号做的判断
            double confidence) {
    }

    /**
     * 功能：批量富化入口。异步执行，立刻返回 jobId。
     * 分批调模型（每批 10~20 条），失败的条目跳过并记录，不中断整批。
     * 幂等：对同一批 tool 重跑，结果覆盖旧的 enriched 草稿。
     */
    public String enrichBatch(List<UUID> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            throw ApiException.badRequest("未选择要富化的工具", "至少选择一条 raw/enriched 工具后重试");
        }
        List<UUID> uniqueIds = List.copyOf(new LinkedHashSet<>(toolIds));
        // 先校验 id，避免异步任务启动后才发现整批参数无效。
        List<ToolRow> tools = repository.toolsByIds(uniqueIds);
        if (tools.size() != uniqueIds.size()) {
            throw ApiException.badRequest("部分工具不存在", "刷新工具池后重新选择");
        }
        String jobId = jobs.start(tools.size(), "准备批量富化");
        executor.execute(() -> process(jobId, tools));
        return jobId;
    }

    /** 功能：查询批量富化进度（供前端进度条轮询，含当前正在处理的 tool 名）。 */
    public JobProgress progress(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * 功能：人工确认富化结果 → enrichment_status 置为 reviewed。
     * 这是 reviewed 的唯一写入口。reviewed 是 M7 敏感字段门禁的依据，
     * 所以要记录确认人。
     */
    public void confirmReview(UUID toolId, String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw ApiException.badRequest("确认人不能为空", "传入当前登录人的稳定标识");
        }
        ToolRow tool = repository.requireTool(toolId);
        if ("raw".equals(tool.enrichmentStatus())) {
            throw ApiException.conflict("raw 工具不能直接标记 reviewed", "先运行 AI 富化并检查结果，再人工确认");
        }
        if (tool.deprecatedAt() != null) {
            throw ApiException.conflict("该工具对应的 endpoint 已从最新 OpenAPI 移除，不能继续复核",
                    "在能力包中替换该工具；如上游恢复该 endpoint，请重新拉取 spec 后再复核");
        }
        if (repository.isTrashed("api_source", tool.apiSourceId())) {
            throw ApiException.conflict("工具所属 REST API 已在回收站中，不能继续复核", "先恢复 REST API 来源，或保留归档状态并在能力包中替换该工具");
        }
        repository.confirmToolReview(toolId, reviewer.trim());
        log.info("tool enrichment reviewed: toolId={}, reviewer={}", toolId, reviewer.trim());
    }

    private void process(String jobId, List<ToolRow> tools) {
        int done = 0;
        int failed = 0;
        try {
            for (int offset = 0; offset < tools.size(); offset += BATCH_SIZE) {
                List<ToolRow> batch = tools.subList(offset, Math.min(offset + BATCH_SIZE, tools.size()));
                Map<UUID, ModelDraft> modelDrafts = modelDrafts(batch);
                for (ToolRow tool : batch) {
                    jobs.update(jobId, done, "正在富化 " + tool.name() + " (" + (done + 1) + "/" + tools.size() + ")");
                    try {
                        ModelDraft draft = modelDrafts.getOrDefault(tool.id(), fallback(tool));
                        Map<String, Object> schema = enrichSchema(tool.inputSchema(), draft);
                        int tokenCost = annotator.tokenCost(tool.name(), draft.description(), schema);
                        repository.updateEnrichment(tool.id(), draft.description(), draft.effect(), schema, tokenCost);
                    } catch (RuntimeException error) {
                        failed++;
                        log.warn("skip failed enrichment item: toolId={}, reason={}", tool.id(), error.getMessage());
                    }
                    done++;
                }
            }
            String result = failed == 0 ? "富化完成" : "富化完成，跳过 " + failed + " 条失败项";
            jobs.done(jobId, result);
        } catch (RuntimeException error) {
            jobs.fail(jobId, "批量富化中止", error);
            log.error("enrichment job failed: jobId={}", jobId, error);
        }
    }

    private Map<UUID, ModelDraft> modelDrafts(List<ToolRow> tools) {
        if (!ai.available()) {
            return Map.of();
        }
        String system = """
                你是企业 API 工具目录富化器。只返回 JSON object，结构为
                {"items":[{"toolId":"uuid","description":"何时用：...。何时别用：...。前置条件：...。",
                "effect":"read|write|delete|unknown","rationale":"判断依据","confidence":0.0,
                "parameterSemantics":{"参数名":"business_key|filter|input"}}]}。
                不得虚构接口能力；method 只是 effect 的先验，必须结合描述和路径判断。
                """;
        List<Map<String, Object>> catalog = tools.stream().map(tool -> Map.<String, Object>of(
                "toolId", tool.id().toString(), "name", tool.name(), "description", tool.description(),
                "method", tool.method(), "path", tool.path(), "inputSchema", tool.inputSchema())).toList();
        JsonNode response = ai.completeJson(system, CanonicalJson.canonicalize(Map.of("tools", catalog)))
                .orElse(null);
        if (response == null || !response.path("items").isArray()) {
            return Map.of();
        }
        Map<UUID, ModelDraft> result = new LinkedHashMap<>();
        for (JsonNode item : response.path("items")) {
            try {
                UUID id = UUID.fromString(item.path("toolId").asText());
                ToolRow tool = tools.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
                if (tool == null) {
                    continue;
                }
                Map<String, String> semantics = new LinkedHashMap<>();
                item.path("parameterSemantics").fields().forEachRemaining(entry ->
                        semantics.put(entry.getKey(), entry.getValue().asText("input")));
                ModelDraft fallback = fallback(tool);
                result.put(id, new ModelDraft(
                        normalizeDescription(item.path("description").asText(), fallback.description()),
                        validEffect(item.path("effect").asText()) ? item.path("effect").asText() : fallback.effect(),
                        item.path("rationale").asText(fallback.rationale()),
                        clip(item.path("confidence").asDouble(fallback.confidence())),
                        semantics.isEmpty() ? fallback.parameterSemantics() : semantics));
            } catch (RuntimeException ignored) {
                // 单条结构异常由 fallback 接管，不影响同批其他条目。
            }
        }
        return result;
    }

    private ModelDraft fallback(ToolRow tool) {
        String effect = inferEffect(tool.method(), tool.path(), tool.description());
        String original = tool.description() == null || tool.description().isBlank()
                ? tool.method() + " " + tool.path() : tool.description().trim();
        String description = "何时用：" + sentence(original)
                + " 何时别用：请求目标或前置标识不明确时不要调用。"
                + " 前置条件：调用方必须提供 inputSchema 中所有必填参数。";
        Map<String, String> semantics = new LinkedHashMap<>();
        properties(tool.inputSchema()).keySet().forEach(name -> semantics.put(name, inferSemantic(name)));
        String rationale = "基于 HTTP " + tool.method() + "、路径 " + tool.path() + " 与原始描述的确定性规则";
        return new ModelDraft(description, effect, rationale, 0.72, semantics);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enrichSchema(Map<String, Object> source, ModelDraft draft) {
        Map<String, Object> schema = json.convertValue(source, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        Map<String, Object> properties = properties(schema);
        draft.parameterSemantics().forEach((name, semantic) -> {
            Object value = properties.get(name);
            if (value instanceof Map<?, ?> raw) {
                ((Map<String, Object>) raw).put("x-semantic", semantic);
            }
        });
        schema.put("x-enrichment", Map.of(
                "model", ai.modelName(),
                "rationale", draft.rationale(),
                "confidence", draft.confidence(),
                "enrichedAt", Instant.now().toString()));
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        Object value = schema.get("properties");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String inferEffect(String method, String path, String description) {
        String upper = method == null ? "" : method.toUpperCase();
        String text = (path + " " + description).toLowerCase();
        if ("DELETE".equals(upper) || text.matches(".*(delete|remove|删除|注销).*")) {
            return "delete";
        }
        if (List.of("POST", "PUT", "PATCH").contains(upper)
                || text.matches(".*(create|update|trigger|refund|创建|修改|发起|执行).*")) {
            return "write";
        }
        if (List.of("GET", "HEAD", "OPTIONS").contains(upper)) {
            return "read";
        }
        return "unknown";
    }

    private static String inferSemantic(String name) {
        String value = name.toLowerCase();
        if (value.matches(".*(^|_)(id|uuid|code|no|key)$")) {
            return "business_key";
        }
        if (value.matches(".*(filter|query|search|keyword|status|sort|page|limit|from|to|start|end).*$")) {
            return "filter";
        }
        return "input";
    }

    private static String normalizeDescription(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        String value = candidate.trim();
        return value.contains("何时用") && value.contains("何时别用") && value.contains("前置条件")
                ? value : fallback;
    }

    private static String sentence(String value) {
        return value.endsWith("。") || value.endsWith(".") ? value : value + "。";
    }

    private static boolean validEffect(String value) {
        return List.of("read", "write", "delete", "unknown").contains(value);
    }

    private static double clip(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record ModelDraft(String description, String effect, String rationale, double confidence,
                              Map<String, String> parameterSemantics) {
    }
}
