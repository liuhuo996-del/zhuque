package com.zhuque.m5_release;

import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * M5 · Release 用例编排：冻结 / 审批 / 回滚。状态迁移一律先过 ReleaseStateMachine。
 */
@Service
public class ReleaseService {

    private final ReleaseStateMachine stateMachine;
    private final ManifestCompiler compiler;
    private final VersionSuggester versionSuggester;

    public ReleaseService(ReleaseStateMachine stateMachine, ManifestCompiler compiler,
                          VersionSuggester versionSuggester) {
        this.stateMachine = stateMachine;
        this.compiler = compiler;
        this.versionSuggester = versionSuggester;
    }

    /**
     * 功能：冻结。draft → candidate，一个事务内完成 ManifestCompiler 的全部产物落库。
     * 返回 releaseId + manifestHash + 建议版本号。
     */
    public UUID freeze(UUID releaseId, String versionOverride) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：审批。写 approval 记录——记录的是 manifest_hash，不是 release_id 本身。
     * 校验流程：任何消费审批的地方（M8 发布前）都要重新比对
     * approval.manifest_hash == release.manifest_hash，不匹配则审批失效。
     */
    public void approve(UUID releaseId, String approver, String expectedManifestHash) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：回滚。取目标旧 Release 的两份 payload 原样重放（委托 M8），零重新计算。
     * 成功后：旧版本重新置 released，被回滚的置 rolled_back。
     * 任何记录都不删除。
     */
    public void rollbackTo(UUID targetReleaseId, String operator) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：拒绝对冻结后 manifest 的修改。
     * 所有 update 入口先调这里：状态在 candidate 及之后 → 抛异常
     * 「manifest 已冻结：要修改请从当前内容开一个新 Release」。
     */
    public void assertMutable(UUID releaseId) {
        throw new UnsupportedOperationException("TODO");
    }
}
