package com.zhuque.m10_org;

import java.util.UUID;

import org.springframework.stereotype.Service;

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

    /** 功能：暂停。吊销全部有效 key（KeyService.revokeAll），配置保留。 */
    public void suspend(UUID agentId, String operator) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：恢复。签发新 key（明文一次性返回），状态回 active。 */
    public String resume(UUID agentId, String operator) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：退役。吊销 key + 从 Nacos 摘除 service + 撤 Higress 鉴权，状态置 retired。 */
    public void retire(UUID agentId, String operator) {
        throw new UnsupportedOperationException("TODO");
    }
}
