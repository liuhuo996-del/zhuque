package com.zhuque.m1_toolpool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * M1-步骤1 · OpenAPI 3.x 解析。
 *
 * 输入：OpenAPI 文档（URL 拉取或上传的原文）
 * 输出：一组「已展开的 endpoint」——后续草稿生成不再需要回头看原文档
 *
 * 必须处理：
 * - 展开 $ref（含跨文件 ref 可先不支持，报清晰错误）
 * - 合并 allOf
 * - oneOf/anyOf：取属性并集，并在结果上标注 variantOf 信息（富化和 L0 检查要用）
 * - 继承 path 级 parameters 到每个 operation
 *
 * 容错纪律：不合规文档逐 endpoint 报错，不整体失败。
 * 一个 500 endpoint 的文档里有 3 个坏的，应该得到 497 条结果 + 3 条 ParseError，
 * 每条 ParseError 要指出位置（path + method + 哪个 $ref / 哪行），
 * 前端会把它渲染成「OpenAPI 解析失败：第 N 行 $ref ... 无法解析」。
 */
@Component
public class OpenApiParser {

    /** 解析成功的单个 endpoint（已完全展开，自包含） */
    public record ParsedEndpoint(
            String operationId,      // 可能为空，为空时草稿生成用 method+path 造名
            String method,
            String path,
            String summary,
            Map<String, Object> parameters,     // 已合并 path 级参数，含 in/required/schema
            Map<String, Object> requestBodySchema,
            Map<String, Object> responseSchema, // 主成功响应（2xx）的 schema
            Map<String, Object> examples) {
    }

    /** 单个 endpoint 的解析失败记录（逐条报错，不整体失败） */
    public record ParseError(String path, String method, String location, String message) {
    }

    public record ParseResult(List<ParsedEndpoint> endpoints, List<ParseError> errors) {
    }

    /**
     * 功能：解析入口。specText 为文档原文（JSON 或 YAML）。
     * 实现提示：swagger-parser 做底座（ResolveFully 展开 $ref），
     * allOf 合并与 oneOf 并集要自己遍历 schema 树完成。
     */
    public ParseResult parse(String specText) {
        throw new UnsupportedOperationException("TODO");
    }
}
