package com.zhuque.m6_testing;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M6-L1 · 内置 mock server。v1 必须有，不是可选项——
 * 企业常常没有 staging，L1 默认打 mock。
 *
 * 生成规则：从 OpenAPI 的 example 生成响应；无 example 时按 schema 造
 * （string 用字段名占位、number 用边界内随机、enum 取第一个值）。
 * mock 要能表达三种鉴权响应（带 key / 不带 / 错 key），
 * 以及"必填缺失时返回结构化错误"的正反例。
 */
@Component
public class MockServerFactory {

    public record MockHandle(String baseUrl, AutoCloseable shutdown) {}

    /**
     * 功能：为一个 api_source 启动临时 mock server（随机端口），
     * 返回 baseUrl 供 L1 把 request_template 的 host 替换过去。
     * 用完必须关（try-with-resources 语义）。
     */
    public MockHandle start(UUID apiSourceId) {
        throw new UnsupportedOperationException("TODO");
    }
}
