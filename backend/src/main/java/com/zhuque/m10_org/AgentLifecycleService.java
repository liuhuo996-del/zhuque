package com.zhuque.m10_org;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.zhuque.common.ApiException;
import com.zhuque.m8_deploy.HigressAuthTarget;
import com.zhuque.m8_deploy.NacosTarget;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M10 · 数字员工生命周期（status 状态机的副作用在这里执行）。
 *
 *   draft → active（首次发布成功，由 M8 回调，不在本类触发）
 *   active → suspended：撤销 key，但保留 Nacos/Higress 配置
 *   suspended → active：签发新 key 恢复
 *   → retired：撤销全部 key + 从 Nacos 摘除 service（走 M8 target）
 *
 * retired 是终态，不可逆；Release 历史全部保留。
 */
@Service
public class AgentLifecycleService {

    private final ControlPlaneRepository repository;
    private final KeyService keys;
    private final NacosTarget nacos;
    private final HigressAuthTarget auth;

    public AgentLifecycleService(ControlPlaneRepository repository, KeyService keys,
                                 NacosTarget nacos, HigressAuthTarget auth) {
        this.repository = repository;
        this.keys = keys;
        this.nacos = nacos;
        this.auth = auth;
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

    /** 功能：退役。吊销 key + 从 Nacos 摘除 service + 撤 Higress 鉴权，状态置 retired。 */
    public void retire(UUID agentId, String operator) {
        var agent = repository.requireAgent(agentId);
        requireOperator(operator);
        if ("retired".equals(agent.status())) {
            return;
        }
        keys.revokeAll(agentId);
        String serviceName = agent.mcpUrl().substring(agent.mcpUrl().lastIndexOf('/') + 1);
        // 先撤工具暴露，再撤鉴权；退役不是发布事务，不会产生“工具裸奔”。
        nacos.restore(serviceName, null);
        auth.restore(serviceName, null);
        repository.forceAgentStatus(agentId, "retired");
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("操作人不能为空", "使用当前登录人的可审计身份执行生命周期操作");
        }
    }
}
