package com.zhuque.m10_org;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import com.zhuque.m8_deploy.NacosTarget;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.AgentKeyRow;

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

    private static final SecureRandom RANDOM = new SecureRandom();
    private final ControlPlaneRepository repository;
    private final NacosTarget secretStore;
    private final TaskScheduler scheduler;

    @Value("${zhuque.key.rotation-overlap:24h}")
    private Duration overlap;

    public KeyService(ControlPlaneRepository repository, NacosTarget secretStore, TaskScheduler scheduler) {
        this.repository = repository;
        this.secretStore = secretStore;
        this.scheduler = scheduler;
    }

    public record IssuedKey(String keyRef, String plaintextOnceOnly) {}

    /** 功能：为 agent 签发新 key：生成 → 存入密钥托管（KMS/Nacos 加密配置）→ 落 key_ref。 */
    public IssuedKey issue(UUID agentId) {
        repository.requireAgent(agentId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String plaintext = "zq_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String keyRef = secretStore.putSecret(agentId, plaintext);
        try {
            repository.insertAgentKey(agentId, keyRef);
        } catch (RuntimeException error) {
            secretStore.deleteSecret(keyRef);
            throw error;
        }
        return new IssuedKey(keyRef, plaintext);
    }

    /** 功能：轮换：签发新 key + 调度旧 key 在重叠窗口结束后吊销。 */
    public IssuedKey rotate(UUID agentId) {
        List<AgentKeyRow> oldKeys = repository.agentKeys(agentId, true);
        IssuedKey issued = issue(agentId);
        Instant revokeAt = Instant.now().plus(overlap);
        for (AgentKeyRow old : oldKeys) {
            scheduler.schedule(() -> {
                secretStore.deleteSecret(old.keyRef());
                repository.revokeKey(old.id());
            }, revokeAt);
        }
        return issued;
    }

    /** 功能：立即吊销该 agent 全部有效 key（suspend/retire 用）。 */
    public void revokeAll(UUID agentId) {
        List<AgentKeyRow> keys = repository.agentKeys(agentId, true);
        for (AgentKeyRow key : keys) {
            secretStore.deleteSecret(key.keyRef());
        }
        repository.revokeAllKeys(agentId);
    }
}
