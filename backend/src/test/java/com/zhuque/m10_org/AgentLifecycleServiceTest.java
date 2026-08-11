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
import com.zhuque.m8_deploy.NacosTarget;
import com.zhuque.persistence.ControlPlaneRepository;

/** 生命周期只管理 Nacos Registry；Higress 路由由独立 watcher 自动收敛。 */
class AgentLifecycleServiceTest {

    @Test
    void retiresAnActiveAgentOnlyAfterNacosMcpIsWithdrawn() {
        RecordingRepository repository = new RecordingRepository("active");
        RecordingNacos nacos = new RecordingNacos();
        RecordingKeys keys = new RecordingKeys();

        new AgentLifecycleService(repository, keys, nacos)
                .retire(repository.agent.id(), "auditor@example.com", "功能下线");

        assertEquals(List.of("nacos:withdraw"), nacos.events);
        assertEquals("mcp-sales-orders", nacos.lastServiceName,
                "必须从 /mcp/{name}/sse URL 解析服务名，不能误取 sse");
        assertTrue(keys.revoked);
        assertEquals("retired", repository.forcedStatus);
        assertEquals("agent", repository.trashedType);
        assertEquals("auditor@example.com", repository.trashedBy);
        assertEquals("功能下线", repository.trashedReason);
        assertEquals("trash", repository.auditAction);
    }

    @Test
    void failedNacosWithdrawalRestoresSnapshotAndDoesNotRetireLocally() {
        RecordingRepository repository = new RecordingRepository("active");
        RecordingNacos nacos = new RecordingNacos();
        RecordingKeys keys = new RecordingKeys();
        nacos.failWithdrawal = true;

        ApiException error = assertThrows(ApiException.class,
                () -> new AgentLifecycleService(repository, keys, nacos)
                        .retire(repository.agent.id(), "auditor@example.com", "功能下线"));

        assertTrue(error.what().contains("已恢复原快照"));
        assertEquals(List.of("nacos:withdraw", "nacos:restore"), nacos.events);
        assertFalse(keys.revoked);
        assertEquals(null, repository.forcedStatus);
        assertEquals(null, repository.trashedType);
    }

    @Test
    void localRetirementFailureRestoresNacosInsteadOfLeavingActiveStateOffline() {
        RecordingRepository repository = new RecordingRepository("active");
        repository.failAudit = true;
        RecordingNacos nacos = new RecordingNacos();
        RecordingKeys keys = new RecordingKeys();

        ApiException error = assertThrows(ApiException.class,
                () -> new AgentLifecycleService(repository, keys, nacos)
                        .retire(repository.agent.id(), "auditor@example.com", "功能下线"));

        assertTrue(error.what().contains("已恢复原线上配置"));
        assertEquals(List.of("nacos:withdraw", "nacos:restore"), nacos.events);
        assertTrue(keys.revoked, "即使本地审计写失败，也应首先尝试吊销现有密钥");
    }

    @Test
    void restoreRecordsGenerationBoundaryAndPurgeAuditsOnlyAfterDeletion() {
        RecordingRepository repository = new RecordingRepository("retired");
        RecordingKeys keys = new RecordingKeys();
        new AgentLifecycleService(repository, keys, new RecordingNacos())
                .restoreFromTrash(repository.agent.id(), "auditor@example.com");

        assertEquals("draft", repository.forcedStatus);
        assertEquals("agent", repository.restoredType);
        assertEquals("auditor@example.com", repository.restoredBy);
        assertEquals("restore", repository.auditAction);

        repository = new RecordingRepository("retired");
        AgentLifecycleService lifecycle = new AgentLifecycleService(repository, keys, new RecordingNacos());
        lifecycle.purge(repository.agent.id(), "auditor@example.com");

        assertTrue(repository.purged);
        assertEquals("purge", repository.auditAction);
        assertTrue(repository.auditAfterPurge);
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
                    "https://mcp.example.com/mcp/mcp-sales-orders/sse", Instant.now());
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
        private final List<String> events = new ArrayList<>();
        private final Map<String, Object> before = Map.of("service", "before");
        private boolean failWithdrawal;
        private String lastServiceName;

        @Override
        public Map<String, Object> read(String serviceName) {
            lastServiceName = serviceName;
            return before;
        }

        @Override
        public void restore(String serviceName, Map<String, Object> snapshot) {
            lastServiceName = serviceName;
            String event = snapshot == null ? "nacos:withdraw" : "nacos:restore";
            events.add(event);
            if (snapshot == null && failWithdrawal) {
                throw ApiException.unavailable("模拟 Nacos 删除失败", "重试");
            }
        }
    }
}
