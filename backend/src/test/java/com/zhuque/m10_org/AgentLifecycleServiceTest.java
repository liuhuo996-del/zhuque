package com.zhuque.m10_org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;
import com.zhuque.m8_deploy.HigressAuthTarget;
import com.zhuque.m8_deploy.NacosTarget;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * 生命周期的关键回归：删除是退役而非硬删；线上摘除失败时的补偿顺序不能重新
 * 产生“工具已暴露但无鉴权”的窗口。测试使用记录型 fake，避免依赖真实网关或 Mockito agent。
 */
class AgentLifecycleServiceTest {

    @Test
    void retiresAnActiveAgentOnlyAfterBothTargetsAreSafelyWithdrawn() {
        RecordingRepository repository = new RecordingRepository("active");
        RecordingNacos nacos = new RecordingNacos();
        RecordingAuth auth = new RecordingAuth();
        RecordingKeys keys = new RecordingKeys();

        new AgentLifecycleService(repository, keys, nacos, auth)
                .retire(repository.agent.id(), "auditor@example.com", "功能下线");

        assertEquals(List.of("nacos:withdraw", "auth:withdraw"), combined(nacos, auth));
        assertTrue(keys.revoked);
        assertEquals("retired", repository.forcedStatus);
        assertEquals("agent", repository.trashedType);
        assertEquals("auditor@example.com", repository.trashedBy);
        assertEquals("功能下线", repository.trashedReason);
        assertEquals("trash", repository.auditAction);
    }

    @Test
    void failedAuthWithdrawalRestoresAuthBeforeNacosAndDoesNotRetireLocally() {
        RecordingRepository repository = new RecordingRepository("active");
        RecordingNacos nacos = new RecordingNacos();
        RecordingAuth auth = new RecordingAuth();
        RecordingKeys keys = new RecordingKeys();
        auth.failWithdrawal = true;

        ApiException error = assertThrows(ApiException.class, () -> new AgentLifecycleService(repository, keys, nacos, auth)
                .retire(repository.agent.id(), "auditor@example.com", "功能下线"));

        assertTrue(error.what().contains("已恢复原快照"));
        assertEquals(List.of("nacos:withdraw", "auth:withdraw", "auth:restore", "nacos:restore"),
                combined(nacos, auth));
        assertFalse(keys.revoked);
        assertEquals(null, repository.forcedStatus);
        assertEquals(null, repository.trashedType);
    }

    @Test
    void localRetirementFailureRestoresTargetsInsteadOfLeavingActiveStateOffline() {
        RecordingRepository repository = new RecordingRepository("active");
        repository.failAudit = true;
        RecordingNacos nacos = new RecordingNacos();
        RecordingAuth auth = new RecordingAuth();
        RecordingKeys keys = new RecordingKeys();

        ApiException error = assertThrows(ApiException.class, () -> new AgentLifecycleService(repository, keys, nacos, auth)
                .retire(repository.agent.id(), "auditor@example.com", "功能下线"));

        assertTrue(error.what().contains("已恢复原线上配置"));
        assertEquals(List.of("nacos:withdraw", "auth:withdraw", "auth:restore", "nacos:restore"),
                combined(nacos, auth));
        assertTrue(keys.revoked, "即使本地审计写失败，也应首先尝试吊销现有密钥");
    }

    @Test
    void restoreRecordsGenerationBoundaryAndPurgeAuditsOnlyAfterDeletion() {
        RecordingRepository repository = new RecordingRepository("retired");
        RecordingKeys keys = new RecordingKeys();
        new AgentLifecycleService(repository, keys, new RecordingNacos(), new RecordingAuth())
                .restoreFromTrash(repository.agent.id(), "auditor@example.com");

        assertEquals("draft", repository.forcedStatus);
        assertEquals("agent", repository.restoredType);
        assertEquals("auditor@example.com", repository.restoredBy);
        assertEquals("restore", repository.auditAction);

        repository = new RecordingRepository("retired");
        AgentLifecycleService lifecycle = new AgentLifecycleService(repository, keys, new RecordingNacos(), new RecordingAuth());
        lifecycle.purge(repository.agent.id(), "auditor@example.com");

        assertTrue(repository.purged);
        assertEquals("purge", repository.auditAction);
        assertTrue(repository.auditAfterPurge);
    }

    private static List<String> combined(RecordingNacos nacos, RecordingAuth auth) {
        List<String> events = new ArrayList<>();
        events.addAll(nacos.events);
        events.addAll(auth.events);
        // The individual fake lists lose cross-target ordering. Reconstruct it from a shared sequence.
        return nacos.sequence;
    }

    private static final class RecordingRepository extends ControlPlaneRepository {
        private final AgentRow agent;
        private String forcedStatus;
        private String trashedType;
        private String trashedBy;
        private String trashedReason;
        private String restoredType;
        private String restoredBy;
        private String auditAction;
        private boolean purged;
        private boolean auditAfterPurge;
        private boolean failAudit;

        RecordingRepository(String status) {
            super(null, new ObjectMapper());
            UUID id = UUID.randomUUID();
            agent = new AgentRow(id, UUID.randomUUID(), "订单专员", "orders", "", "", status,
                    "https://mcp.example.com/mcp-sales-orders", Instant.now());
        }

        @Override
        public AgentRow requireAgent(UUID id) {
            assertEquals(agent.id(), id);
            return agent;
        }

        @Override
        public boolean agentHasDeployRecords(UUID agentId) {
            return false;
        }

        @Override
        public boolean agentHasReleases(UUID agentId) {
            return false;
        }

        @Override
        public void forceAgentStatus(UUID id, String status) {
            forcedStatus = status;
        }

        @Override
        public void trashResource(String resourceType, UUID resourceId, String actor, String reason) {
            trashedType = resourceType;
            trashedBy = actor;
            trashedReason = reason;
        }

        @Override
        public void restoreResource(String resourceType, UUID resourceId, String actor) {
            restoredType = resourceType;
            restoredBy = actor;
        }

        @Override
        public void purgeAgent(UUID agentId) {
            purged = true;
        }

        @Override
        public void insertAuditEvent(String actor, String action, String resourceType,
                                     UUID resourceId, Map<String, Object> detail) {
            auditAction = action;
            auditAfterPurge = !"purge".equals(action) || purged;
            if (failAudit) {
                throw ApiException.unavailable("审计存储不可用", "重试");
            }
        }
    }

    private static final class RecordingKeys extends KeyService {
        private boolean revoked;

        RecordingKeys() {
            super(null, new NacosTarget(), null);
        }

        @Override
        public void revokeAll(UUID agentId) {
            revoked = true;
        }
    }

    private static final class RecordingNacos extends NacosTarget {
        private static final List<String> sequence = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final Map<String, Object> before = Map.of("service", "before");

        RecordingNacos() {
            sequence.clear();
        }

        @Override
        public Map<String, Object> read(String serviceName) {
            return before;
        }

        @Override
        public void restore(String serviceName, Map<String, Object> snapshot) {
            String event = snapshot == null ? "nacos:withdraw" : "nacos:restore";
            events.add(event);
            sequence.add(event);
        }
    }

    private static final class RecordingAuth extends HigressAuthTarget {
        private final List<String> events = new ArrayList<>();
        private final Map<String, Object> before = Map.of("allowList", "before");
        private boolean failWithdrawal;

        RecordingAuth() {
            super(new NacosTarget());
        }

        @Override
        public Map<String, Object> read(String serviceName) {
            return before;
        }

        @Override
        public void restore(String serviceName, Map<String, Object> snapshot) {
            String event = snapshot == null ? "auth:withdraw" : "auth:restore";
            events.add(event);
            RecordingNacos.sequence.add(event);
            if (snapshot == null && failWithdrawal) {
                throw ApiException.unavailable("模拟 Higress 删除失败", "重试");
            }
        }
    }
}
