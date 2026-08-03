package com.zhuque.m8_deploy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;
import com.zhuque.m2_agent.AgentService;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.ReleaseRow;

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
    private final ControlPlaneRepository repository;
    private final AgentService agentService;

    public DualTargetPublisher(NacosTarget nacosTarget, HigressAuthTarget higressAuthTarget,
                               DeployPrecheck precheck, ControlPlaneRepository repository,
                               AgentService agentService) {
        this.nacosTarget = nacosTarget;
        this.higressAuthTarget = higressAuthTarget;
        this.precheck = precheck;
        this.repository = repository;
        this.agentService = agentService;
    }

    public record PublishResult(String mcpUrl, String plaintextKeyOnceOnly) {}

    /**
     * 功能：发布入口（见类注释的五步事务算法）。
     * 前置校验：release.status == approved 且 approval.manifest_hash 与当前一致。
     */
    public PublishResult publish(UUID releaseId, String operator) {
        ReleaseRow release = repository.requireRelease(releaseId);
        assertAgentCanPublish(release);
        if (!"approved".equals(release.status())) {
            throw ApiException.conflict("Release 尚未 approved，不能发布", "完成测试、门禁和人工审批后再发布");
        }
        if (!repository.hasValidApproval(releaseId, release.manifestHash())) {
            throw ApiException.conflict("审批已失效或未绑定当前 manifest_hash", "刷新证据包并重新人工审批");
        }
        assertPrecheck(releaseId);
        deploySnapshot(release);
        repository.transitionRelease(releaseId, "approved", "released");
        repository.supersedeOtherReleased(release.agentId(), releaseId);
        if ("draft".equals(repository.requireAgent(release.agentId()).status())) {
            agentService.markActiveAfterFirstRelease(release.agentId());
        }
        return new PublishResult(repository.requireAgent(release.agentId()).mcpUrl(), null);
    }

    /**
     * 功能：回滚重放。取旧 Release 的两份 payload 原样 apply（同一套事务算法），
     * 零重新计算。由 M5.rollbackTo 调用。
     */
    public void replay(UUID oldReleaseId, String operator) {
        ReleaseRow release = repository.requireRelease(oldReleaseId);
        assertAgentCanPublish(release);
        if (release.nacosPayload().isEmpty() || release.higressAuthPayload().isEmpty()) {
            throw ApiException.conflict("旧 Release 不含完整双目标快照", "只能选择曾成功冻结并发布的版本回滚");
        }
        assertPrecheck(oldReleaseId);
        deploySnapshot(release);
    }

    private void assertPrecheck(UUID releaseId) {
        List<DeployPrecheck.CheckItem> failures = precheck.checkFor(releaseId).stream()
                .filter(item -> !item.ok()).toList();
        if (!failures.isEmpty()) {
            String what = failures.stream().map(item -> item.name() + "=" + item.current())
                    .collect(java.util.stream.Collectors.joining("；"));
            String fix = failures.stream().map(DeployPrecheck.CheckItem::fix).distinct()
                    .collect(java.util.stream.Collectors.joining("；"));
            throw ApiException.unavailable("发布前置检查失败：" + what, fix);
        }
    }

    private void assertAgentCanPublish(ReleaseRow release) {
        var agent = repository.requireAgent(release.agentId());
        repository.assertReleaseCreatedAfterLastAgentRestore(release.id());
        if ("retired".equals(agent.status())) {
            throw ApiException.conflict("已退役数字员工不能发布或回滚历史 Release",
                    "先在回收站恢复为草稿，并新建 Release 重新完成测试、门禁和人工审批");
        }
        if ("suspended".equals(agent.status())) {
            throw ApiException.conflict("已暂停数字员工不能发布或回滚", "先恢复数字员工并签发新密钥，再由人工继续发布");
        }
    }

    private void deploySnapshot(ReleaseRow release) {
        String serviceName = required(release.higressAuthPayload(), "mcpServerName");
        Map<String, Object> authBefore = higressAuthTarget.read(serviceName);
        Map<String, Object> nacosBefore = nacosTarget.read(serviceName);
        boolean authApplied = false;
        boolean nacosApplied = false;
        RuntimeException failure;
        try {
            authApplied = true;
            higressAuthTarget.apply(release.id(), release.higressAuthPayload());
            repository.insertDeployRecord(release.id(), higressAuthTarget.name(),
                    CanonicalJson.sha256(release.higressAuthPayload()), "success");
            nacosApplied = true;
            nacosTarget.apply(release.id(), release.nacosPayload());
            repository.insertDeployRecord(release.id(), nacosTarget.name(),
                    CanonicalJson.sha256(release.nacosPayload()), "success");
            return;
        } catch (RuntimeException error) {
            failure = error;
        }

        List<String> restoreFailures = new ArrayList<>();
        if (nacosApplied) {
            try {
                nacosTarget.restore(serviceName, nacosBefore);
            } catch (RuntimeException error) {
                restoreFailures.add("Nacos 恢复失败：" + error.getMessage());
            }
        }
        if (authApplied) {
            try {
                higressAuthTarget.restore(serviceName, authBefore);
            } catch (RuntimeException error) {
                restoreFailures.add("Higress 鉴权恢复失败：" + error.getMessage());
            }
        }
        String result = restoreFailures.isEmpty() ? "failed_rolled_back"
                : "critical_inconsistent:" + String.join(";", restoreFailures);
        repository.insertDeployRecord(release.id(), "nacos", CanonicalJson.sha256(release.nacosPayload()), result);
        repository.insertDeployRecord(release.id(), "higress_auth",
                CanonicalJson.sha256(release.higressAuthPayload()), result);
        if (!restoreFailures.isEmpty()) {
            throw ApiException.unavailable("双目标发布失败且恢复不完整：" + String.join("；", restoreFailures),
                    "立即停止发布并人工对账 Nacos 与网关鉴权状态");
        }
        throw ApiException.unavailable("双目标发布失败，已恢复原快照：" + failure.getMessage(),
                "修复目标连接后重新由人工点击发布");
    }

    private static String required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw ApiException.badRequest("部署载荷缺少 " + key, "重新冻结 Release");
        }
        return String.valueOf(value);
    }
}
