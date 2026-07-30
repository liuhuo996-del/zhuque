package com.zhuque.m2_agent;

import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * M2 · 数字员工的创建与生命周期（status 状态机在 M10 的语义下执行）。
 *
 * 约束（硬）：
 * - slug 创建后不可变：任何 update 接口都不接受 slug 字段
 * - 同一部门内 slug 唯一（数据库唯一约束兜底，这里提前校验给出友好错误）
 * - mcp_url 按 mcp-{department_slug}-{agent_slug} 生成后落库，此后不可变更，
 *   后续 reconcile / 回滚全靠它对账
 * - 新建 agent 初始 status=draft；只有 M8 首次发布成功的回调才能置 active
 */
@Service
public class AgentService {

    public record CreateAgentCmd(
            UUID departmentId,
            String name,
            String slug,
            String description,     // 职责描述 / system prompt 原文
            String forbiddenNotes) { // 明确禁止的事，独立字段，不混进描述
    }

    /**
     * 功能：创建数字员工。
     * 校验 slug 格式（[a-z0-9-]）与部门内唯一性；生成并固化 mcp_url；status=draft。
     * 返回新 agent 的 id。
     */
    public UUID create(CreateAgentCmd cmd) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：M8 发布成功后的回调——draft → active。
     * 其他任何路径不允许把 agent 置为 active。
     */
    public void markActiveAfterFirstRelease(UUID agentId) {
        throw new UnsupportedOperationException("TODO");
    }
}
