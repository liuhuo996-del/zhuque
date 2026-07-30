package com.zhuque.m6_testing;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * M6 · 用例管理。用例来源三条路：
 * 1. AI 从 OpenAPI 生成草稿（generateDrafts）
 * 2. 人工补关键 case
 * 3. （有日志的客户）从真实调用日志回放导入——v1 留接口
 *
 * 用例本身要版本化，并绑定 tool 版本：tool 的 schema 变了，
 * 绑定旧版的用例要标记"待更新"而不是悄悄失效。
 */
@Service
public class TestCaseService {

    public record CaseDef(String caseId, String layer, UUID toolId, String toolVersion,
                          String input, String golden, String origin) {} // origin: ai|human|replay

    /** 功能：AI 生成用例草稿（L1 参数边界 + L2 自然语言任务与 golden tool）。 */
    public List<CaseDef> generateDrafts(UUID releaseId) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：tool schema 变更时，把绑定旧版本的用例标记为"待更新"。M1 的 SpecSyncService 在 applyDiff 后调用。 */
    public void markStaleCases(UUID toolId, String newToolVersion) {
        throw new UnsupportedOperationException("TODO");
    }
}
