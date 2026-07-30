package com.zhuque.m1_toolpool;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.zhuque.m1_toolpool.OpenApiParser.ParsedEndpoint;

/**
 * M1-步骤2 · 从解析结果生成 tool 草稿（enrichment_status = raw）。
 */
@Component
public class ToolDraftGenerator {

    /** tool 表条目的草稿形态（未落库，未富化） */
    public record ToolDraft(
            String name,
            String description,          // 草稿阶段 = summary 原文，富化后重写
            Map<String, Object> inputSchema,
            Map<String, Object> requestTemplate,
            String method,
            String path,
            java.util.List<String> outputFields) {
    }

    /**
     * 功能：生成全局唯一的 tool 名。
     * 规则：{source_slug}_{operationId}；无 operationId 时从 method+path 生成
     * （如 get_orders_order_id），并处理非法字符和长度上限。
     * 唯一性冲突（同一 source 两个同名 operationId）时加序号后缀并记 warning。
     */
    public String buildName(String sourceSlug, ParsedEndpoint ep) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：生成 input_schema（JSON Schema）+ request_template。
     * request_template 结构：method / url 模板（path 参数占位）/ headers /
     * query 映射 / argsToJsonBody 的映射。占位符命名必须与 input_schema
     * 的属性名严格一致——L0 会检查两边一致性，M8 的 Nacos payload 直接引用它。
     */
    public ToolDraft generate(String sourceSlug, ParsedEndpoint ep) {
        throw new UnsupportedOperationException("TODO");
    }
}
