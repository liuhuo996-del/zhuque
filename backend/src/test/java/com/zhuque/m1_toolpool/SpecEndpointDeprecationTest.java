package com.zhuque.m1_toolpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;
import com.zhuque.persistence.ControlPlaneRepository;

/** 回归：spec 移除 endpoint 必须弃用工具，而不是让它继续进入新配置。 */
class SpecEndpointDeprecationTest {

    @Test
    void removedEndpointIsDeprecatedAndItsAuditDriftRetainsTheToolIdentity() {
        RecordingRepository repository = new RecordingRepository();
        SpecSyncService service = new SpecSyncService(repository, null, null, null);

        service.markRemovedEndpoint(repository.sourceId, repository.removed);

        assertEquals(repository.removed.id(), repository.deprecatedId);
        assertTrue(repository.deprecationReason.contains("GET /legacy-orders"));
        assertEquals("api_source", repository.driftScopeType);
        assertEquals(repository.sourceId, repository.driftScopeId);
        assertEquals("spec", repository.driftKind);
        assertEquals("endpoint_removed", repository.driftDetail.get("change"));
        assertEquals(true, repository.driftDetail.get("toolDeprecated"));
        assertEquals(repository.removed.id().toString(), repository.driftDetail.get("toolId"));
    }

    @Test
    void newPackConfigurationRejectsAHiddenDeprecatedToolBeforeDeletingExistingBindings() {
        SelectionRepository repository = new SelectionRepository();
        UUID deprecatedTool = UUID.randomUUID();

        ApiException error = assertThrows(ApiException.class, () -> repository.replacePackTools(
                UUID.randomUUID(), List.of(deprecatedTool), "human", Map.of(), Map.of()));

        assertTrue(error.what().contains("已弃用"));
        assertFalse(repository.updateAttempted);
    }

    @Test
    void directReviewEndpointCannotReviveADeprecatedTool() {
        ReviewRepository repository = new ReviewRepository();
        EnrichmentService service = new EnrichmentService(repository, null, null, null, null, null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.confirmReview(repository.tool.id(), "auditor@example.com"));

        assertTrue(error.what().contains("已从最新 OpenAPI 移除"));
        assertFalse(repository.reviewConfirmed);
    }

    private static final class RecordingRepository extends ControlPlaneRepository {
        private final UUID sourceId = UUID.randomUUID();
        private final ToolRow removed = new ToolRow(UUID.randomUUID(), sourceId, "orders_legacy",
                "legacy endpoint", Map.of(), Map.of(), "GET", "/legacy-orders", "read", "reviewed",
                List.of(), List.of(), 1, Instant.now(), null, null);
        private UUID deprecatedId;
        private String deprecationReason;
        private String driftScopeType;
        private UUID driftScopeId;
        private String driftKind;
        private Map<String, Object> driftDetail = Map.of();

        RecordingRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public int packReferenceCount(UUID toolId) {
            assertEquals(removed.id(), toolId);
            return 1;
        }

        @Override
        public void deprecateTool(UUID toolId, String reason) {
            deprecatedId = toolId;
            deprecationReason = reason;
        }

        @Override
        public void insertDriftEvent(String scopeType, UUID scopeId, String kind, Map<String, Object> detail) {
            driftScopeType = scopeType;
            driftScopeId = scopeId;
            driftKind = kind;
            driftDetail = detail;
        }
    }

    private static final class SelectionRepository extends ControlPlaneRepository {
        private boolean updateAttempted;

        SelectionRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public List<ToolRow> toolsByIds(java.util.Collection<UUID> ids) {
            // 正常 repository 会在 SQL 中过滤 deprecated_at；模拟过滤后的空结果。
            return List.of();
        }

        @Override
        public int packReferenceCount(UUID toolId) {
            return 0;
        }

        @Override
        public void deprecateTool(UUID toolId, String reason) {
            throw new AssertionError("not used by pack selection");
        }

        @Override
        public List<ToolRow> toolsBySource(UUID sourceId) {
            return List.of();
        }

        // replacePackTools must fail before it reaches JDBC delete/insert.
        @Override
        public void updateApiSourceHash(UUID id, String hash) {
            updateAttempted = true;
        }
    }

    private static final class ReviewRepository extends ControlPlaneRepository {
        private final ToolRow tool = new ToolRow(UUID.randomUUID(), UUID.randomUUID(), "legacy_orders",
                "legacy endpoint", Map.of(), Map.of(), "GET", "/legacy-orders", "read", "enriched",
                List.of(), List.of(), 1, Instant.now(), Instant.now(), "endpoint removed");
        private boolean reviewConfirmed;

        ReviewRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public ToolRow requireTool(UUID id) {
            assertEquals(tool.id(), id);
            return tool;
        }

        @Override
        public boolean isTrashed(String resourceType, UUID resourceId) {
            return false;
        }

        @Override
        public void confirmToolReview(UUID toolId, String reviewer) {
            reviewConfirmed = true;
        }
    }
}
