package com.zhuque.m8_deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.zhuque.m2_agent.AgentService;
import com.zhuque.persistence.ControlPlaneRepository;

class DualTargetPublisherTest {

    @Test
    void publishWritesOnlyNacosAndWaitsUntilRegistryDetailIsVisible() {
        Rig rig = new Rig();

        rig.publisher.publish(rig.release.id(), "publisher@example.com");

        assertEquals(List.of("nacos:read", "nacos:apply", "nacos:visible"), rig.events);
        assertEquals(List.of("nacos:success"), rig.repository.deployRecords);
    }

    @Test
    void partialNacosFailureRestoresSnapshotWithoutCallingHigress() {
        Rig rig = new Rig();
        rig.nacos.failApply = true;

        ApiException error = assertThrows(ApiException.class,
                () -> rig.publisher.publish(rig.release.id(), "publisher@example.com"));

        assertTrue(error.what().contains("已恢复原快照"));
        assertEquals(List.of("nacos:read", "nacos:apply", "nacos:restore"), rig.events);
        assertEquals(List.of("nacos:failed_rolled_back"), rig.repository.deployRecords);
    }

    @Test
    void failedNacosRestoreIsReportedAsCriticalInconsistency() {
        Rig rig = new Rig();
        rig.nacos.failApply = true;
        rig.nacos.failRestore = true;

        ApiException error = assertThrows(ApiException.class,
                () -> rig.publisher.publish(rig.release.id(), "publisher@example.com"));

        assertTrue(error.what().contains("恢复不完整"));
        assertTrue(rig.repository.deployRecords.get(0).startsWith("nacos:critical_inconsistent"));
    }

    private static final class Rig {
        private final List<String> events = new ArrayList<>();
        private final RecordingRepository repository = new RecordingRepository();
        private final RecordingNacos nacos = new RecordingNacos(events);
        private final ControlPlaneRepository.ReleaseRow release = repository.release;
        private final DualTargetPublisher publisher = new DualTargetPublisher(nacos,
                new PassingPrecheck(repository, nacos), repository, new AgentService(repository));
    }

    private static final class RecordingRepository extends ControlPlaneRepository {
        private final UUID agentId = UUID.randomUUID();
        private final UUID releaseId = UUID.randomUUID();
        private final AgentRow agent = new AgentRow(agentId, UUID.randomUUID(), "Agent", "agent", "", "",
                "active", "http://localhost:8080/mcp/mcp-dept-agent/sse", Instant.now());
        private final ReleaseRow release = new ReleaseRow(releaseId, agentId, "1.0.0", "approved",
                Map.of(), "manifest-hash",
                Map.of("mcpName", "mcp-dept-agent", "service", Map.of("name", "mcp-dept-agent")),
                Map.of(), Map.of(), Map.of(), Instant.now());
        private final List<String> deployRecords = new ArrayList<>();

        RecordingRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public ReleaseRow requireRelease(UUID id) {
            assertEquals(releaseId, id);
            return release;
        }

        @Override
        public AgentRow requireAgent(UUID id) {
            assertEquals(agentId, id);
            return agent;
        }

        @Override
        public void assertReleaseCreatedAfterLastAgentRestore(UUID id) {
            assertEquals(releaseId, id);
        }

        @Override
        public boolean hasValidApproval(UUID id, String manifestHash) {
            return releaseId.equals(id) && "manifest-hash".equals(manifestHash);
        }

        @Override
        public void insertDeployRecord(UUID id, String target, String payloadHash, String result) {
            assertEquals(releaseId, id);
            deployRecords.add(target + ":" + result);
        }

        @Override
        public void transitionRelease(UUID id, String expected, String next) {
            assertEquals(releaseId, id);
            assertEquals("approved", expected);
            assertEquals("released", next);
        }

        @Override
        public void supersedeOtherReleased(UUID id, UUID keepReleaseId) {
            assertEquals(agentId, id);
            assertEquals(releaseId, keepReleaseId);
        }
    }

    private static final class PassingPrecheck extends DeployPrecheck {
        PassingPrecheck(ControlPlaneRepository repository, NacosTarget nacos) {
            super(repository, nacos);
        }

        @Override
        public List<CheckItem> checkFor(UUID releaseId) {
            return List.of();
        }
    }

    private static final class RecordingNacos extends NacosTarget {
        private final List<String> events;
        private boolean failApply;
        private boolean failRestore;

        RecordingNacos(List<String> events) {
            this.events = events;
        }

        @Override
        public Map<String, Object> read(String serviceName) {
            events.add("nacos:read");
            return Map.of("service", "before");
        }

        @Override
        public void apply(UUID releaseId, Map<String, Object> payload) {
            events.add("nacos:apply");
            if (failApply) {
                throw ApiException.unavailable("模拟 Nacos 部分写入后失败", "重试");
            }
        }

        @Override
        public Map<String, Object> awaitMcpVisible(String serviceName) {
            events.add("nacos:visible");
            return Map.of("mcpId", "test-id", "service", Map.of());
        }

        @Override
        public void restore(String serviceName, Map<String, Object> snapshot) {
            events.add("nacos:restore");
            if (failRestore) {
                throw ApiException.unavailable("模拟 Nacos 恢复失败", "人工处理");
            }
        }
    }
}
