package com.zhuque;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.zhuque.common.ApiException;
import com.zhuque.common.JobProgress;
import com.zhuque.common.JobRegistry;
import com.zhuque.m10_org.AgentLifecycleService;
import com.zhuque.m10_org.DepartmentService;
import com.zhuque.m10_org.KeyService;
import com.zhuque.m3_matching.IntentMatcher;
import com.zhuque.m5_release.ReleaseService;
import com.zhuque.m6_testing.L0StaticChecker;
import com.zhuque.m6_testing.L1ContractTester;
import com.zhuque.m6_testing.L2AgentEvaluator;
import com.zhuque.m7_gate.GateEngine;
import com.zhuque.m8_deploy.DeployPrecheck;
import com.zhuque.m8_deploy.DualTargetPublisher;
import com.zhuque.m9_drift.ConfigDriftDetector;
import com.zhuque.persistence.ControlPlaneRepository;

/** v1 控制面用例 HTTP 入口；只生成配置与证据，不处理任何 MCP 运行时消息。 */
@RestController
@RequestMapping("/api")
public class ControlPlaneController {

    private final ControlPlaneRepository repository;
    private final DepartmentService departments;
    private final IntentMatcher matcher;
    private final ReleaseService releases;
    private final L0StaticChecker l0;
    private final L1ContractTester l1;
    private final L2AgentEvaluator l2;
    private final GateEngine gates;
    private final DualTargetPublisher publisher;
    private final DeployPrecheck precheck;
    private final KeyService keys;
    private final AgentLifecycleService lifecycle;
    private final ConfigDriftDetector drift;
    private final JobRegistry jobs;

    public ControlPlaneController(ControlPlaneRepository repository, DepartmentService departments,
                                  IntentMatcher matcher, ReleaseService releases, L0StaticChecker l0,
                                  L1ContractTester l1, L2AgentEvaluator l2, GateEngine gates,
                                  DualTargetPublisher publisher, DeployPrecheck precheck, KeyService keys,
                                  AgentLifecycleService lifecycle, ConfigDriftDetector drift, JobRegistry jobs) {
        this.repository = repository;
        this.departments = departments;
        this.matcher = matcher;
        this.releases = releases;
        this.l0 = l0;
        this.l1 = l1;
        this.l2 = l2;
        this.gates = gates;
        this.publisher = publisher;
        this.precheck = precheck;
        this.keys = keys;
        this.lifecycle = lifecycle;
        this.drift = drift;
        this.jobs = jobs;
    }

    @GetMapping("/departments")
    public List<ControlPlaneRepository.DepartmentRow> departments() {
        return repository.departments();
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> createDepartment(@RequestBody DepartmentInput input) {
        return Map.of("id", departments.create(input.name(), input.slug()));
    }

    @GetMapping("/packs")
    public List<ControlPlaneRepository.PackRow> packs(@RequestParam(required = false) UUID department) {
        return repository.packs(department);
    }

    @PostMapping("/packs")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> createPack(@RequestBody PackInput input) {
        if (input.departmentId() == null || input.name() == null || input.name().isBlank()) {
            throw ApiException.badRequest("能力包缺少部门或名称", "选择部门并填写名称");
        }
        UUID id = repository.createPack(input.departmentId(), input.name().trim(), "department",
                input.projectionName() == null ? "default" : input.projectionName(),
                input.visibilityCondition() == null ? Map.of() : input.visibilityCondition());
        return Map.of("id", id);
    }

    @PutMapping("/packs/{id}/tools")
    public void replacePackTools(@PathVariable UUID id, @RequestBody PackToolsInput input) {
        List<UUID> toolIds = input.toolIds() == null ? List.of() : input.toolIds();
        repository.replacePackTools(id, toolIds, input.addedBy() == null ? "human" : input.addedBy(),
                input.reasons() == null ? Map.of() : input.reasons(),
                input.confidences() == null ? Map.of() : input.confidences());
    }

    @PostMapping("/agents/{agentId}/packs/{packId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attachPack(@PathVariable UUID agentId, @PathVariable UUID packId) {
        repository.requireAgent(agentId);
        repository.attachPack(agentId, packId);
    }

    @PostMapping("/matches")
    public IntentMatcher.MatchResult match(@RequestBody MatchInput input) {
        return matcher.match(input.intentIds(), input.forbiddenRules(), input.candidateToolIds());
    }

    @GetMapping("/releases")
    public List<ControlPlaneRepository.ReleaseRow> releaseList(@RequestParam(required = false) UUID agent) {
        return repository.releases(agent);
    }

    @GetMapping("/releases/{id}")
    public ReleaseDetail release(@PathVariable UUID id) {
        return new ReleaseDetail(repository.requireRelease(id), repository.testReports(id, null),
                repository.gateDecisions(id), repository.approvals(id));
    }

    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> createRelease(@RequestBody CreateReleaseInput input) {
        return Map.of("id", repository.insertDraftRelease(input.agentId(), "draft"));
    }

    @PostMapping("/releases/{id}/freeze")
    public ControlPlaneRepository.ReleaseRow freeze(@PathVariable UUID id, @RequestBody(required = false) VersionInput input) {
        releases.freeze(id, input == null ? null : input.version());
        return repository.requireRelease(id);
    }

    @PostMapping("/releases/{id}/tests/l0")
    public List<L0StaticChecker.L0Case> runL0(@PathVariable UUID id) {
        return l0.run(id);
    }

    @PostMapping("/releases/{id}/tests/l1")
    public Map<String, String> runL1(@PathVariable UUID id, @RequestParam(defaultValue = "mock") String target) {
        return Map.of("jobId", l1.run(id, target));
    }

    @PostMapping("/releases/{id}/tests/l2")
    public Map<String, String> runL2(@PathVariable UUID id, @RequestBody L2AgentEvaluator.L2Config config) {
        return Map.of("jobId", l2.run(id, config));
    }

    @PostMapping("/releases/{id}/tests/complete")
    public void completeTests(@PathVariable UUID id) {
        var release = repository.requireRelease(id);
        if (!"candidate".equals(release.status())) {
            throw ApiException.conflict("只有 candidate 可以完成测试", "刷新 Release 状态");
        }
        if (repository.testReports(id, "L0").isEmpty() || repository.testReports(id, "L1").isEmpty()) {
            throw ApiException.conflict("L0/L1 证据不完整", "至少完成 L0 和默认 mock L1");
        }
        repository.transitionRelease(id, "candidate", "tested");
    }

    @PostMapping("/releases/{id}/gates")
    public GateEngine.GateSummary judge(@PathVariable UUID id) {
        return gates.judge(id);
    }

    @PostMapping("/releases/{id}/gates/{ruleId}/waive")
    public void waive(@PathVariable UUID id, @PathVariable String ruleId, @RequestBody WaiverInput input) {
        gates.waive(id, ruleId, input.waivedBy(), input.reason());
    }

    @PostMapping("/releases/{id}/approve")
    public void approve(@PathVariable UUID id, @RequestBody ApprovalInput input) {
        releases.approve(id, input.approver(), input.manifestHash());
    }

    /** 唯一发布 HTTP 入口，必须由用户显式点击触发，不被任何后台任务调用。 */
    @PostMapping("/releases/{id}/publish")
    public DualTargetPublisher.PublishResult publish(@PathVariable UUID id, @RequestBody OperatorInput input) {
        return publisher.publish(id, input.operator());
    }

    @PostMapping("/releases/{id}/rollback")
    public void rollback(@PathVariable UUID id, @RequestBody OperatorInput input) {
        releases.rollbackTo(id, input.operator());
    }

    @GetMapping("/deploy/precheck")
    public List<DeployPrecheck.CheckItem> precheck() {
        return precheck.checkEnvironment();
    }

    @GetMapping("/jobs/{id}/progress")
    public JobProgress job(@PathVariable String id) {
        return jobs.get(id);
    }

    @PostMapping("/agents/{id}/keys")
    public KeyService.IssuedKey issueKey(@PathVariable UUID id) {
        return keys.issue(id);
    }

    @PostMapping("/agents/{id}/keys/rotate")
    public KeyService.IssuedKey rotateKey(@PathVariable UUID id) {
        return keys.rotate(id);
    }

    @PostMapping("/agents/{id}/suspend")
    public void suspend(@PathVariable UUID id, @RequestBody OperatorInput input) {
        lifecycle.suspend(id, input.operator());
    }

    @PostMapping("/agents/{id}/resume")
    public Map<String, String> resume(@PathVariable UUID id, @RequestBody OperatorInput input) {
        return Map.of("plaintextKeyOnceOnly", lifecycle.resume(id, input.operator()));
    }

    @PostMapping("/agents/{id}/retire")
    public void retire(@PathVariable UUID id, @RequestBody OperatorInput input) {
        lifecycle.retire(id, input.operator());
    }

    @PostMapping("/agents/{id}/drift/repair")
    public void repairDrift(@PathVariable UUID id, @RequestBody OperatorInput input) {
        drift.repairByReplay(id, input.operator());
    }

    public record DepartmentInput(String name, String slug) {}
    public record PackInput(UUID departmentId, String name, String projectionName,
                            Map<String, Object> visibilityCondition) {}
    public record PackToolsInput(List<UUID> toolIds, String addedBy, Map<UUID, String> reasons,
                                 Map<UUID, Double> confidences) {}
    public record MatchInput(List<UUID> intentIds, List<String> forbiddenRules, List<UUID> candidateToolIds) {}
    public record CreateReleaseInput(UUID agentId) {}
    public record VersionInput(String version) {}
    public record WaiverInput(String waivedBy, String reason) {}
    public record ApprovalInput(String approver, String manifestHash) {}
    public record OperatorInput(String operator) {}
    public record ReleaseDetail(ControlPlaneRepository.ReleaseRow release,
                                List<ControlPlaneRepository.TestReportRow> tests,
                                List<ControlPlaneRepository.GateDecisionRow> gates,
                                List<ControlPlaneRepository.ApprovalRow> approvals) {}
}
