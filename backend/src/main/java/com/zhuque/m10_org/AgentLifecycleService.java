package com.zhuque.m10_org;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zhuque.common.AgentNames;
import com.zhuque.common.ApiException;
import com.zhuque.m8_deploy.NacosTarget;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M10 · 数字员工生命周期（status 状态机的副作用在这里执行）。
 *
 *   draft → active（首次发布成功，由 M8 回调，不在本类触发）
 *   active → suspended：撤销 key，但保留 Nacos MCP 配置
 *   suspended → active：签发新 key 恢复
 *   → retired：进入回收站；已部署的员工同时撤销 key 并从 Nacos MCP Registry 摘除
 *
 * retired 可恢复为 draft，但不会自动重新暴露任何能力，必须开新 Release 人工发布。
 * 有 Release 证据的员工永远不能物理删除；纯草稿可由回收站二次确认后永久删除。
 */
@Service
public class AgentLifecycleService {

    private final ControlPlaneRepository repository;
    private final KeyService keys;
    private final NacosTarget nacos;

    public AgentLifecycleService(ControlPlaneRepository repository, KeyService keys,
                                 NacosTarget nacos) {
        this.repository = repository;
        this.keys = keys;
        this.nacos = nacos;
    }

    /** 功能：暂停。吊销全部有效 key（KeyService.revokeAll），配置保留。 */
    public void suspend(UUID agentId, String operator) {
        var agent = repository.requireAgent(agentId);
        requireOperator(operator);
        if (!"active".equals(agent.status())) {
            throw ApiException.conflict("只有 active 数字员工可以暂停", "刷新后检查当前状态");
        }
        keys.revokeAll(agentId);
        if (!repository.updateAgentStatus(agentId, "active", "suspended")) {
            throw ApiException.conflict("数字员工状态已变化", "刷新后重试");
        }
    }

    /** 功能：恢复。签发新 key（明文一次性返回），状态回 active。 */
    public String resume(UUID agentId, String operator) {
        var agent = repository.requireAgent(agentId);
        requireOperator(operator);
        if (!"suspended".equals(agent.status())) {
            throw ApiException.conflict("只有 suspended 数字员工可以恢复", "刷新后检查当前状态");
        }
        KeyService.IssuedKey key = keys.issue(agentId);
        if (!repository.updateAgentStatus(agentId, "suspended", "active")) {
            keys.revokeAll(agentId);
            throw ApiException.conflict("数字员工状态已变化", "新签发密钥已撤销，请刷新后重试");
        }
        return key.plaintextOnceOnly();
    }

    /** 功能：退役并进入回收站。已部署时先摘除 Nacos MCP，再更新本地状态。 */
    @Transactional
    public void retire(UUID agentId, String operator) {
        retire(agentId, operator, "用户请求退役");
    }

    @Transactional
    public void retire(UUID agentId, String operator, String reason) {
        var agent = repository.requireAgent(agentId);
        String actor = requireOperator(operator);
        String retirementReason = requireReason(reason);
        if ("retired".equals(agent.status())) {
            return;
        }

        // 先安全摘除线上配置，成功后才撤 key、更新本地状态。这样外部摘除失败时，
        // 本地不会错误显示为已退役；若后续本地写入或吊销 key 失败，则用下面保存的
        // Nacos 快照补偿，不能留下“本地 active、线上已摘除”的静默分叉。
        DeploymentSnapshot withdrawn = null;
        if (needsDeploymentWithdrawal(agent)) {
            withdrawn = withdrawDeployment(agent);
        }
        try {
            keys.revokeAll(agentId);
            repository.forceAgentStatus(agentId, "retired");
            repository.trashResource("agent", agentId, actor, retirementReason);
            repository.insertAuditEvent(actor, "trash", "agent", agentId, Map.of(
                    "name", agent.name(), "previousStatus", agent.status(),
                    "reason", retirementReason,
                    "releaseHistoryRetained", repository.agentHasReleases(agentId)));
        } catch (RuntimeException failure) {
            if (withdrawn == null) {
                throw failure;
            }
            List<String> recoveryFailures = restoreDeployment(withdrawn);
            if (!recoveryFailures.isEmpty()) {
                throw ApiException.unavailable("退役未完成，且线上配置恢复不完整："
                        + String.join("；", recoveryFailures),
                        "本地退役状态会回滚；立即人工核对 Nacos MCP 与密钥吊销状态后再重试");
            }
            throw ApiException.unavailable("退役未完成，已恢复原线上配置：" + message(failure),
                    "本地退役状态未提交；请核对可能已部分吊销的密钥后，修复问题并重新退役");
        }
    }

    /** 从回收站恢复为 draft；不恢复旧 key 或 Nacos 配置。 */
    @Transactional
    public void restoreFromTrash(UUID agentId, String operator) {
        var agent = repository.requireAgent(agentId);
        String actor = requireOperator(operator);
        if (!"retired".equals(agent.status())) {
            throw ApiException.conflict("只有回收站中的数字员工可以恢复", "刷新后检查当前状态");
        }
        repository.forceAgentStatus(agentId, "draft");
        // 不删除 lifecycle 行：恢复时点用于拒绝恢复前的旧 Release 再次发布。
        repository.restoreResource("agent", agentId, actor);
        repository.insertAuditEvent(actor, "restore", "agent", agentId, Map.of(
                "name", agent.name(), "restoredAs", "draft",
                "notice", "不会自动恢复旧凭据或线上配置，须新建 Release 并人工发布"));
    }

    /** 永久删除只适用于从未冻结 Release 的纯草稿；审计事件本身不随资源删除。 */
    @Transactional
    public void purge(UUID agentId, String operator) {
        var agent = repository.requireAgent(agentId);
        String actor = requireOperator(operator);
        // 先完成所有引用 / Release 证据校验与实际删除；避免记录一个并未成功的 purge。
        repository.purgeAgent(agentId);
        repository.insertAuditEvent(actor, "purge", "agent", agentId, Map.of(
                "name", agent.name(), "slug", agent.slug(),
                "retention", "仅保留本条不可变审计事件"));
    }

    private boolean needsDeploymentWithdrawal(ControlPlaneRepository.AgentRow agent) {
        return "active".equals(agent.status()) || "suspended".equals(agent.status())
                || repository.agentHasDeployRecords(agent.id());
    }

    /**
     * GateForge 只管理 Nacos Registry。Higress 会根据平台侧手工配置的 Nacos3
     * source 自动收敛路由，不在此调用 Console API。
     */
    private DeploymentSnapshot withdrawDeployment(ControlPlaneRepository.AgentRow agent) {
        String serviceName = AgentNames.serviceNameFromUrl(agent.mcpUrl());
        Map<String, Object> nacosBefore = nacos.read(serviceName);
        DeploymentSnapshot snapshot = new DeploymentSnapshot(serviceName, nacosBefore);
        try {
            nacos.restore(serviceName, null);
            return snapshot;
        } catch (RuntimeException failure) {
            List<String> recoveryFailures = restoreDeployment(snapshot);
            if (!recoveryFailures.isEmpty()) {
                throw ApiException.unavailable("退役时摘除线上配置失败且恢复不完整："
                        + String.join("；", recoveryFailures),
                        "停止后续发布，人工核对 Nacos MCP Registry 后再重试退役");
            }
            throw ApiException.unavailable("退役时摘除线上配置失败，已恢复原快照：" + message(failure),
                    "修复目标连接或权限后重新执行退役");
        }
    }

    /**
     * 本地事务失败时恢复 Nacos MCP 快照；Higress watcher 会自行跟随 Registry 状态。
     */
    private List<String> restoreDeployment(DeploymentSnapshot snapshot) {
        List<String> recoveryFailures = new ArrayList<>();
        try {
            nacos.restore(snapshot.serviceName(), snapshot.nacosBefore());
        } catch (RuntimeException recoveryError) {
            recoveryFailures.add("Nacos 配置恢复失败：" + message(recoveryError));
        }
        return recoveryFailures;
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("操作人不能为空", "使用当前登录人的可审计身份执行生命周期操作");
        }
        return operator.trim();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 2) {
            throw ApiException.badRequest("退役理由至少需要两个字符", "说明退役原因，便于后续审计和恢复判断");
        }
        return reason.trim();
    }

    private record DeploymentSnapshot(String serviceName, Map<String, Object> nacosBefore) {}
}
