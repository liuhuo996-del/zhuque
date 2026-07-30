package com.zhuque.m1_toolpool;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * M1 · spec 的拉取与「条目级 diff」再同步。
 *
 * 核心纪律：重新拉取 spec 时做条目级 diff，绝不全表重建——
 * 全表重建会丢掉人工审核状态（reviewed）和富化结果，这是不可接受的。
 */
@Service
public class SpecSyncService {

    /** 条目级 diff 结果（同时也是 M9 spec 漂移的 detail 数据结构） */
    public record SpecDiff(
            List<String> addedEndpoints,          // 新增：生成新 tool 草稿（raw）
            List<UUID> removedToolIds,            // 删除：标记而非物理删（可能被 pack 引用）
            List<UUID> changedToolIds,            // 修改：更新 schema，enrichment 降回 enriched 待复核
            boolean breaking) {                   // 删 endpoint/删必填参数/改类型/改必填性 = breaking
    }

    /**
     * 功能：拉取 spec_url，算 hash。hash 未变直接返回 null（无事发生）。
     * 拉取失败（404/超时/非 JSON）时抛带指引的异常：
     * 「拉取 spec 失败：GET ... 返回 404。检查 spec_url 是否变更，或改为手动上传。」
     */
    public SpecDiff refetch(UUID apiSourceId) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：把 diff 应用到 tool 表。
     * - added → ToolDraftGenerator 走全流程生成草稿
     * - removed → 打删除标记；若被 pack 引用，同时生成 drift_event 提醒（交给 M9 落库）
     * - changed → 只更新 input_schema/output_fields/request_template，
     *   保留 description 等富化字段，enrichment_status: reviewed → enriched（需重新复核）
     */
    public void applyDiff(UUID apiSourceId, SpecDiff diff) {
        throw new UnsupportedOperationException("TODO");
    }
}
