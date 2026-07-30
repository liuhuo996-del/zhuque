package com.zhuque.metrics;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * 北极星指标埋点（P3）：
 *
 *   首次推荐精确率 = 1 - 人审时被删除的工具占比，按部门统计。
 *
 * 采集点：矩阵页「确认并冻结」时，比对 AI 初始候选集 vs 最终入选集，
 * 记录 被删数 / 初始数。超过 30% 基本可断定是池子富化没做好，不是模型不行
 * ——这个结论要在指标页展示出来，直接指向 M1。
 */
@Service
public class NorthStarMetrics {

    /** 功能：冻结时埋点。initialToolIds = AI 候选，finalToolIds = 人审后入选。 */
    public void recordFirstRecommendation(UUID agentId, UUID departmentId,
                                          java.util.List<UUID> initialToolIds,
                                          java.util.List<UUID> finalToolIds) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：按部门聚合首次推荐精确率（时间范围可选）。 */
    public Map<UUID, Double> precisionByDepartment() {
        throw new UnsupportedOperationException("TODO");
    }
}
