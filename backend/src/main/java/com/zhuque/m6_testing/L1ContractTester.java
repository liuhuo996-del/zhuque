package com.zhuque.m6_testing;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M6-L1 · 契约测试。打 mock 或 staging，mock 优先。
 *
 * 必测清单（每项每 tool 一组 case，落 test_report，layer=L1）：
 * - 鉴权三态：带 key / 不带 key / 错 key，响应是否符合预期
 * - 参数映射：MCP 入参是否正确落到 path / query / body / header
 *   （对照 request_template 逐参数断言）
 * - 必填缺失时错误是否结构化可读——返回 500 或 HTML 的接口对 Agent 是废的，
 *   这条 fail 要在 detail 里附上实际响应片段
 * - 边界值：空串、超长、特殊字符、类型错误
 * - 幂等实测：同一 Idempotency-Key 调两次 + 回读校验只产生一个副作用
 *   （结果写入 idempotency_verified，M7 的 BLOCK 规则直接消费它）
 */
@Component
public class L1ContractTester {

    public record L1Case(String caseId, String result, String detail) {}

    /**
     * 功能：跑一个 Release 的全部 L1 case。
     * target = "mock"（默认，用 MockServerFactory）或 "staging"（连真环境）。
     * 异步执行返回 jobId，进度走 JobProgress。
     */
    public String run(UUID releaseId, String target) {
        throw new UnsupportedOperationException("TODO");
    }
}
