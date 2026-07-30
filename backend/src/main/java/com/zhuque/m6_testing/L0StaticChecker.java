package com.zhuque.m6_testing;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M6-L0 · 静态检查。零外部依赖、秒级、确定性 100%——企业没有 staging 时，
 * L0+L1(mock) 必须足以跑完整条发布流程。
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

    public record L0Case(String caseId, String result, String detail) {} // result: pass|warn|fail

    /**
     * 功能：对一个 Release 的 manifest 内所有 tool 跑全部静态检查。
     * 纯内存，输入是冻结后的 manifest（不查外部服务）。
     * caseId 命名规范：L0-{检查项}-{tool名}，前端按此分组展示。
     */
    public List<L0Case> run(UUID releaseId) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：同包内工具相似度矩阵（名称编辑距离 + 描述语义相似）。
     * 返回超过阈值的 tool 对。阈值可配。
     */
    public List<String> similarToolPairs(UUID releaseId) {
        throw new UnsupportedOperationException("TODO");
    }
}
