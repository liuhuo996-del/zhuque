package com.zhuque.m8_deploy;

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
 * M8 · Nacos MCP Registry 发布器。
 *
 * GateForge 只把冻结快照发布到 Nacos 3 原生 MCP Registry。Higress 的
 * Nacos3 服务来源、路由策略和鉴权由平台侧独立维护；每次 Release 发布不会
 * 登录或修改 Higress Console。Higress watcher 从 Nacos 自动发现并生成路由。
 *
 * 发布算法：Nacos 前快照 → apply → 等待 detail 可见 → 成功；失败则恢复快照。
 *
 * 其他：
 * - 每次 apply 落 deploy_record（target、payload_hash、结果、耗时）
 * - 成功后：release → released，上一版 → superseded；
 *   首次发布回调 AgentService.markActiveAfterFirstRelease
 * - 返回 MCP URL；运行时鉴权凭据由 Higress 平台侧独立配置
 */
@Service
public class DualTargetPublisher {

    private final NacosTarget nacosTarget;
    private final DeployPrecheck precheck;
    private final ControlPlaneRepository repository;
    private final AgentService agentService;

    public DualTargetPublisher(NacosTarget nacosTarget, DeployPrecheck precheck,
                               ControlPlaneRepository repository,
                               AgentService agentService) {
        this.nacosTarget = nacosTarget;
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
     * 功能：回滚重放。取旧 Release 的 Nacos payload 原样 apply（同一套事务算法），
     * 零重新计算。由 M5.rollbackTo 调用。
     */
    public void replay(UUID oldReleaseId, String operator) {
        ReleaseRow release = repository.requireRelease(oldReleaseId);
        assertAgentCanPublish(release);
        if (release.nacosPayload().isEmpty()) {
            throw ApiException.conflict("旧 Release 不含 Nacos MCP 快照", "只能选择曾成功冻结并发布的版本回滚");
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
        String serviceName = required(release.nacosPayload(), "mcpName");
        Map<String, Object> nacosBefore = nacosTarget.read(serviceName);
        boolean nacosTouched = false;
        RuntimeException failure;
        try {
            // 先置 true：即使远端已部分写入后抛错，也必须尝试恢复原快照。
            nacosTouched = true;
            nacosTarget.apply(release.id(), release.nacosPayload());
            nacosTarget.awaitMcpVisible(serviceName);
            repository.insertDeployRecord(release.id(), nacosTarget.name(),
                    CanonicalJson.sha256(release.nacosPayload()), "success");
            return;
        } catch (RuntimeException error) {
            failure = error;
        }

        RuntimeException restoreFailure = null;
        if (nacosTouched) {
            try {
                nacosTarget.restore(serviceName, nacosBefore);
            } catch (RuntimeException error) {
                restoreFailure = error;
            }
        }
        String result = restoreFailure == null ? "failed_rolled_back"
                : "critical_inconsistent:Nacos 恢复失败：" + message(restoreFailure);
        repository.insertDeployRecord(release.id(), "nacos", CanonicalJson.sha256(release.nacosPayload()), result);
        if (restoreFailure != null) {
            throw ApiException.unavailable("Nacos MCP 发布失败且恢复不完整：" + message(restoreFailure),
                    "立即停止发布并人工对账 Nacos MCP Registry 状态");
        }
        throw ApiException.unavailable("Nacos MCP 发布失败，已恢复原快照：" + message(failure),
                "修复 Nacos 连接或载荷后重新由人工点击发布");
    }

    private static String required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw ApiException.badRequest("部署载荷缺少 " + key, "重新冻结 Release");
        }
        return String.valueOf(value);
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
