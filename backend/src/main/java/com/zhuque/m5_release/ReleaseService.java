package com.zhuque.m5_release;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zhuque.common.ApiException;
import com.zhuque.m7_gate.GateEngine;
import com.zhuque.m8_deploy.DualTargetPublisher;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M5 · Release 用例编排：冻结 / 审批 / 回滚。状态迁移一律先过 ReleaseStateMachine。
 */
@Service
public class ReleaseService {

    private final ReleaseStateMachine stateMachine;
    private final ManifestCompiler compiler;
    private final VersionSuggester versionSuggester;
    private final ControlPlaneRepository repository;
    private final DualTargetPublisher publisher;
    private final GateEngine gates;

    public ReleaseService(ReleaseStateMachine stateMachine, ManifestCompiler compiler,
                          VersionSuggester versionSuggester, ControlPlaneRepository repository,
                          DualTargetPublisher publisher, GateEngine gates) {
        this.stateMachine = stateMachine;
        this.compiler = compiler;
        this.versionSuggester = versionSuggester;
        this.repository = repository;
        this.publisher = publisher;
        this.gates = gates;
    }

    /**
     * 功能：冻结。draft → candidate，一个事务内完成 ManifestCompiler 的全部产物落库。
     * 返回 releaseId + manifestHash + 建议版本号。
     */
    @Transactional
    public UUID freeze(UUID releaseId, String versionOverride) {
        var release = repository.requireRelease(releaseId);
        repository.requireAgentEditable(release.agentId());
        repository.assertReleaseCreatedAfterLastAgentRestore(releaseId);
        stateMachine.assertTransition(release.status(), "candidate");
        var compiled = compiler.compile(release.agentId());
        var previous = repository.previousRelease(release.agentId(), releaseId).orElse(null);
        var suggestion = versionSuggester.suggest(previous == null ? null : previous.manifest(),
                compiled.manifest(), previous == null ? null : previous.version());
        String version = versionOverride == null || versionOverride.isBlank()
                ? suggestion.suggested() : versionOverride.trim();
        if (!version.matches("^v\\d+(?:\\.\\d+){0,2}$")) {
            throw ApiException.badRequest("版本号格式不合法", "使用 v1、v1.2 或 v1.2.3 格式");
        }
        repository.updateCompiledRelease(releaseId, version, compiled.manifest(), compiled.manifestHash(),
                compiled.nacosPayload(), compiled.higressAuthPayload(), compiled.sourceSpecHashes(),
                compiled.targetConstraints());
        return releaseId;
    }

    /**
     * 功能：审批。写 approval 记录——记录的是 manifest_hash，不是 release_id 本身。
     * 校验流程：任何消费审批的地方（M8 发布前）都要重新比对
     * approval.manifest_hash == release.manifest_hash，不匹配则审批失效。
     */
    @Transactional
    public void approve(UUID releaseId, String approver, String expectedManifestHash) {
        // 与开始/完成测试、门禁判定共用 Release 行锁。审批一旦通过，任何后续重跑或
        // 门禁改写都会在状态检查处被拒绝，而不会产生“批准中的半途证据”。
        var release = repository.lockRelease(releaseId);
        repository.requireAgentEditable(release.agentId());
        repository.assertReleaseCreatedAfterLastAgentRestore(releaseId);
        stateMachine.assertTransition(release.status(), "approved");
        if (approver == null || approver.isBlank()) {
            throw ApiException.badRequest("审批人不能为空", "使用当前登录人的可审计身份审批");
        }
        if (expectedManifestHash == null || !expectedManifestHash.equals(release.manifestHash())) {
            throw ApiException.conflict("审批内容已变化，manifest_hash 不匹配", "刷新证据包并重新审批");
        }
        // GateEngine 也会复核；这里保留独立的硬性防线，避免未来门禁规则调整时放宽
        // L0/L1 完整运行和“无 running run”的审批前提。
        repository.requireCoreTestsCompleted(releaseId);
        if (!gates.canApprove(releaseId)) {
            throw ApiException.conflict("Release 仍有未通过的 BLOCK 门禁", "修复问题并重跑门禁，或由责任人填写理由豁免");
        }
        repository.insertApproval(releaseId, release.manifestHash(), approver.trim(), "approved");
        repository.transitionRelease(releaseId, "tested", "approved");
    }

    /**
     * 功能：回滚。取目标旧 Release 的两份 payload 原样重放（委托 M8），零重新计算。
     * 成功后：旧版本重新置 released，被回滚的置 rolled_back。
     * 任何记录都不删除。
     */
    public void rollbackTo(UUID targetReleaseId, String operator) {
        var target = repository.requireRelease(targetReleaseId);
        if (!java.util.Set.of("superseded", "released").contains(target.status())) {
            throw ApiException.conflict("只能回滚到曾经发布过的完整快照", "选择 superseded 或当前 released 版本");
        }
        var current = repository.releasedForAgent(target.agentId()).orElse(null);
        if (current != null && current.id().equals(target.id())) {
            throw ApiException.badRequest("目标版本已经在线", "选择更早的已发布版本");
        }
        publisher.replay(targetReleaseId, operator);
        if (current != null) {
            repository.forceReleaseStatus(current.id(), "rolled_back");
        }
        repository.forceReleaseStatus(target.id(), "released");
    }

    /**
     * 功能：拒绝对冻结后 manifest 的修改。
     * 所有 update 入口先调这里：状态在 candidate 及之后 → 抛异常
     * 「manifest 已冻结：要修改请从当前内容开一个新 Release」。
     */
    public void assertMutable(UUID releaseId) {
        var release = repository.requireRelease(releaseId);
        if (stateMachine.isFrozen(release.status())) {
            throw ApiException.conflict("manifest 已冻结", "要修改请从当前内容开一个新 Release");
        }
    }
}
