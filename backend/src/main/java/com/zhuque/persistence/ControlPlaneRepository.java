package com.zhuque.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;

/**
 * v1 的显式 SQL 持久层。JSONB 在边界处统一转成 Map/List，业务模块不接触 SQL 或 PG 特有类型。
 */
@Repository
public class ControlPlaneRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ControlPlaneRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record DepartmentRow(UUID id, String name, String slug, String consumerGroupRef, Instant createdAt) {}

    public record AgentRow(UUID id, UUID departmentId, String name, String slug, String description,
                           String forbiddenNotes, String status, String mcpUrl, Instant createdAt) {}

    public record IntentRow(UUID id, UUID agentId, String text, int orderNo, String source) {}

    public record ApiSourceRow(UUID id, String name, String specUrl, String specHash,
                               Instant lastFetchedAt, String envProfile) {}

    public record ToolRow(UUID id, UUID apiSourceId, String name, String description,
                          Map<String, Object> inputSchema, Map<String, Object> requestTemplate,
                          String method, String path, String effect, String enrichmentStatus,
                          List<String> outputFields, List<String> sensitivityFlags,
                          int tokenCost, Instant createdAt) {}

    public record PackRow(UUID id, UUID departmentId, String name, String scope, Instant createdAt) {}

    public record ProjectionRow(UUID id, UUID packId, String name, Map<String, Object> visibilityCondition) {}

    public record ReleaseRow(UUID id, UUID agentId, String version, String status,
                             Map<String, Object> manifest, String manifestHash,
                             Map<String, Object> nacosPayload, Map<String, Object> higressAuthPayload,
                             Map<String, Object> sourceSpecHashes, Map<String, Object> targetConstraints,
                             Instant createdAt) {}

    public record TestReportRow(UUID id, UUID releaseId, String layer, String caseId, String result,
                                Map<String, Object> detail, Map<String, Object> modelMeta) {}

    public record GateDecisionRow(UUID id, UUID releaseId, String ruleId, String verdict,
                                  String waivedBy, String waiverReason) {}

    public record ApprovalRow(UUID id, UUID releaseId, String manifestHash, String approver,
                              Instant decidedAt, String decision) {}

    public record DriftEventRow(UUID id, String scopeType, UUID scopeId, String kind,
                                Map<String, Object> detail, Instant detectedAt, String status) {}

    public record AgentKeyRow(UUID id, UUID agentId, String keyRef, Instant rotatedAt, Instant revokedAt) {}

    // ---------------------------------------------------------------- departments

    public List<DepartmentRow> departments() {
        return jdbc.query("select * from department order by created_at", this::department);
    }

    public DepartmentRow requireDepartment(UUID id) {
        return one("select * from department where id = ?", this::department, "数字部门 " + id, id);
    }

    public boolean departmentSlugExists(String slug) {
        return count("select count(*) from department where slug = ?", slug) > 0;
    }

    public UUID insertDepartment(String name, String slug, String consumerGroupRef) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into department(id,name,slug,consumer_group_ref) values (?,?,?,?)",
                id, name, slug, consumerGroupRef);
        return id;
    }

    // ---------------------------------------------------------------- agents/intents

    public AgentRow requireAgent(UUID id) {
        return one("select * from agent where id = ?", this::agent, "数字员工 " + id, id);
    }

    public List<AgentRow> agents(UUID departmentId) {
        if (departmentId == null) {
            return jdbc.query("select * from agent order by created_at desc", this::agent);
        }
        return jdbc.query("select * from agent where department_id = ? order by created_at desc",
                this::agent, departmentId);
    }

    public List<AgentRow> activeAgents() {
        return jdbc.query("select * from agent where status = 'active' order by created_at", this::agent);
    }

    public boolean agentSlugExists(UUID departmentId, String slug) {
        return count("select count(*) from agent where department_id = ? and slug = ?", departmentId, slug) > 0;
    }

    public UUID insertAgent(UUID departmentId, String name, String slug, String description,
                            String forbiddenNotes, String mcpUrl) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into agent(id,department_id,name,slug,description,forbidden_notes,status,mcp_url)
                values (?,?,?,?,?,?,'draft',?)
                """, id, departmentId, name, slug, description, forbiddenNotes, mcpUrl);
        return id;
    }

    public boolean updateAgentStatus(UUID id, String expected, String next) {
        return jdbc.update("update agent set status = ? where id = ? and status = ?", next, id, expected) == 1;
    }

    public void forceAgentStatus(UUID id, String status) {
        if (jdbc.update("update agent set status = ? where id = ?", status, id) != 1) {
            throw ApiException.notFound("数字员工 " + id);
        }
    }

    public List<IntentRow> intentsForAgent(UUID agentId) {
        return jdbc.query("select * from intent where agent_id = ? order by order_no", this::intent, agentId);
    }

    public List<IntentRow> intentsByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select * from intent where id in (" + placeholders(ids.size()) + ") order by order_no",
                this::intent, ids.toArray());
    }

    @Transactional
    public void replaceIntents(UUID agentId, List<IntentRow> intents) {
        requireAgent(agentId);
        jdbc.update("delete from intent where agent_id = ?", agentId);
        for (int index = 0; index < intents.size(); index++) {
            IntentRow intent = intents.get(index);
            jdbc.update("insert into intent(id,agent_id,text,order_no,source) values (?,?,?,?,?)",
                    intent.id() == null ? UUID.randomUUID() : intent.id(), agentId,
                    intent.text(), index + 1, intent.source());
        }
    }

    // ---------------------------------------------------------------- api sources/tools

    public ApiSourceRow requireApiSource(UUID id) {
        return one("select * from api_source where id = ?", this::apiSource, "API 来源 " + id, id);
    }

    public List<ApiSourceRow> apiSources() {
        return jdbc.query("select * from api_source order by name", this::apiSource);
    }

    public UUID insertApiSource(String name, String specUrl, String specHash, String envProfile) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into api_source(id,name,spec_url,spec_hash,last_fetched_at,env_profile)
                values (?,?,?,?,now(),?)
                """, id, name, specUrl, specHash, envProfile);
        return id;
    }

    public void updateApiSourceHash(UUID id, String hash) {
        if (jdbc.update("update api_source set spec_hash = ?, last_fetched_at = now() where id = ?", hash, id) != 1) {
            throw ApiException.notFound("API 来源 " + id);
        }
    }

    public ToolRow requireTool(UUID id) {
        return one("select * from tool where id = ?", this::tool, "工具 " + id, id);
    }

    public List<ToolRow> tools() {
        return jdbc.query("select * from tool order by created_at desc, name", this::tool);
    }

    public List<ToolRow> toolsBySource(UUID sourceId) {
        return jdbc.query("select * from tool where api_source_id = ? order by name", this::tool, sourceId);
    }

    public List<ToolRow> toolsByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select * from tool where id in (" + placeholders(ids.size()) + ") order by name",
                this::tool, ids.toArray());
    }

    public List<ToolRow> toolsForAgent(UUID agentId) {
        return jdbc.query("""
                select distinct t.* from tool t
                join pack_tool pt on pt.tool_id = t.id
                join agent_pack ap on ap.pack_id = pt.pack_id
                where ap.agent_id = ? order by t.name
                """, this::tool, agentId);
    }

    public Optional<ToolRow> toolByEndpoint(UUID sourceId, String method, String path) {
        return optional("select * from tool where api_source_id = ? and method = ? and path = ?",
                this::tool, sourceId, method, path);
    }

    public boolean toolNameExists(String name) {
        return count("select count(*) from tool where name = ?", name) > 0;
    }

    public UUID insertTool(UUID sourceId, String name, String description,
                           Map<String, Object> inputSchema, Map<String, Object> requestTemplate,
                           String method, String path, String effect, String enrichmentStatus,
                           List<String> outputFields, List<String> sensitivityFlags, int tokenCost) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into tool(id,api_source_id,name,description,input_schema,request_template,method,path,
                                 effect,enrichment_status,output_fields,sensitivity_flags,token_cost)
                values (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?::jsonb,?::jsonb,?)
                """, id, sourceId, name, description, toJson(inputSchema), toJson(requestTemplate),
                method, path, effect, enrichmentStatus, toJson(outputFields), toJson(sensitivityFlags), tokenCost);
        return id;
    }

    public boolean updateEnrichment(UUID toolId, String description, String effect,
                                    Map<String, Object> inputSchema, int tokenCost) {
        return jdbc.update("""
                update tool set description = ?, effect = ?, input_schema=?::jsonb, token_cost=?,
                    enrichment_status = 'enriched'
                where id = ? and enrichment_status <> 'reviewed'
                """, description, effect, toJson(inputSchema), tokenCost, toolId) == 1;
    }

    public void confirmToolReview(UUID toolId, String reviewer) {
        Map<String, Object> review = Map.of("reviewedBy", reviewer, "reviewedAt", Instant.now().toString());
        if (jdbc.update("""
                update tool set enrichment_status = 'reviewed',
                    input_schema = input_schema || jsonb_build_object('x-review', ?::jsonb)
                where id = ?
                """, toJson(review), toolId) != 1) {
            throw ApiException.notFound("工具 " + toolId);
        }
    }

    public void updateToolDefinition(UUID toolId, Map<String, Object> inputSchema,
                                     Map<String, Object> requestTemplate, List<String> outputFields,
                                     List<String> sensitivityFlags, int tokenCost) {
        if (jdbc.update("""
                update tool set input_schema=?::jsonb, request_template=?::jsonb, output_fields=?::jsonb,
                    sensitivity_flags=?::jsonb, token_cost=?,
                    enrichment_status=case when enrichment_status='reviewed' then 'enriched' else enrichment_status end
                where id=?
                """, toJson(inputSchema), toJson(requestTemplate), toJson(outputFields),
                toJson(sensitivityFlags), tokenCost, toolId) != 1) {
            throw ApiException.notFound("工具 " + toolId);
        }
    }

    public int packReferenceCount(UUID toolId) {
        return count("select count(*) from pack_tool where tool_id = ?", toolId);
    }

    // ---------------------------------------------------------------- packs/projections

    public List<PackRow> packs(UUID departmentId) {
        if (departmentId == null) {
            return jdbc.query("select * from pack order by created_at desc", this::pack);
        }
        return jdbc.query("select * from pack where department_id = ? order by created_at desc",
                this::pack, departmentId);
    }

    public List<PackRow> packsForAgent(UUID agentId) {
        return jdbc.query("""
                select p.* from pack p join agent_pack ap on ap.pack_id=p.id
                where ap.agent_id=? order by p.created_at
                """, this::pack, agentId);
    }

    public ProjectionRow projectionForPack(UUID packId) {
        return one("select * from projection where pack_id = ?", this::projection,
                "能力包 " + packId + " 的 projection", packId);
    }

    @Transactional
    public UUID createPack(UUID departmentId, String name, String scope, String projectionName,
                           Map<String, Object> visibilityCondition) {
        requireDepartment(departmentId);
        UUID packId = UUID.randomUUID();
        jdbc.update("insert into pack(id,department_id,name,scope) values (?,?,?,?)",
                packId, departmentId, name, scope);
        jdbc.update("insert into projection(id,pack_id,name,visibility_condition) values (?,?,?,?::jsonb)",
                UUID.randomUUID(), packId, projectionName, toJson(visibilityCondition));
        return packId;
    }

    @Transactional
    public void replacePackTools(UUID packId, List<UUID> toolIds, String addedBy,
                                 Map<UUID, String> reasons, Map<UUID, Double> confidences) {
        jdbc.update("delete from pack_tool where pack_id = ?", packId);
        for (UUID toolId : toolIds) {
            jdbc.update("""
                    insert into pack_tool(pack_id,tool_id,added_by,reason,confidence)
                    values (?,?,?,?,?)
                    """, packId, toolId, addedBy, reasons.get(toolId), confidences.get(toolId));
        }
    }

    public void attachPack(UUID agentId, UUID packId) {
        jdbc.update("insert into agent_pack(agent_id,pack_id) values (?,?) on conflict do nothing", agentId, packId);
    }

    public List<UUID> toolIdsForPack(UUID packId) {
        return jdbc.query("select tool_id from pack_tool where pack_id=? order by tool_id",
                (rs, row) -> rs.getObject(1, UUID.class), packId);
    }

    // ---------------------------------------------------------------- releases

    public ReleaseRow requireRelease(UUID id) {
        return one("select * from release where id = ?", this::release, "Release " + id, id);
    }

    public List<ReleaseRow> releases(UUID agentId) {
        if (agentId == null) {
            return jdbc.query("select * from release order by created_at desc", this::release);
        }
        return jdbc.query("select * from release where agent_id=? order by created_at desc", this::release, agentId);
    }

    public UUID insertDraftRelease(UUID agentId, String version) {
        requireAgent(agentId);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into release(id,agent_id,version,status) values (?,?,?,'draft')", id, agentId, version);
        return id;
    }

    public Optional<ReleaseRow> latestRelease(UUID agentId) {
        return optional("select * from release where agent_id=? order by created_at desc limit 1",
                this::release, agentId);
    }

    public Optional<ReleaseRow> previousRelease(UUID agentId, UUID excluding) {
        return optional("""
                select * from release where agent_id=? and id<>? and status<>'draft'
                order by created_at desc limit 1
                """, this::release, agentId, excluding);
    }

    public Optional<ReleaseRow> releasedForAgent(UUID agentId) {
        return optional("""
                select * from release where agent_id=? and status='released'
                order by created_at desc limit 1
                """, this::release, agentId);
    }

    public void updateCompiledRelease(UUID id, String version, Map<String, Object> manifest,
                                      String manifestHash, Map<String, Object> nacosPayload,
                                      Map<String, Object> higressPayload, Map<String, Object> sourceHashes,
                                      Map<String, Object> targetConstraints) {
        int updated = jdbc.update("""
                update release set version=?, status='candidate', manifest=?::jsonb, manifest_hash=?,
                    nacos_payload=?::jsonb, higress_auth_payload=?::jsonb,
                    source_spec_hashes=?::jsonb, target_constraints=?::jsonb
                where id=? and status='draft'
                """, version, toJson(manifest), manifestHash, toJson(nacosPayload), toJson(higressPayload),
                toJson(sourceHashes), toJson(targetConstraints), id);
        if (updated != 1) {
            throw ApiException.conflict("Release 不是可冻结的 draft", "刷新后重新打开该 Release；已冻结内容不可修改");
        }
    }

    public void transitionRelease(UUID id, String expected, String next) {
        if (jdbc.update("update release set status=? where id=? and status=?", next, id, expected) != 1) {
            throw ApiException.conflict("Release 状态已变化，无法从 " + expected + " 进入 " + next,
                    "刷新 Release 详情后按当前状态继续");
        }
    }

    public void forceReleaseStatus(UUID id, String status) {
        if (jdbc.update("update release set status=? where id=?", status, id) != 1) {
            throw ApiException.notFound("Release " + id);
        }
    }

    public void supersedeOtherReleased(UUID agentId, UUID keepReleaseId) {
        jdbc.update("update release set status='superseded' where agent_id=? and id<>? and status='released'",
                agentId, keepReleaseId);
    }

    // ---------------------------------------------------------------- evidence

    public List<TestReportRow> testReports(UUID releaseId, String layer) {
        if (layer == null) {
            return jdbc.query("select * from test_report where release_id=? order by layer,case_id",
                    this::testReport, releaseId);
        }
        return jdbc.query("select * from test_report where release_id=? and layer=? order by case_id",
                this::testReport, releaseId, layer);
    }

    public void deleteTestReports(UUID releaseId, String layer) {
        jdbc.update("delete from test_report where release_id=? and layer=?", releaseId, layer);
    }

    public void insertTestReport(UUID releaseId, String layer, String caseId, String result,
                                 Map<String, Object> detail, Map<String, Object> modelMeta) {
        jdbc.update("""
                insert into test_report(id,release_id,layer,case_id,result,detail,model_meta)
                values (?,?,?,?,?,?::jsonb,?::jsonb)
                """, UUID.randomUUID(), releaseId, layer, caseId, result, toJson(detail),
                modelMeta == null ? null : toJson(modelMeta));
    }

    public List<GateDecisionRow> gateDecisions(UUID releaseId) {
        return jdbc.query("select * from gate_decision where release_id=? order by rule_id",
                this::gateDecision, releaseId);
    }

    public Optional<GateDecisionRow> gateDecision(UUID releaseId, String ruleId) {
        return optional("""
                select * from gate_decision where release_id=? and rule_id=?
                order by case when verdict='waived' then 0 else 1 end limit 1
                """, this::gateDecision, releaseId, ruleId);
    }

    public void replaceGateDecision(UUID releaseId, String ruleId, String verdict) {
        Optional<GateDecisionRow> existing = gateDecision(releaseId, ruleId);
        if (existing.isPresent() && "waived".equals(existing.get().verdict())) {
            return;
        }
        jdbc.update("delete from gate_decision where release_id=? and rule_id=?", releaseId, ruleId);
        jdbc.update("""
                insert into gate_decision(id,release_id,rule_id,verdict) values (?,?,?,?)
                """, UUID.randomUUID(), releaseId, ruleId, verdict);
    }

    public void waiveGate(UUID releaseId, String ruleId, String by, String reason) {
        jdbc.update("delete from gate_decision where release_id=? and rule_id=?", releaseId, ruleId);
        jdbc.update("""
                insert into gate_decision(id,release_id,rule_id,verdict,waived_by,waiver_reason)
                values (?,?,?,'waived',?,?)
                """, UUID.randomUUID(), releaseId, ruleId, by, reason);
    }

    public void insertApproval(UUID releaseId, String manifestHash, String approver, String decision) {
        jdbc.update("""
                insert into approval(id,release_id,manifest_hash,approver,decision)
                values (?,?,?,?,?)
                """, UUID.randomUUID(), releaseId, manifestHash, approver, decision);
    }

    public List<ApprovalRow> approvals(UUID releaseId) {
        return jdbc.query("select * from approval where release_id=? order by decided_at desc",
                this::approval, releaseId);
    }

    public boolean hasValidApproval(UUID releaseId, String manifestHash) {
        return count("""
                select count(*) from approval
                where release_id=? and manifest_hash=? and decision='approved'
                """, releaseId, manifestHash) > 0;
    }

    public void insertDeployRecord(UUID releaseId, String target, String payloadHash, String result) {
        jdbc.update("""
                insert into deploy_record(id,release_id,target,payload_hash,result) values (?,?,?,?,?)
                """, UUID.randomUUID(), releaseId, target, payloadHash, result);
    }

    // ---------------------------------------------------------------- drift

    public void insertDriftEvent(String scopeType, UUID scopeId, String kind, Map<String, Object> detail) {
        jdbc.update("""
                insert into drift_event(id,scope_type,scope_id,kind,detail,status)
                values (?,?,?,?,?::jsonb,'open')
                """, UUID.randomUUID(), scopeType, scopeId, kind, toJson(detail));
    }

    public List<DriftEventRow> driftEvents(String status) {
        if (status == null) {
            return jdbc.query("select * from drift_event order by detected_at desc", this::driftEvent);
        }
        return jdbc.query("select * from drift_event where status=? order by detected_at desc",
                this::driftEvent, status);
    }

    public boolean hasOpenDrift(String scopeType, UUID scopeId, String kind) {
        return count("""
                select count(*) from drift_event
                where scope_type=? and scope_id=? and kind=? and status='open'
                """, scopeType, scopeId, kind) > 0;
    }

    public void resolveDrift(String scopeType, UUID scopeId, String kind) {
        jdbc.update("""
                update drift_event set status='resolved'
                where scope_type=? and scope_id=? and kind=? and status='open'
                """, scopeType, scopeId, kind);
    }

    public List<String> impactChain(UUID sourceId) {
        return jdbc.query("""
                select distinct t.name tool_name, p.name pack_name, a.name agent_name,
                       coalesce(r.version,'无已发布版本') release_version
                from tool t
                left join pack_tool pt on pt.tool_id=t.id
                left join pack p on p.id=pt.pack_id
                left join agent_pack ap on ap.pack_id=p.id
                left join agent a on a.id=ap.agent_id
                left join release r on r.agent_id=a.id and r.status='released'
                where t.api_source_id=? order by t.name,p.name,a.name
                """, (rs, row) -> String.join(" → ", nonNull(
                "tool:" + rs.getString("tool_name"),
                prefixed("pack:", rs.getString("pack_name")),
                prefixed("agent:", rs.getString("agent_name")),
                prefixed("release:", rs.getString("release_version")))), sourceId);
    }

    // ---------------------------------------------------------------- keys

    public void insertAgentKey(UUID agentId, String keyRef) {
        jdbc.update("insert into agent_key(id,agent_id,key_ref) values (?,?,?)",
                UUID.randomUUID(), agentId, keyRef);
    }

    public List<AgentKeyRow> agentKeys(UUID agentId, boolean activeOnly) {
        String sql = "select * from agent_key where agent_id=?"
                + (activeOnly ? " and revoked_at is null" : "") + " order by rotated_at desc";
        return jdbc.query(sql, this::agentKey, agentId);
    }

    public void revokeAllKeys(UUID agentId) {
        jdbc.update("update agent_key set revoked_at=now() where agent_id=? and revoked_at is null", agentId);
    }

    public void revokeKey(UUID keyId) {
        jdbc.update("update agent_key set revoked_at=now() where id=? and revoked_at is null", keyId);
    }

    // ---------------------------------------------------------------- row mappers

    private DepartmentRow department(ResultSet rs, int row) throws SQLException {
        return new DepartmentRow(uuid(rs, "id"), rs.getString("name"), rs.getString("slug"),
                rs.getString("consumer_group_ref"), instant(rs, "created_at"));
    }

    private AgentRow agent(ResultSet rs, int row) throws SQLException {
        return new AgentRow(uuid(rs, "id"), uuid(rs, "department_id"), rs.getString("name"),
                rs.getString("slug"), rs.getString("description"), rs.getString("forbidden_notes"),
                rs.getString("status"), rs.getString("mcp_url"), instant(rs, "created_at"));
    }

    private IntentRow intent(ResultSet rs, int row) throws SQLException {
        return new IntentRow(uuid(rs, "id"), uuid(rs, "agent_id"), rs.getString("text"),
                rs.getInt("order_no"), rs.getString("source"));
    }

    private ApiSourceRow apiSource(ResultSet rs, int row) throws SQLException {
        return new ApiSourceRow(uuid(rs, "id"), rs.getString("name"), rs.getString("spec_url"),
                rs.getString("spec_hash"), instant(rs, "last_fetched_at"), rs.getString("env_profile"));
    }

    private ToolRow tool(ResultSet rs, int row) throws SQLException {
        return new ToolRow(uuid(rs, "id"), uuid(rs, "api_source_id"), rs.getString("name"),
                rs.getString("description"), map(rs.getString("input_schema")),
                map(rs.getString("request_template")), rs.getString("method"), rs.getString("path"),
                rs.getString("effect"), rs.getString("enrichment_status"),
                strings(rs.getString("output_fields")), strings(rs.getString("sensitivity_flags")),
                rs.getInt("token_cost"), instant(rs, "created_at"));
    }

    private PackRow pack(ResultSet rs, int row) throws SQLException {
        return new PackRow(uuid(rs, "id"), uuid(rs, "department_id"), rs.getString("name"),
                rs.getString("scope"), instant(rs, "created_at"));
    }

    private ProjectionRow projection(ResultSet rs, int row) throws SQLException {
        return new ProjectionRow(uuid(rs, "id"), uuid(rs, "pack_id"), rs.getString("name"),
                map(rs.getString("visibility_condition")));
    }

    private ReleaseRow release(ResultSet rs, int row) throws SQLException {
        return new ReleaseRow(uuid(rs, "id"), uuid(rs, "agent_id"), rs.getString("version"),
                rs.getString("status"), map(rs.getString("manifest")), rs.getString("manifest_hash"),
                map(rs.getString("nacos_payload")), map(rs.getString("higress_auth_payload")),
                map(rs.getString("source_spec_hashes")), map(rs.getString("target_constraints")),
                instant(rs, "created_at"));
    }

    private TestReportRow testReport(ResultSet rs, int row) throws SQLException {
        String modelMeta = rs.getString("model_meta");
        return new TestReportRow(uuid(rs, "id"), uuid(rs, "release_id"), rs.getString("layer"),
                rs.getString("case_id"), rs.getString("result"), map(rs.getString("detail")),
                modelMeta == null ? null : map(modelMeta));
    }

    private GateDecisionRow gateDecision(ResultSet rs, int row) throws SQLException {
        return new GateDecisionRow(uuid(rs, "id"), uuid(rs, "release_id"), rs.getString("rule_id"),
                rs.getString("verdict"), rs.getString("waived_by"), rs.getString("waiver_reason"));
    }

    private ApprovalRow approval(ResultSet rs, int row) throws SQLException {
        return new ApprovalRow(uuid(rs, "id"), uuid(rs, "release_id"), rs.getString("manifest_hash"),
                rs.getString("approver"), instant(rs, "decided_at"), rs.getString("decision"));
    }

    private DriftEventRow driftEvent(ResultSet rs, int row) throws SQLException {
        return new DriftEventRow(uuid(rs, "id"), rs.getString("scope_type"), uuid(rs, "scope_id"),
                rs.getString("kind"), map(rs.getString("detail")), instant(rs, "detected_at"),
                rs.getString("status"));
    }

    private AgentKeyRow agentKey(ResultSet rs, int row) throws SQLException {
        return new AgentKeyRow(uuid(rs, "id"), uuid(rs, "agent_id"), rs.getString("key_ref"),
                instant(rs, "rotated_at"), instant(rs, "revoked_at"));
    }

    // ---------------------------------------------------------------- helpers

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper,
                      String resource, Object... args) {
        return optional(sql, mapper, args).orElseThrow(() -> ApiException.notFound(resource));
    }

    private <T> Optional<T> optional(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("无法序列化 JSONB 字段", error);
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return Map.of();
        }
        try {
            return json.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库 JSONB 不是 object：" + value, error);
        }
    }

    private List<String> strings(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return List.of();
        }
        try {
            return json.readValue(value, STRING_LIST_TYPE);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库 JSONB 不是字符串数组：" + value, error);
        }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static String prefixed(String prefix, String value) {
        return value == null ? null : prefix + value;
    }

    private static List<String> nonNull(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }
}
