package com.zhuque.m1_toolpool;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.zhuque.common.JobProgress;

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
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：查询批量富化进度（供前端进度条轮询，含当前正在处理的 tool 名）。 */
    public JobProgress progress(String jobId) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：人工确认富化结果 → enrichment_status 置为 reviewed。
     * 这是 reviewed 的唯一写入口。reviewed 是 M7 敏感字段门禁的依据，
     * 所以要记录确认人。
     */
    public void confirmReview(UUID toolId, String reviewer) {
        throw new UnsupportedOperationException("TODO");
    }
}
