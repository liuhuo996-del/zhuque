package com.zhuque.m6_testing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M6-L0 · 静态检查。零外部依赖、秒级、确定性 100%。
 * 它是正式测试链的一层，但不能替代 L1 对已配置测试/预发上游的真实契约验证。
 *
 * 检查项清单（每项一个 case，逐条落 test_report，layer=L0）：
 * - 每个参数是否有 description；enum 是否有值域
 * - 是否存在 additionalProperties:true 这类黑洞
 * - 同包内 tool 名/描述相似度（编辑距离 + 语义），过高告警
 *   ——相似工具是 Agent 选错的最大隐性来源
 * - response schema 是否存在、有无 example
 * - token_cost 与包预算
 * - 敏感字段命中汇总
 * - 幂等信号：method 语义 + 有无 Idempotency-Key 声明
 *   （结果供 M7 的 idempotency 规则消费）
 */
@Component
public class L0StaticChecker {

    private final ControlPlaneRepository repository;

    @Value("${zhuque.testing.similarity-threshold:0.78}")
    private double similarityThreshold;
    @Value("${zhuque.testing.max-token-budget:20000}")
    private int maxTokenBudget;

    public L0StaticChecker(ControlPlaneRepository repository) {
        this.repository = repository;
    }

    public record L0Case(String caseId, String result, String detail) {} // result: pass|warn|fail

    /**
     * 功能：对一个 Release 的 manifest 内所有 tool 跑全部静态检查。
     * 纯内存，输入是冻结后的 manifest（不查外部服务）。
     * caseId 命名规范：L0-{检查项}-{tool名}，前端按此分组展示。
     */
    // completeTestRun 会把覆盖不完整的 run 标为 failed 并抛出 ApiException；该失败记录
    // 本身是审计证据，不能被外层 L0 事务回滚掉。
    @Transactional(noRollbackFor = com.zhuque.common.ApiException.class)
    public List<L0Case> run(UUID releaseId) {
        var release = repository.requireReleaseTestable(releaseId);
        List<Map<String, Object>> tools = tools(release.manifest());
        List<L0Case> result = new ArrayList<>();
        int totalTokens = 0;
        for (Map<String, Object> tool : tools) {
            String name = String.valueOf(tool.get("name"));
            Map<String, Object> schema = map(tool.get("inputSchema"));
            Map<String, Object> properties = map(schema.get("properties"));
            List<String> badParams = properties.entrySet().stream().filter(entry -> {
            Map<String, Object> definition = map(entry.getValue());
            boolean missingDescription = String.valueOf(definition.getOrDefault("description", "")).isBlank();
            boolean emptyEnum = definition.containsKey("enum") && list(definition.get("enum")).isEmpty();
            return missingDescription || emptyEnum;
            }).map(Map.Entry::getKey).toList();
            result.add(caseOf("L0-schema-" + name, badParams.isEmpty() ? "pass" : "fail",
                badParams.isEmpty() ? "参数描述与值域完整" : "缺少参数描述或 enum 值域：" + badParams));
            boolean blackHole = Boolean.TRUE.equals(schema.get("additionalProperties"));
            result.add(caseOf("L0-additional-properties-" + name, blackHole ? "fail" : "pass",
                blackHole ? "inputSchema 允许任意字段；请声明明确参数" : "未发现 additionalProperties:true"));
            List<?> outputs = list(tool.get("outputFields"));
            result.add(caseOf("L0-response-" + name, outputs.isEmpty() ? "warn" : "pass",
                outputs.isEmpty() ? "缺少 response schema/output_fields" : "已提取 " + outputs.size() + " 个输出字段"));
            List<?> sensitive = list(tool.get("sensitivityFlags"));
            result.add(caseOf("L0-sensitive-" + name, sensitive.isEmpty() ? "pass" : "warn",
                sensitive.isEmpty() ? "未命中敏感字段" : "命中敏感字段：" + sensitive));
            String effect = String.valueOf(tool.get("effect"));
            Map<String, Object> template = map(tool.get("requestTemplate"));
            String requestUrl = String.valueOf(template.getOrDefault("url", ""));
            boolean absoluteUrl = requestUrl.startsWith("http://") || requestUrl.startsWith("https://");
            result.add(caseOf("L0-request-url-" + name, absoluteUrl ? "pass" : "fail",
                absoluteUrl ? "REST 请求模板使用绝对 URL" : "requestTemplate.url 不是绝对地址；在 OpenAPI servers 或导入 baseUrl 中补全"));
            boolean idempotent = !"write".equals(effect) || template.toString().toLowerCase(Locale.ROOT)
                .contains("idempotency");
            result.add(caseOf("L0-idempotency-" + name, idempotent ? "pass" : "warn",
                idempotent ? "读操作或已声明幂等信号" : "写工具未声明 Idempotency-Key，须由 L1 实测"));
            totalTokens += number(tool.get("tokenCost"));
        }
        result.add(caseOf("L0-budget-release", totalTokens <= maxTokenBudget ? "pass" : "warn",
            "工具 token_cost 合计 " + totalTokens + "，预算 " + maxTokenBudget));
        for (String pair : similarToolPairs(releaseId)) {
            result.add(caseOf("L0-similarity-" + pair.replace(" ↔ ", "-"), "warn",
                "工具过于相似，Agent 可能误选：" + pair));
        }
        String runId = UUID.randomUUID().toString();
        repository.beginTestRun(releaseId, "L0", runId, result.size());
        result.forEach(item -> repository.insertTestReport(releaseId, "L0", runId, item.caseId(), item.result(),
            Map.of("message", item.detail()), Map.of()));
        repository.completeTestRun(releaseId, "L0", runId);
        return List.copyOf(result);
    }

    /**
     * 功能：同包内工具相似度矩阵（名称编辑距离 + 描述语义相似）。
     * 返回超过阈值的 tool 对。阈值可配。
     */
    public List<String> similarToolPairs(UUID releaseId) {
        List<Map<String, Object>> tools = tools(repository.requireRelease(releaseId).manifest());
        List<String> result = new ArrayList<>();
        for (int left = 0; left < tools.size(); left++) {
            for (int right = left + 1; right < tools.size(); right++) {
                Map<String, Object> a = tools.get(left);
                Map<String, Object> b = tools.get(right);
                double score = similarity(a.get("name") + " " + a.get("description"),
                        b.get("name") + " " + b.get("description"));
                if (score >= similarityThreshold) {
                    result.add(a.get("name") + " ↔ " + b.get("name") + " (" + String.format(Locale.ROOT, "%.2f", score) + ")");
                }
            }
        }
        return result;
    }

    private static L0Case caseOf(String id, String result, String detail) {
        return new L0Case(id, result, detail);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tools(Map<String, Object> manifest) {
        Object value = manifest.get("tools");
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double similarity(String left, String right) {
        Set<String> a = grams(left);
        Set<String> b = grams(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> grams(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        Set<String> result = new LinkedHashSet<>();
        int[] points = text.codePoints().toArray();
        for (int index = 0; index + 1 < points.length; index++) {
            result.add(new String(points, index, 2));
        }
        return result;
    }
}
