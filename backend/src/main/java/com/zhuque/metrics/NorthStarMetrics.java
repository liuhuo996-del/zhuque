package com.zhuque.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private final List<Event> events = new CopyOnWriteArrayList<>();

    /** 功能：冻结时埋点。initialToolIds = AI 候选，finalToolIds = 人审后入选。 */
    public void recordFirstRecommendation(UUID agentId, UUID departmentId,
                                          java.util.List<UUID> initialToolIds,
                                          java.util.List<UUID> finalToolIds) {
        if (agentId == null || departmentId == null) {
            throw com.zhuque.common.ApiException.badRequest("指标缺少 agentId/departmentId", "从矩阵确认冻结入口记录指标");
        }
        java.util.Set<UUID> initial = new java.util.LinkedHashSet<>(initialToolIds == null ? List.of() : initialToolIds);
        java.util.Set<UUID> selected = new java.util.LinkedHashSet<>(finalToolIds == null ? List.of() : finalToolIds);
        long retained = initial.stream().filter(selected::contains).count();
        events.add(new Event(agentId, departmentId, initial.size(), retained));
    }

    /** 功能：按部门聚合首次推荐精确率（时间范围可选）。 */
    public Map<UUID, Double> precisionByDepartment() {
        Map<UUID, long[]> totals = new LinkedHashMap<>();
        for (Event event : events) {
            long[] sum = totals.computeIfAbsent(event.departmentId(), ignored -> new long[2]);
            sum[0] += event.initialCount();
            sum[1] += event.retainedCount();
        }
        Map<UUID, Double> result = new LinkedHashMap<>();
        totals.forEach((department, sum) -> result.put(department, sum[0] == 0 ? 1 : (double) sum[1] / sum[0]));
        return Map.copyOf(result);
    }

    private record Event(UUID agentId, UUID departmentId, long initialCount, long retainedCount) {}
}
