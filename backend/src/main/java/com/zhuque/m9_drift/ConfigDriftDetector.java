package com.zhuque.m9_drift;

import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.zhuque.common.CanonicalJson;
import com.zhuque.m8_deploy.DualTargetPublisher;
import com.zhuque.m8_deploy.NacosTarget;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M9-2 · 配置漂移检测。
 *
 * 定时从 Nacos 读回每个 active agent 的当前 MCP service 定义
 * （走 M8 NacosTarget.read，别绕过它直连），与该 agent 当前 released
 * Release 的 nacos_payload 做 CanonicalJson 比对。
 * 不一致 = 有人手改了线上配置：
 * - 落 drift_event（kind=config, detail=diff）
 * - 前端提供「重新 apply 当前 Release」修复按钮 → repairByReplay
 *
 * v2 扩展点（留注释不实现）：
 * - 行为漂移：用已通过的只读 case set 定期打线上，比对响应 schema 和关键字段
 * - 运行时漂移：从网关日志翻译工具级信号（零调用、调用后立刻重试、错误率突升）
 */
@Component
public class ConfigDriftDetector {

    private final ControlPlaneRepository repository;
    private final NacosTarget nacos;
    private final DualTargetPublisher publisher;

    public ConfigDriftDetector(ControlPlaneRepository repository, NacosTarget nacos,
                               DualTargetPublisher publisher) {
        this.repository = repository;
        this.nacos = nacos;
        this.publisher = publisher;
    }

    /** 功能：定时比对全部 active agent 的线上配置与 released 快照。 */
    @Scheduled(fixedDelayString = "${zhuque.drift.config-scan-interval:6h}")
    public void scanAll() {
        for (var agent : repository.activeAgents()) {
            var release = repository.releasedForAgent(agent.id()).orElse(null);
            if (release == null) {
                continue;
            }
            String name = String.valueOf(release.higressAuthPayload().get("mcpServerName"));
            try {
                var actual = nacos.read(name);
                Object expected = release.nacosPayload().get("service");
                String expectedHash = CanonicalJson.sha256(expected);
                String actualHash = CanonicalJson.sha256(actual);
                if (!expectedHash.equals(actualHash) && !repository.hasOpenDrift("agent", agent.id(), "config")) {
                    repository.insertDriftEvent("agent", agent.id(), "config", java.util.Map.of(
                            "releaseId", release.id().toString(), "expectedHash", expectedHash,
                            "actualHash", actualHash, "target", "nacos"));
                }
            } catch (RuntimeException error) {
                if (!repository.hasOpenDrift("agent", agent.id(), "config")) {
                    repository.insertDriftEvent("agent", agent.id(), "config", java.util.Map.of(
                            "releaseId", release.id().toString(), "scanError",
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                }
            }
        }
    }

    /**
     * 功能：修复 = 重新 apply 当前 released Release（走 M8 双 target 事务重放）。
     * 成功后把对应 drift_event 置 resolved。
     */
    public void repairByReplay(UUID agentId, String operator) {
        var release = repository.releasedForAgent(agentId)
                .orElseThrow(() -> com.zhuque.common.ApiException.notFound("数字员工当前 released Release"));
        publisher.replay(release.id(), operator);
        repository.resolveDrift("agent", agentId, "config");
    }
}
