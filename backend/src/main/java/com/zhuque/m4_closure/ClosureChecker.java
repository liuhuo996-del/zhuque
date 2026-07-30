package com.zhuque.m4_closure;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * M4 · 闭包检查。v1 性价比最高的模块：纯静态分析，基本不调模型，
 * 是"AI 挑的这堆工具到底能不能用"的唯一硬保证。
 *
 * 解决的问题：包里有 get_order_detail(order_id) 和 create_refund(order_id)，
 * 但用户只报手机号，没有工具能从手机号拿到 order_id → 上线即死且不报错。
 *
 * 算法（可达性不动点迭代）：
 * 1. 初始可达集 = 用户/对话可提供的值（UI 显式标注，不允许默认全可达）
 *              + 上下文注入常量（tenant_id、当前用户、当前时间等）
 * 2. 循环：找出「必填参数全部落在可达集内」的 tool
 *         → 把它们的 output_fields 并入可达集，直到不动点
 * 3. 剩下的 tool 即不可达
 *
 * 性能要求：500 工具规模 200ms 内返回；纯内存计算，不查外部服务
 * （tool 数据由调用方一次性传入，本类不碰数据库）。
 */
@Service
public class ClosureChecker {

    /** 检查输入：调用方（M5 冻结 / 矩阵页实时接口）负责把数据拼好 */
    public record ClosureInput(
            List<ToolNode> tools,
            Set<String> userSuppliedParams,   // UI 上显式标注的"对话可提供"
            Set<String> contextConstants) {   // 注入常量，模型不用填
    }

    public record ToolNode(UUID toolId, String name, List<String> requiredParams, List<String> outputFields) {}

    /** 缺口：哪个参数缺、谁需要它、池里谁能补 */
    public record MissingParam(String param, List<String> neededByTools, List<Suggestion> suggestions) {}

    /** FIXABLE 的具体建议：缺什么、哪个工具能补、它输出什么字段 */
    public record Suggestion(UUID toolId, String toolName, List<String> providesFields) {}

    /** 低置信度的语义字段匹配，单独列出让人确认（如 orderNo ≈ order_id, 0.7） */
    public record FuzzyMatch(String requiredParam, String matchedOutputField, double confidence) {}

    public record ClosureResult(
            String conclusion,             // CLOSED | FIXABLE | BLOCKED
            List<MissingParam> missing,    // FIXABLE/BLOCKED 时非空
            List<UUID> unreachableTools,   // 不动点迭代后仍不可达的 tool
            List<UUID> orphanTools,        // 孤儿：输出无人消费且自身不是任何意图的终点
            List<FuzzyMatch> fuzzyMatches) {
    }

    /**
     * 功能：闭包检查主入口（见类注释的三步算法）。
     * FIXABLE 的判定：对每个缺失参数在全池（poolTools，含未入选的）里找 producer；
     * 全部找得到 → FIXABLE + 逐条 Suggestion；任一找不到 → BLOCKED + 缺失参数清单。
     */
    public ClosureResult check(ClosureInput selected, List<ToolNode> poolTools) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：孤儿工具检测。
     * 定义：output_fields 无人消费（没有其他入选 tool 的必填参数与之匹配），
     * 且自身不是任何意图的终点（intentTerminalToolIds 之外）。
     * 孤儿不阻断，只提示。
     */
    public List<UUID> findOrphans(List<ToolNode> selected, Set<UUID> intentTerminalToolIds) {
        throw new UnsupportedOperationException("TODO");
    }
}
