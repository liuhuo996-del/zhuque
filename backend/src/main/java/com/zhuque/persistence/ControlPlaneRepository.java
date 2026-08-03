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
                          int tokenCost, Instant createdAt, Instant deprecatedAt,
                          String deprecationReason) {}

    public record PackRow(UUID id, UUID departmentId, String name, String scope, Instant createdAt) {}

    public record ProjectionRow(UUID id, UUID packId, String name, Map<String, Object> visibilityCondition) {}

    public record ReleaseRow(UUID id, UUID agentId, String version, String status,
                             Map<String, Object> manifest, String manifestHash,
                             Map<String, Object> nacosPayload, Map<String, Object> higressAuthPayload,
                             Map<String, Object> sourceSpecHashes, Map<String, Object> targetConstraints,
                             Instant createdAt) {}

    public record TestReportRow(UUID id, UUID releaseId, String layer, String caseId, String result,
                                Map<String, Object> detail, Map<String, Object> modelMeta,
                                UUID testRunId) {
        /** 保留旧构造器，方便现有调用方和测试逐步升级。 */
        public TestReportRow(UUID id, UUID releaseId, String layer, String caseId, String result,
                             Map<String, Object> detail, Map<String, Object> modelMeta) {
            this(id, releaseId, layer, caseId, result, detail, modelMeta, null);
        }
    }

    /** 一层正式测试的最后一次运行状态；用于阻止异步 L1/L2 半途证据进入审批链。 */
    public record TestRunRow(UUID releaseId, String layer, String jobId, int expectedCases, String state,
                             String failure, Instant startedAt, Instant completedAt, UUID id,
                             String evidenceBinding) {
        /** V6 兼容构造器；V7 起每次运行都有不可变 id。 */
        public TestRunRow(UUID releaseId, String layer, String jobId, int expectedCases, String state,
                          String failure, Instant startedAt, Instant completedAt) {
            this(releaseId, layer, jobId, expectedCases, state, failure, startedAt, completedAt, null, "bound");
        }

        /** 为已有调用方保留 V7 初版的九参数构造器。 */
        public TestRunRow(UUID releaseId, String layer, String jobId, int expectedCases, String state,
                          String failure, Instant startedAt, Instant completedAt, UUID id) {
            this(releaseId, layer, jobId, expectedCases, state, failure, startedAt, completedAt, id, "bound");
        }
    }

    public record GateDecisionRow(UUID id, UUID releaseId, String ruleId, String verdict,
                                  String waivedBy, String waiverReason, Map<String, Object> detail,
                                  String ruleSetVersion, Instant decidedAt, String decidedBy) {}

    public record ApprovalRow(UUID id, UUID releaseId, String manifestHash, String approver,
                              Instant decidedAt, String decision) {}

    public record DriftEventRow(UUID id, String scopeType, UUID scopeId, String kind,
                                Map<String, Object> detail, Instant detectedAt, String status) {}

    public record AgentKeyRow(UUID id, UUID agentId, String keyRef, Instant rotatedAt, Instant revokedAt) {}

    public record ResourceLifecycleRow(String resourceType, UUID resourceId, String state,
                                       Instant changedAt, String changedBy, String reason) {}

    public record AuditEventRow(UUID id, String actor, String action, String resourceType,
                                UUID resourceId, Map<String, Object> detail, Instant occurredAt) {}

    public record DeployRecordRow(UUID id, UUID releaseId, String target, String payloadHash,
                                  Instant appliedAt, String result) {}

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

    /**
     * 退役资源只读：详情、Release 历史和审计可以继续查询，但不能再编辑能力、
     * 签发密钥或创建新的 Release。恢复后状态会回到 draft，才重新允许这些操作。
     */
    public AgentRow requireAgentEditable(UUID id) {
        AgentRow agent = requireAgent(id);
        if ("retired".equals(agent.status())) {
            throw ApiException.conflict("已退役数字员工不能继续编辑", "先在回收站恢复为草稿；恢复后请新建 Release 并重新走测试、审批和发布");
        }
        return agent;
    }

    public List<AgentRow> agents(UUID departmentId) {
        if (departmentId == null) {
            return jdbc.query("select * from agent where status <> 'retired' order by created_at desc", this::agent);
        }
        return jdbc.query("select * from agent where department_id = ? and status <> 'retired' order by created_at desc",
                this::agent, departmentId);
    }

    public List<AgentRow> retiredAgents(UUID departmentId) {
        if (departmentId == null) {
            return jdbc.query("select * from agent where status = 'retired' order by created_at desc", this::agent);
        }
        return jdbc.query("select * from agent where department_id = ? and status = 'retired' order by created_at desc",
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

    public boolean agentHasReleases(UUID agentId) {
        return count("select count(*) from release where agent_id = ?", agentId) > 0;
    }

    /**
     * draft Release 只是尚未冻结的编辑草稿，随测试员工一起清理没有审计损失；一旦
     * Release 冻结，或已经产生测试/门禁/审批/部署记录，就属于必须长期保留的证据。
     */
    public boolean agentHasReleaseEvidence(UUID agentId) {
        return count("""
                select count(*) from release r
                where r.agent_id=? and (
                    r.status <> 'draft'
                    or exists (select 1 from test_report t where t.release_id=r.id)
                    or exists (select 1 from gate_decision g where g.release_id=r.id)
                    or exists (select 1 from approval a where a.release_id=r.id)
                    or exists (select 1 from deploy_record d where d.release_id=r.id)
                )
                """, agentId) > 0;
    }

    public boolean agentHasDeployRecords(UUID agentId) {
        return count("""
                select count(*) from deploy_record d join release r on r.id=d.release_id
                where r.agent_id=?
                """, agentId) > 0;
    }

    @Transactional
    public void purgeAgent(UUID agentId) {
        AgentRow agent = requireAgent(agentId);
        if (!"retired".equals(agent.status())) {
            throw ApiException.conflict("只有回收站中的数字员工可以永久删除", "先将数字员工移入回收站");
        }
        if (agentHasReleaseEvidence(agentId)) {
            throw ApiException.conflict("该数字员工已有冻结 Release 或相关证据，不能永久删除",
                    "保留测试、门禁、审批和部署记录用于追查；可继续留在回收站中");
        }
        // 仅删除未冻结且没有任何关联证据的草稿 Release；若发生并发冻结/写证据，
        // 外键或状态条件会使后续删除失败并由事务整体回滚，绝不误删证据。
        jdbc.update("delete from release where agent_id=? and status='draft'", agentId);
        jdbc.update("delete from intent where agent_id=?", agentId);
        jdbc.update("delete from agent_pack where agent_id=?", agentId);
        jdbc.update("delete from agent_key where agent_id=?", agentId);
        jdbc.update("delete from resource_lifecycle where resource_type='agent' and resource_id=?", agentId);
        jdbc.update("delete from agent where id=?", agentId);
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
        requireAgentEditable(agentId);
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
        return jdbc.query("""
                select s.* from api_source s
                where not exists (
                    select 1 from resource_lifecycle l
                    where l.resource_type='api_source' and l.resource_id=s.id and l.state='trashed'
                )
                order by s.name
                """, this::apiSource);
    }

    public List<ApiSourceRow> trashedApiSources() {
        return jdbc.query("""
                select s.* from api_source s
                join resource_lifecycle l on l.resource_type='api_source' and l.resource_id=s.id
                where l.state='trashed' order by l.changed_at desc
                """, this::apiSource);
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
        return jdbc.query("""
                select t.* from tool t
                where t.deprecated_at is null and not exists (
                    select 1 from resource_lifecycle l
                    where l.resource_type='api_source' and l.resource_id=t.api_source_id and l.state='trashed'
                )
                order by t.created_at desc, t.name
                """, this::tool);
    }

    public List<ToolRow> toolsBySource(UUID sourceId) {
        return jdbc.query("select * from tool where api_source_id = ? order by name", this::tool, sourceId);
    }

    public List<ToolRow> toolsByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select t.* from tool t where t.id in (%s)
                and t.deprecated_at is null
                and not exists (
                    select 1 from resource_lifecycle l
                    where l.resource_type='api_source' and l.resource_id=t.api_source_id and l.state='trashed'
                ) order by t.name
                """.formatted(placeholders(ids.size())),
                this::tool, ids.toArray());
    }

    public List<ToolRow> toolsForAgent(UUID agentId) {
        return jdbc.query("""
                select distinct t.* from tool t
                join pack_tool pt on pt.tool_id = t.id
                join agent_pack ap on ap.pack_id = pt.pack_id
                where ap.agent_id = ? and t.deprecated_at is null and not exists (
                    select 1 from resource_lifecycle l
                    where l.resource_type='api_source' and l.resource_id=t.api_source_id and l.state='trashed'
                ) order by t.name
                """, this::tool, agentId);
    }

    /**
     * 能力包中的工具仍是原始配置的一部分，但其 API 来源已被移入回收站。
     * 这类引用不能在冻结新 Release 时被静默跳过，否则 pack 快照会和 tools
     * 快照相互矛盾；调用方应要求用户恢复来源或显式替换/移除工具。
     */
    public List<ToolRow> trashedToolsForAgent(UUID agentId) {
        return jdbc.query("""
                select distinct t.* from tool t
                join pack_tool pt on pt.tool_id=t.id
                join agent_pack ap on ap.pack_id=pt.pack_id
                join resource_lifecycle l on l.resource_type='api_source'
                    and l.resource_id=t.api_source_id and l.state='trashed'
                where ap.agent_id=? order by t.name
                """, this::tool, agentId);
    }

    /**
     * endpoint 从当前 OpenAPI 消失后，旧工具行仍保留给历史 Release 对账，但不能再
     * 静默编入新的 Release。该查询特意不依赖来源是否已进入回收站：两类问题都要
     * 由编译器显式提示，而不是从 manifest 中悄悄遗漏。
     */
    public List<ToolRow> deprecatedToolsForAgent(UUID agentId) {
        return jdbc.query("""
                select distinct t.* from tool t
                join pack_tool pt on pt.tool_id=t.id
                join agent_pack ap on ap.pack_id=pt.pack_id
                where ap.agent_id=? and t.deprecated_at is not null
                order by t.name
                """, this::tool, agentId);
    }

    public List<ToolRow> deprecatedToolsForPack(UUID packId) {
        return jdbc.query("""
                select t.* from tool t
                join pack_tool pt on pt.tool_id=t.id
                where pt.pack_id=? and t.deprecated_at is not null
                order by t.name
                """, this::tool, packId);
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
                    enrichment_status=case when enrichment_status='reviewed' then 'enriched' else enrichment_status end,
                    deprecated_at=null, deprecation_reason=null
                where id=?
                """, toJson(inputSchema), toJson(requestTemplate), toJson(outputFields),
                toJson(sensitivityFlags), tokenCost, toolId) != 1) {
            throw ApiException.notFound("工具 " + toolId);
        }
    }

    /** endpoint 已不再存在于最新 spec。只写弃用元数据，绝不物理删除工具快照。 */
    public void deprecateTool(UUID toolId, String reason) {
        if (jdbc.update("""
                update tool set deprecated_at=coalesce(deprecated_at, now()), deprecation_reason=?
                where id=?
                """, reason == null ? "endpoint 已从最新 OpenAPI 移除" : reason.trim(), toolId) != 1) {
            throw ApiException.notFound("工具 " + toolId);
        }
    }

    public int packReferenceCount(UUID toolId) {
        return count("select count(*) from pack_tool where tool_id = ?", toolId);
    }

    public int sourcePackReferenceCount(UUID sourceId) {
        return count("""
                select count(*) from pack_tool pt join tool t on t.id=pt.tool_id
                where t.api_source_id=?
                """, sourceId);
    }

    public boolean releaseReferencesSource(UUID sourceId) {
        return count("select count(*) from release where jsonb_exists(source_spec_hashes, ?)",
                sourceId.toString()) > 0;
    }

    @Transactional
    public void purgeApiSource(UUID sourceId) {
        requireApiSource(sourceId);
        if (!isTrashed("api_source", sourceId)) {
            throw ApiException.conflict("只有回收站中的 REST API 来源可以永久删除", "先将来源移入回收站");
        }
        if (sourcePackReferenceCount(sourceId) > 0) {
            throw ApiException.conflict("该 REST API 的工具仍被能力包引用，不能永久删除",
                    "先从相关能力包移除工具，再重试");
        }
        if (releaseReferencesSource(sourceId)) {
            throw ApiException.conflict("该 REST API 已进入冻结 Release，不能永久删除",
                    "保留来源用于 spec_hash、审批和发布追查；可继续留在回收站中");
        }
        jdbc.update("delete from tool where api_source_id=?", sourceId);
        jdbc.update("delete from resource_lifecycle where resource_type='api_source' and resource_id=?", sourceId);
        jdbc.update("delete from api_source where id=?", sourceId);
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

    public PackRow requirePack(UUID id) {
        return one("select * from pack where id = ?", this::pack, "能力包 " + id, id);
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
        // List.copyOf 会在这里抢先抛 NPE，导致调用方拿不到可行动的校验提示；先用
        // 可容纳 null 的副本完成显式校验。
        List<UUID> requested = toolIds == null ? List.of() : new ArrayList<>(toolIds);
        if (requested.stream().anyMatch(java.util.Objects::isNull)) {
            throw ApiException.badRequest("能力包包含无效工具 ID", "刷新工具池后重新选择可用工具");
        }
        var unique = new java.util.LinkedHashSet<>(requested);
        if (unique.size() != requested.size()) {
            throw ApiException.badRequest("能力包中不能重复选择同一工具", "移除重复工具后重新保存");
        }
        // toolsByIds 只返回未归档来源、未弃用的工具；先整体校验，避免先 delete 再发现
        // 不可用工具而留下半更新的能力包。
        if (toolsByIds(unique).size() != unique.size()) {
            throw ApiException.conflict("能力包包含已弃用、已归档或不存在的工具",
                    "从普通工具池重新选择当前 OpenAPI 仍提供的工具；历史 Release 不会受影响");
        }
        jdbc.update("delete from pack_tool where pack_id = ?", packId);
        for (UUID toolId : requested) {
            jdbc.update("""
                    insert into pack_tool(pack_id,tool_id,added_by,reason,confidence)
                    values (?,?,?,?,?)
                    """, packId, toolId, addedBy, reasons.get(toolId), confidences.get(toolId));
        }
    }

    public void attachPack(UUID agentId, UUID packId) {
        AgentRow agent = requireAgentEditable(agentId);
        PackRow pack = requirePack(packId);
        if (!agent.departmentId().equals(pack.departmentId())) {
            throw ApiException.conflict("数字员工与能力包不属于同一数字部门", "选择当前部门下的能力包后再绑定");
        }
        List<ToolRow> deprecated = deprecatedToolsForPack(packId);
        if (!deprecated.isEmpty()) {
            throw ApiException.conflict("能力包包含已弃用工具：" + toolNames(deprecated),
                    "从能力包移除或替换已从 OpenAPI 消失的 endpoint 后再绑定；历史 Release 不会受影响");
        }
        jdbc.update("insert into agent_pack(agent_id,pack_id) values (?,?) on conflict do nothing", agentId, packId);
    }

    public List<UUID> toolIdsForPack(UUID packId) {
        return jdbc.query("select tool_id from pack_tool where pack_id=? order by tool_id",
                (rs, row) -> rs.getObject(1, UUID.class), packId);
    }

    public List<UUID> agentIdsForPack(UUID packId) {
        return jdbc.query("select agent_id from agent_pack where pack_id=? order by agent_id",
                (rs, row) -> rs.getObject(1, UUID.class), packId);
    }

    // ---------------------------------------------------------------- releases

    public ReleaseRow requireRelease(UUID id) {
        return one("select * from release where id = ?", this::release, "Release " + id, id);
    }

    /**
     * 对同一 Release 的测试启动、测试完成、门禁判定和审批共用这一把行锁。
     * 这避免了“刚完成测试就被另一请求重跑”或“审批后门禁仍被改写”的竞态。
     * 调用方必须处在 {@link Transactional} 事务中。
     */
    public ReleaseRow lockRelease(UUID id) {
        return one("select * from release where id = ? for update", this::release, "Release " + id, id);
    }

    public List<ReleaseRow> releases(UUID agentId) {
        if (agentId == null) {
            return jdbc.query("select * from release order by created_at desc", this::release);
        }
        return jdbc.query("select * from release where agent_id=? order by created_at desc", this::release, agentId);
    }

    public UUID insertDraftRelease(UUID agentId, String version) {
        requireAgentEditable(agentId);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into release(id,agent_id,version,status) values (?,?,?,'draft')", id, agentId, version);
        return id;
    }

    /**
     * 回收站恢复不会重新暴露旧能力。恢复时间点之前的 Release 无论原先处于什么
     * 状态，都必须作废并新建一份 Release，避免旧 approved 快照被再次发布。
     */
    public void assertReleaseCreatedAfterLastAgentRestore(UUID releaseId) {
        int stale = count("""
                select count(*) from release r
                join resource_lifecycle l on l.resource_type='agent' and l.resource_id=r.agent_id
                where r.id=? and l.state='restored' and r.created_at <= l.changed_at
                """, releaseId);
        if (stale > 0) {
            throw ApiException.conflict("该 Release 创建于数字员工退役/恢复之前，不能再次使用",
                    "恢复后的员工必须新建 Release，并重新完成测试、门禁和人工审批");
        }
    }

    /** 只有 candidate 可写入测试证据；冻结后的历史 Release 永远只读。 */
    public ReleaseRow requireReleaseTestable(UUID releaseId) {
        return validateReleaseTestable(requireRelease(releaseId));
    }

    /** 测试相关的状态判断应在拿到 Release 行锁后完成。 */
    public ReleaseRow lockReleaseTestable(UUID releaseId) {
        return validateReleaseTestable(lockRelease(releaseId));
    }

    private ReleaseRow validateReleaseTestable(ReleaseRow release) {
        requireAgentEditable(release.agentId());
        assertReleaseCreatedAfterLastAgentRestore(release.id());
        if (!"candidate".equals(release.status())) {
            throw ApiException.conflict("只有 candidate Release 可以重跑测试",
                    "已测试或已审批的 Release 证据不可改写；如需变更请新建 Release 并重新冻结");
        }
        return release;
    }

    /** 门禁和豁免只可在候选/已测试阶段更新，批准后成为历史审计证据。 */
    public ReleaseRow requireReleaseGateMutable(UUID releaseId) {
        return validateReleaseGateMutable(requireRelease(releaseId));
    }

    /** 门禁与审批共享 Release 行锁，批准后不允许再追加新的当前判定。 */
    public ReleaseRow lockReleaseGateMutable(UUID releaseId) {
        return validateReleaseGateMutable(lockRelease(releaseId));
    }

    private ReleaseRow validateReleaseGateMutable(ReleaseRow release) {
        requireAgentEditable(release.agentId());
        assertReleaseCreatedAfterLastAgentRestore(release.id());
        if (!java.util.Set.of("candidate", "tested").contains(release.status())) {
            throw ApiException.conflict("当前 Release 的门禁证据已冻结",
                    "只有 candidate 或 tested Release 可以判定或豁免门禁；批准后请新建 Release");
        }
        return release;
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

    /**
     * 开始一层正式测试。每次重跑都创建新的不可变 run；旧报告绝不删除。
     * Release 行锁与局部唯一索引共同保证同一层同时至多一个 running run。
     */
    @Transactional
    public void beginTestRun(UUID releaseId, String layer, String jobId, int expectedCases) {
        if (!java.util.Set.of("L0", "L1", "L2").contains(layer)
                || jobId == null || jobId.isBlank() || expectedCases < 0) {
            throw ApiException.badRequest("正式测试运行参数不合法", "检查测试层、任务标识和预期用例数后重试");
        }
        lockReleaseTestable(releaseId);
        if (hasRunningTestRun(releaseId, layer)) {
            throw ApiException.conflict(layer + " 正式测试仍在运行", "等待当前任务完成或失败后再重跑，避免混合两次测试证据");
        }
        jdbc.update("""
                insert into test_run(id,release_id,layer,job_id,expected_cases,state,started_at,completed_at,failure,
                                     evidence_binding)
                values (?,?,?,?,?, 'running', now(), null, null, 'bound')
                """, UUID.randomUUID(), releaseId, layer, jobId, expectedCases);
    }

    /** 标记完整运行已结束；报告数不匹配会明确标为失败，不能用半途结果完成测试。 */
    @Transactional(noRollbackFor = ApiException.class)
    public void completeTestRun(UUID releaseId, String layer, String jobId) {
        lockReleaseTestable(releaseId);
        // 与 insertTestReport 持有同一 test_run 行锁：完成时的报告数与最终状态是一致快照，
        // 滞后 worker 只能在完成前插入，或在完成后明确被拒绝。
        TestRunRow run = requireTestRunByJobForUpdate(releaseId, layer, jobId);
        if (!"running".equals(run.state()) || !isBoundRun(run)) {
            throw ApiException.conflict("测试任务状态已变化", "刷新任务进度后再继续");
        }
        int actual = testReportCount(run);
        if (actual != run.expectedCases()) {
            String failure = "测试报告覆盖不完整：预期 " + run.expectedCases() + " 条，实际 " + actual + " 条";
            jdbc.update("""
                    update test_run set state='failed', completed_at=now(), failure=?
                    where id=? and state='running'
                    """, failure, run.id());
            throw ApiException.conflict(failure, "重新运行该层正式测试，等待任务完整结束后再完成测试");
        }
        if (jdbc.update("""
                update test_run set state='completed', completed_at=now(), failure=null
                where id=? and state='running'
                """, run.id()) != 1) {
            throw ApiException.conflict("测试任务状态已变化", "刷新任务进度后再继续");
        }
    }

    /** 异步任务异常时把持久状态收敛为 failed，进程重启后也不会把半途证据当成完整测试。 */
    @Transactional
    public void failTestRun(UUID releaseId, String layer, String jobId, String failure) {
        lockRelease(releaseId);
        jdbc.update("""
                update test_run set state='failed', completed_at=now(), failure=?
                where release_id=? and layer=? and job_id=? and state='running'
                """, failure == null || failure.isBlank() ? "测试任务异常中止" : failure,
                releaseId, layer, jobId);
    }

    /** 当前 run 是本层最后一次启动的运行；其报告才是门禁和详情页默认展示的证据。 */
    public Optional<TestRunRow> testRun(UUID releaseId, String layer) {
        return optional("""
                select * from test_run where release_id=? and layer=?
                order by started_at desc, id desc limit 1
                """, this::testRun, releaseId, layer);
    }

    /** 供审计导出使用：不覆盖、不过滤历史测试运行。 */
    public List<TestRunRow> testRunHistory(UUID releaseId) {
        return jdbc.query("select * from test_run where release_id=? order by started_at desc, id desc",
                this::testRun, releaseId);
    }

    public void requireCompletedTestRun(UUID releaseId, String layer) {
        TestRunRow run = testRun(releaseId, layer).orElseThrow(() -> ApiException.conflict(
                layer + " 正式测试尚未运行", "运行并完成 " + layer + " 测试后再继续"));
        // V7 迁移前的报告没有可靠 run_id；可以展示给审计人员，但绝不能把归属不确定的
        // 旧记录当作新的 tested/approved 凭据。
        if (!isBoundRun(run)) {
            throw ApiException.conflict(layer + " 测试证据归属为 legacy/unbound，不能用于审批",
                    "重新运行 " + layer + " 正式测试，生成带不可变 run_id 的完整证据后再继续");
        }
        if (!"completed".equals(run.state())) {
            throw ApiException.conflict(layer + " 正式测试尚未完整结束", run.failure() == null || run.failure().isBlank()
                    ? "等待任务完成；任务失败后请修复并重新运行" : run.failure());
        }
        int actual = testReportCount(run);
        if (actual != run.expectedCases()) {
            throw ApiException.conflict(layer + " 正式测试证据覆盖不完整",
                    "预期 " + run.expectedCases() + " 条、当前 " + actual + " 条；请重新运行该层测试");
        }
    }

    /** L0/L1 是进入 tested 和 approved 前不可绕过的完整证据；L2 可选但不能半途运行。 */
    public void requireCoreTestsCompleted(UUID releaseId) {
        requireCompletedTestRun(releaseId, "L0");
        requireCompletedTestRun(releaseId, "L1");
        if (hasRunningTestRuns(releaseId)) {
            throw ApiException.conflict("仍有正式测试任务在运行",
                    "等待 L0/L1/L2 任务完整结束后再继续，避免半途证据进入审批链");
        }
    }

    /** 原子完成测试：与 beginTestRun 共用同一 Release 锁，杜绝完成后立即被重跑。 */
    @Transactional
    public void completeReleaseTests(UUID releaseId) {
        lockReleaseTestable(releaseId);
        requireCoreTestsCompleted(releaseId);
        transitionRelease(releaseId, "candidate", "tested");
    }

    public boolean hasRunningTestRuns(UUID releaseId) {
        return count("select count(*) from test_run where release_id=? and state='running'", releaseId) > 0;
    }

    private boolean hasRunningTestRun(UUID releaseId, String layer) {
        return count("select count(*) from test_run where release_id=? and layer=? and state='running'",
                releaseId, layer) > 0;
    }

    /** 默认只返回每层最新一次运行的报告，避免历史失败污染当前门禁。 */
    public List<TestReportRow> testReports(UUID releaseId, String layer) {
        if (layer == null) {
            List<TestReportRow> reports = new ArrayList<>();
            for (String currentLayer : List.of("L0", "L1", "L2")) {
                reports.addAll(testReports(releaseId, currentLayer));
            }
            return List.copyOf(reports);
        }
        TestRunRow run = testRun(releaseId, layer).orElse(null);
        if (run == null || !isBoundRun(run)) {
            return jdbc.query("""
                    select * from test_report where release_id=? and layer=? and test_run_id is null order by case_id
                    """, this::testReport, releaseId, layer);
        }
        return jdbc.query("select * from test_report where test_run_id=? order by case_id",
                this::testReport, run.id());
    }

    /** 历史证据仅供审计/追查：与当前测试视图分开，永不被重跑删除。 */
    public List<TestReportRow> testReportHistory(UUID releaseId, String layer) {
        if (layer == null) {
            return jdbc.query("select * from test_report where release_id=? order by layer, id", this::testReport, releaseId);
        }
        return jdbc.query("select * from test_report where release_id=? and layer=? order by id",
                this::testReport, releaseId, layer);
    }

    /**
     * 新代码必须传入 jobId，以避免一个滞后的异步 worker 把报告写进随后重跑的新 run。
     */
    @Transactional
    public void insertTestReport(UUID releaseId, String layer, String jobId, String caseId, String result,
                                 Map<String, Object> detail, Map<String, Object> modelMeta) {
        // 与 begin/complete/fail 统一锁顺序：release → test_run。否则 INSERT 的 release
        // 外键 KEY SHARE 与 completeTestRun 的 FOR UPDATE 会形成反向等待。
        lockReleaseTestable(releaseId);
        TestRunRow run = requireTestRunByJobForUpdate(releaseId, layer, jobId);
        if (!"running".equals(run.state()) || !isBoundRun(run)) {
            throw ApiException.conflict("测试任务状态已变化", "测试已结束或被替换，不能继续写入其证据");
        }
        jdbc.update("""
                insert into test_report(id,test_run_id,release_id,layer,case_id,result,detail,model_meta)
                values (?,?,?,?,?,?,?::jsonb,?::jsonb)
                """, UUID.randomUUID(), run.id(), releaseId, layer, caseId, result, toJson(detail),
                modelMeta == null ? null : toJson(modelMeta));
    }

    /**
     * 兼容旧调用方。正式 L0/L1/L2 已改用带 jobId 的重载；这里仅会绑定当前唯一 running run。
     */
    @Deprecated(forRemoval = false)
    @Transactional
    public void insertTestReport(UUID releaseId, String layer, String caseId, String result,
                                 Map<String, Object> detail, Map<String, Object> modelMeta) {
        TestRunRow run = optional("""
                select * from test_run where release_id=? and layer=? and state='running'
                order by started_at desc, id desc limit 1
                """, this::testRun, releaseId, layer).orElseThrow(() -> ApiException.conflict(
                        layer + " 正式测试运行不存在或已结束", "重新启动该层正式测试后再写入证据"));
        insertTestReport(releaseId, layer, run.jobId(), caseId, result, detail, modelMeta);
    }

    private TestRunRow requireTestRunByJob(UUID releaseId, String layer, String jobId) {
        return optional("""
                select * from test_run where release_id=? and layer=? and job_id=?
                order by started_at desc, id desc limit 1
                """, this::testRun, releaseId, layer, jobId).orElseThrow(() -> ApiException.conflict(
                        layer + " 正式测试运行不存在", "重新启动该层正式测试"));
    }

    private TestRunRow requireTestRunByJobForUpdate(UUID releaseId, String layer, String jobId) {
        return optional("""
                select * from test_run where release_id=? and layer=? and job_id=?
                order by started_at desc, id desc limit 1 for update
                """, this::testRun, releaseId, layer, jobId).orElseThrow(() -> ApiException.conflict(
                        layer + " 正式测试运行不存在", "重新启动该层正式测试"));
    }

    private int testReportCount(TestRunRow run) {
        if (!isBoundRun(run)) {
            return count("select count(*) from test_report where release_id=? and layer=? and test_run_id is null",
                    run.releaseId(), run.layer());
        }
        return count("select count(*) from test_report where test_run_id=?", run.id());
    }

    private static boolean isBoundRun(TestRunRow run) {
        return run != null && run.id() != null && "bound".equals(run.evidenceBinding());
    }

    /** Release 详情只展示每条规则的当前判定；完整历史由 gateDecisionHistory 提供。 */
    public List<GateDecisionRow> gateDecisions(UUID releaseId) {
        return jdbc.query("""
                select distinct on (rule_id) * from gate_decision where release_id=?
                order by rule_id, decided_at desc, id desc
                """, this::gateDecision, releaseId);
    }

    public List<GateDecisionRow> gateDecisionHistory(UUID releaseId) {
        return jdbc.query("select * from gate_decision where release_id=? order by decided_at desc, id desc",
                this::gateDecision, releaseId);
    }

    public Optional<GateDecisionRow> gateDecision(UUID releaseId, String ruleId) {
        return optional("""
                select * from gate_decision where release_id=? and rule_id=?
                order by decided_at desc, id desc limit 1
                """, this::gateDecision, releaseId, ruleId);
    }

    /** 追加一条带规则版本、判定详情与责任人的门禁审计记录；不覆盖历史。 */
    public void appendGateDecision(UUID releaseId, String ruleId, String verdict, Map<String, Object> detail,
                                   String ruleSetVersion, String decidedBy) {
        jdbc.update("""
                insert into gate_decision(id,release_id,rule_id,verdict,detail,rule_set_version,decided_by)
                values (?,?,?,?,?::jsonb,?,?)
                """, UUID.randomUUID(), releaseId, ruleId, verdict, toJson(detail == null ? Map.of() : detail),
                ruleSetVersion == null || ruleSetVersion.isBlank() ? "unknown" : ruleSetVersion,
                decidedBy == null || decidedBy.isBlank() ? "system" : decidedBy.trim());
    }

    /** 兼容旧调用；语义从 replace 改为追加，确保审计历史不可被悄悄改写。 */
    public void replaceGateDecision(UUID releaseId, String ruleId, String verdict) {
        Optional<GateDecisionRow> existing = gateDecision(releaseId, ruleId);
        if (existing.isPresent() && "waived".equals(existing.get().verdict())) {
            return;
        }
        appendGateDecision(releaseId, ruleId, verdict, Map.of(), "legacy-compatible", "system");
    }

    public void waiveGate(UUID releaseId, String ruleId, String by, String reason,
                          Map<String, Object> detail, String ruleSetVersion) {
        jdbc.update("""
                insert into gate_decision(id,release_id,rule_id,verdict,waived_by,waiver_reason,
                                          detail,rule_set_version,decided_by)
                values (?,?,?,'waived',?,?,?::jsonb,?,?)
                """, UUID.randomUUID(), releaseId, ruleId, by, reason,
                toJson(detail == null ? Map.of() : detail),
                ruleSetVersion == null || ruleSetVersion.isBlank() ? "unknown" : ruleSetVersion, by);
    }

    public void waiveGate(UUID releaseId, String ruleId, String by, String reason) {
        waiveGate(releaseId, ruleId, by, reason, Map.of("message", "人工豁免"), "legacy-compatible");
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

    public List<DeployRecordRow> deployRecords(UUID releaseId) {
        return jdbc.query("select * from deploy_record where release_id=? order by applied_at",
                this::deployRecord, releaseId);
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

    // ---------------------------------------------------------------- trash / audit support metadata

    public void trashResource(String resourceType, UUID resourceId, String actor, String reason) {
        jdbc.update("""
                insert into resource_lifecycle(resource_type,resource_id,state,changed_at,changed_by,reason)
                values (?,?,'trashed',now(),?,?)
                on conflict (resource_type,resource_id) do update
                set state='trashed',changed_at=now(),changed_by=excluded.changed_by,reason=excluded.reason
                """, resourceType, resourceId, actor, reason == null ? "" : reason);
    }

    public void restoreResource(String resourceType, UUID resourceId, String actor) {
        jdbc.update("""
                update resource_lifecycle set state='restored', changed_at=now(), changed_by=?
                where resource_type=? and resource_id=?
                """, actor == null || actor.isBlank() ? "console-user" : actor.trim(), resourceType, resourceId);
    }

    /** 兼容没有显式操作人的旧调用；新调用应传入真实可审计身份。 */
    public void restoreResource(String resourceType, UUID resourceId) {
        restoreResource(resourceType, resourceId, "console-user");
    }

    public boolean isTrashed(String resourceType, UUID resourceId) {
        return count("""
                select count(*) from resource_lifecycle
                where resource_type=? and resource_id=? and state='trashed'
                """, resourceType, resourceId) > 0;
    }

    public Optional<ResourceLifecycleRow> lifecycle(String resourceType, UUID resourceId) {
        return optional("select * from resource_lifecycle where resource_type=? and resource_id=?",
                this::resourceLifecycle, resourceType, resourceId);
    }

    public void insertAuditEvent(String actor, String action, String resourceType,
                                 UUID resourceId, Map<String, Object> detail) {
        jdbc.update("""
                insert into audit_event(id,actor,action,resource_type,resource_id,detail)
                values (?,?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), actor, action, resourceType, resourceId,
                toJson(detail == null ? Map.of() : detail));
    }

    public List<AuditEventRow> auditEvents(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("select * from audit_event order by occurred_at desc limit ?",
                this::auditEvent, safeLimit);
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
                rs.getInt("token_cost"), instant(rs, "created_at"), instant(rs, "deprecated_at"),
                rs.getString("deprecation_reason"));
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
                modelMeta == null ? null : map(modelMeta), uuid(rs, "test_run_id"));
    }

    private TestRunRow testRun(ResultSet rs, int row) throws SQLException {
        return new TestRunRow(uuid(rs, "release_id"), rs.getString("layer"), rs.getString("job_id"),
                rs.getInt("expected_cases"), rs.getString("state"), rs.getString("failure"),
                instant(rs, "started_at"), instant(rs, "completed_at"), uuid(rs, "id"),
                rs.getString("evidence_binding"));
    }

    private GateDecisionRow gateDecision(ResultSet rs, int row) throws SQLException {
        return new GateDecisionRow(uuid(rs, "id"), uuid(rs, "release_id"), rs.getString("rule_id"),
                rs.getString("verdict"), rs.getString("waived_by"), rs.getString("waiver_reason"),
                map(rs.getString("detail")), rs.getString("rule_set_version"), instant(rs, "decided_at"),
                rs.getString("decided_by"));
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

    private ResourceLifecycleRow resourceLifecycle(ResultSet rs, int row) throws SQLException {
        return new ResourceLifecycleRow(rs.getString("resource_type"), uuid(rs, "resource_id"),
                rs.getString("state"), instant(rs, "changed_at"), rs.getString("changed_by"),
                rs.getString("reason"));
    }

    private AuditEventRow auditEvent(ResultSet rs, int row) throws SQLException {
        return new AuditEventRow(uuid(rs, "id"), rs.getString("actor"), rs.getString("action"),
                rs.getString("resource_type"), uuid(rs, "resource_id"), map(rs.getString("detail")),
                instant(rs, "occurred_at"));
    }

    private DeployRecordRow deployRecord(ResultSet rs, int row) throws SQLException {
        return new DeployRecordRow(uuid(rs, "id"), uuid(rs, "release_id"), rs.getString("target"),
                rs.getString("payload_hash"), instant(rs, "applied_at"), rs.getString("result"));
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
        // V1 曾把这两个 Release object 字段的默认值写成 []。保留对已经
        // 初始化过的数据库的读取兼容；新默认值在 V4 统一为 {}。
        if ("[]".equals(value.trim())) {
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

    private static String toolNames(List<ToolRow> tools) {
        String names = tools.stream().map(ToolRow::name).limit(8)
                .collect(java.util.stream.Collectors.joining("、"));
        return tools.size() > 8 ? names + " 等 " + tools.size() + " 个工具" : names;
    }
}
