package com.zhuque.m1_toolpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;
import com.zhuque.persistence.ControlPlaneRepository;

class ApiSourceLifecycleServiceTest {

    @Test
    void trashAllowsExistingPackReferencesButPreservesTheirImpactForAudit() {
        RecordingRepository repository = new RecordingRepository();
        repository.references = 2;
        repository.impactChain = List.of("tool:orders_get → pack:订单包");

        new ApiSourceLifecycleService(repository).trash(repository.source.id(), "auditor@example.com", "测试接口退役");

        assertEquals("api_source", repository.trashedResourceType);
        assertEquals(repository.source.id(), repository.trashedResourceId);
        assertEquals("auditor@example.com", repository.trashedBy);
        assertEquals("测试接口退役", repository.trashReason);
        assertEquals("trash", repository.auditAction);
        assertEquals(2, repository.auditDetail.get("packReferences"));
        assertEquals(List.of("tool:orders_get → pack:订单包"), repository.auditDetail.get("impactChain"));
    }

    @Test
    void trashRequiresAnAuditableReason() {
        RecordingRepository repository = new RecordingRepository();

        assertThrows(ApiException.class, () -> new ApiSourceLifecycleService(repository)
                .trash(repository.source.id(), "auditor@example.com", " "));

        assertNull(repository.trashedResourceId);
        assertNull(repository.auditAction);
    }

    private static final class RecordingRepository extends ControlPlaneRepository {
        private final ApiSourceRow source = new ApiSourceRow(UUID.randomUUID(), "测试订单 API",
                "https://orders.test.example.com/openapi.yaml", "spec-hash", Instant.now(), "test");
        private int references;
        private List<String> impactChain = List.of();
        private String trashedResourceType;
        private UUID trashedResourceId;
        private String trashedBy;
        private String trashReason;
        private String auditAction;
        private Map<String, Object> auditDetail = Map.of();

        RecordingRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public ApiSourceRow requireApiSource(UUID id) {
            assertEquals(source.id(), id);
            return source;
        }

        @Override
        public boolean isTrashed(String resourceType, UUID resourceId) {
            return false;
        }

        @Override
        public int sourcePackReferenceCount(UUID sourceId) {
            assertEquals(source.id(), sourceId);
            return references;
        }

        @Override
        public List<String> impactChain(UUID sourceId) {
            assertEquals(source.id(), sourceId);
            return impactChain;
        }

        @Override
        public void trashResource(String resourceType, UUID resourceId, String actor, String reason) {
            trashedResourceType = resourceType;
            trashedResourceId = resourceId;
            trashedBy = actor;
            trashReason = reason;
        }

        @Override
        public void restoreResource(String resourceType, UUID resourceId, String actor) {
            // 本测试只覆盖 trash；保留该 override 使 fake 与真实 repository 的新签名一致。
        }

        @Override
        public void insertAuditEvent(String actor, String action, String resourceType,
                                     UUID resourceId, Map<String, Object> detail) {
            assertEquals(source.id(), resourceId);
            assertEquals("api_source", resourceType);
            auditAction = action;
            auditDetail = detail;
        }
    }
}
