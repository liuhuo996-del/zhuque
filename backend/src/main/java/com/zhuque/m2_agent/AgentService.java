package com.zhuque.m2_agent;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zhuque.common.AgentNames;
import com.zhuque.common.ApiException;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.DepartmentRow;

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

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final ControlPlaneRepository repository;

    @Value("${zhuque.mcp-public-base-url:http://localhost:8080/mcp}")
    private String publicBaseUrl;

    public AgentService(ControlPlaneRepository repository) {
        this.repository = repository;
    }

    public record CreateAgentCmd(
            UUID departmentId,
            String name,
            String slug,
            String description,     // 职责描述 / system prompt 原文
            String forbiddenNotes,
            String operator) {       // 控制面操作人，仅用于辅助审计，不进入 agent 核心字段
    }

    /**
     * 功能：创建数字员工。
     * 校验 slug 格式（[a-z0-9-]）与部门内唯一性；生成并固化 mcp_url；status=draft。
     * 返回新 agent 的 id。
     */
    @Transactional
    public UUID create(CreateAgentCmd cmd) {
        if (cmd == null || cmd.departmentId() == null) {
            throw ApiException.badRequest("departmentId 不能为空", "选择数字部门后再创建员工");
        }
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw ApiException.badRequest("数字员工名称不能为空", "填写便于审核识别的名称");
        }
        String slug = cmd.slug() == null ? "" : cmd.slug().trim();
        if (!SLUG.matcher(slug).matches()) {
            throw ApiException.badRequest("slug 格式不合法", "仅使用小写字母、数字和单个连字符，例如 after-sales");
        }
        DepartmentRow department = repository.requireDepartment(cmd.departmentId());
        if (repository.agentSlugExists(cmd.departmentId(), slug)) {
            throw ApiException.conflict("该部门已存在 slug=" + slug + " 的数字员工", "换一个 slug；已创建的 slug 不可修改");
        }
        String mcpUrl = AgentNames.mcpUrl(publicBaseUrl, department.slug(), slug);
        UUID id = repository.insertAgent(cmd.departmentId(), cmd.name().trim(), slug,
                safe(cmd.description()), safe(cmd.forbiddenNotes()), mcpUrl);
        String actor = cmd.operator() == null || cmd.operator().isBlank() ? "console-user" : cmd.operator().trim();
        repository.insertAuditEvent(actor, "create", "agent", id, java.util.Map.of(
            "name", cmd.name().trim(), "slug", slug, "departmentId", cmd.departmentId().toString()));
        return id;
    }

    /**
     * 功能：M8 发布成功后的回调——draft → active。
     * 其他任何路径不允许把 agent 置为 active。
     */
    public void markActiveAfterFirstRelease(UUID agentId) {
        var agent = repository.requireAgent(agentId);
        if ("active".equals(agent.status())) {
            return;
        }
        if (!"draft".equals(agent.status())) {
            throw ApiException.conflict("只有 draft 数字员工能在首次发布后激活",
                    "当前状态为 " + agent.status() + "；刷新后检查生命周期操作");
        }
        if (!repository.updateAgentStatus(agentId, "draft", "active")) {
            throw ApiException.conflict("数字员工状态已被并发修改", "刷新后重试");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
