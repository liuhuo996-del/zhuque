package com.zhuque.m8_deploy;

import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * M8 · 双 target 事务发布器。全项目最需要正确性的模块。
 *
 * 事务算法（硬）：
 *   1. precheck：校验 target_constraints（版本、Redis、MCP enable），不满足直接拒绝
 *   2. 快照：依次 read 两个 target 的当前状态存内存快照
 *   3. 依次 apply，顺序写死：先 HigressAuthTarget（鉴权）→ 后 NacosTarget（暴露工具）。
 *      理由：这个顺序保证任何时刻只要工具已暴露，鉴权必然已配好；
 *      若第二步失败，回滚第一步只是撤掉一份还没人用的鉴权配置，无裸奔窗口。
 *      反过来（先工具后鉴权）失败时就是「工具已暴露、鉴权未配」，不可接受
 *   4. 任一 apply 失败 → 用快照对已成功的 target 执行 restore → 整体报失败
 *   5. restore 本身失败 = 最高级告警（状态不一致），落 deploy_record 并停止后续一切自动动作
 *
 * 绝不允许出现「工具已暴露、鉴权未配」的裸奔窗口。
 *
 * 其他：
 * - 每次 apply 落 deploy_record（target、payload_hash、结果、耗时）
 * - 成功后：release → released，上一版 → superseded；
 *   首次发布回调 AgentService.markActiveAfterFirstRelease
 * - 返回 MCP URL + key（key 明文只在这一次响应里出现，见 M10 KeyService）
 */
@Service
public class DualTargetPublisher {

    private final NacosTarget nacosTarget;
    private final HigressAuthTarget higressAuthTarget;
    private final DeployPrecheck precheck;

    public DualTargetPublisher(NacosTarget nacosTarget, HigressAuthTarget higressAuthTarget,
                               DeployPrecheck precheck) {
        this.nacosTarget = nacosTarget;
        this.higressAuthTarget = higressAuthTarget;
        this.precheck = precheck;
    }

    public record PublishResult(String mcpUrl, String plaintextKeyOnceOnly) {}

    /**
     * 功能：发布入口（见类注释的五步事务算法）。
     * 前置校验：release.status == approved 且 approval.manifest_hash 与当前一致。
     */
    public PublishResult publish(UUID releaseId, String operator) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：回滚重放。取旧 Release 的两份 payload 原样 apply（同一套事务算法），
     * 零重新计算。由 M5.rollbackTo 调用。
     */
    public void replay(UUID oldReleaseId, String operator) {
        throw new UnsupportedOperationException("TODO");
    }
}
