package com.zhuque.m8_deploy;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M8 · 发布前置检查（同时服务设置页的"立即检查"按钮）。
 *
 * 检查项：
 * - Nacos 版本 ≥ target_constraints 要求，Admin API 可用
 * - Higress 版本（需支持同步 Nacos 原生 MCP Server）
 * - Redis 可达（Higress 的 MCP 功能依赖）
 * - Higress MCP 功能已 enable
 *
 * 不满足 → 直接拒绝发布，每项失败都要给出具体缺口和修复指引
 * （如「Higress 未启用 MCP Server 能力。在 higress-config 中设置
 * mcpServer.enable: true 并重启网关」）。
 */
@Component
public class DeployPrecheck {

    public record CheckItem(String name, boolean ok, String current, String fix) {}

    /** 功能：按某 Release 的 target_constraints 逐项检查。 */
    public List<CheckItem> checkFor(UUID releaseId) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：设置页的全局环境检查（不绑定 Release，用最新约束基线）。 */
    public List<CheckItem> checkEnvironment() {
        throw new UnsupportedOperationException("TODO");
    }
}
