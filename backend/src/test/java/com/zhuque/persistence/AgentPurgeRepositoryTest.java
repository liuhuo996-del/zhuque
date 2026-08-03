package com.zhuque.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;

/** 永久删除只清理没有冻结或任何证据关联的 draft Release。 */
class AgentPurgeRepositoryTest {

    @Test
    void purgeDeletesAssociatedDraftReleaseBeforeDeletingTheRetiredAgent() {
        RecordingJdbc jdbc = new RecordingJdbc();
        ControlPlaneRepository repository = new ControlPlaneRepository(jdbc, new ObjectMapper());

        repository.purgeAgent(jdbc.agent.id());

        int draftReleaseDelete = jdbc.indexOf("delete from release where agent_id=? and status='draft'");
        int agentDelete = jdbc.indexOf("delete from agent where id=?");
        assertTrue(draftReleaseDelete >= 0, "纯 draft Release 应随测试员工一并清理");
        assertTrue(draftReleaseDelete < agentDelete, "必须先清理 draft Release，避免外键阻止删除员工");
    }

    @Test
    void purgeRejectsFrozenOrEvidenceBearingReleaseBeforeAnyDelete() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.releaseEvidenceCount = 1;
        ControlPlaneRepository repository = new ControlPlaneRepository(jdbc, new ObjectMapper());

        ApiException error = assertThrows(ApiException.class, () -> repository.purgeAgent(jdbc.agent.id()));

        assertTrue(error.what().contains("冻结 Release"));
        assertEquals(List.of(), jdbc.updates);
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final ControlPlaneRepository.AgentRow agent = new ControlPlaneRepository.AgentRow(
                UUID.randomUUID(), UUID.randomUUID(), "测试员工", "test-agent", "", "", "retired",
                "https://mcp.example.com/mcp-test-agent", Instant.now());
        private int releaseEvidenceCount;
        private final List<String> updates = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.startsWith("select * from agent")) {
                return (T) agent;
            }
            throw new AssertionError("unexpected row query: " + sql);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("from release r")) {
                return requiredType.cast(releaseEvidenceCount);
            }
            throw new AssertionError("unexpected count query: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return 1;
        }

        int indexOf(String fragment) {
            for (int index = 0; index < updates.size(); index++) {
                if (updates.get(index).contains(fragment)) {
                    return index;
                }
            }
            return -1;
        }
    }
}
