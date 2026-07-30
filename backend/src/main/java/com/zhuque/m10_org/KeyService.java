package com.zhuque.m10_org;

import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * M10 · Key 管理。
 *
 * 铁律：
 * - 数据库只存引用（key_ref）和轮换/吊销时间，明文永不落库、永不打日志
 * - 明文只在创建/轮换那一次的方法返回值里出现，UI 提示"只显示一次"
 * - 轮换：新 key 生效后，旧 key 保留一个可配的重叠窗口
 *   （zhuque.key.rotation-overlap，默认 24h）再吊销，
 *   避免正在跑的数字员工被打断——延迟吊销用调度任务兜底
 */
@Service
public class KeyService {

    public record IssuedKey(String keyRef, String plaintextOnceOnly) {}

    /** 功能：为 agent 签发新 key：生成 → 存入密钥托管（KMS/Nacos 加密配置）→ 落 key_ref。 */
    public IssuedKey issue(UUID agentId) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：轮换：签发新 key + 调度旧 key 在重叠窗口结束后吊销。 */
    public IssuedKey rotate(UUID agentId) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：立即吊销该 agent 全部有效 key（suspend/retire 用）。 */
    public void revokeAll(UUID agentId) {
        throw new UnsupportedOperationException("TODO");
    }
}
