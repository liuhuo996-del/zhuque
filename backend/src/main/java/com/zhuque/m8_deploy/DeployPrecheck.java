package com.zhuque.m8_deploy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zhuque.persistence.ControlPlaneRepository;

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

    private final ControlPlaneRepository repository;
    private final NacosTarget nacos;
    private final HigressAuthTarget higress;

    @Value("${zhuque.nacos.min-version:3.0.1}")
    private String defaultNacosVersion;
    @Value("${zhuque.higress.min-version:2.2.0}")
    private String defaultHigressVersion;
    @Value("${zhuque.higress.redis-ready:false}")
    private boolean redisReady;
    @Value("${zhuque.higress.mcp-enabled:false}")
    private boolean mcpEnabled;

    public DeployPrecheck(ControlPlaneRepository repository, NacosTarget nacos, HigressAuthTarget higress) {
        this.repository = repository;
        this.nacos = nacos;
        this.higress = higress;
    }

    public record CheckItem(String name, boolean ok, String current, String fix) {}

    /** 功能：按某 Release 的 target_constraints 逐项检查。 */
    public List<CheckItem> checkFor(UUID releaseId) {
        Map<String, Object> constraints = repository.requireRelease(releaseId).targetConstraints();
        return checks(String.valueOf(constraints.getOrDefault("nacosMinVersion", defaultNacosVersion)),
            String.valueOf(constraints.getOrDefault("higressMinVersion", defaultHigressVersion)),
            Boolean.parseBoolean(String.valueOf(constraints.getOrDefault("redisRequired", true))),
            Boolean.parseBoolean(String.valueOf(constraints.getOrDefault("mcpServerEnabled", true))));
    }

    /** 功能：设置页的全局环境检查（不绑定 Release，用最新约束基线）。 */
    public List<CheckItem> checkEnvironment() {
        return checks(defaultNacosVersion, defaultHigressVersion, true, true);
    }

    private List<CheckItem> checks(String minNacos, String minHigress, boolean requireRedis, boolean requireMcp) {
        String nacosVersion = probe(nacos::probeVersion);
        String higressVersion = probe(higress::probeVersion);
        return List.of(
                new CheckItem("Nacos Admin API", atLeast(nacosVersion, minNacos), nacosVersion,
                        "升级到 Nacos ≥ " + minNacos + "，并检查 Admin API 与命名空间"),
                new CheckItem("Higress Console", atLeast(higressVersion, minHigress), higressVersion,
                        "升级到 Higress ≥ " + minHigress + "，并检查 Console 连接"),
                new CheckItem("Redis", !requireRedis || redisReady, redisReady ? "ready" : "unconfirmed",
                        "确认 Redis 可达后设置 ZHUQUE_HIGRESS_REDIS_READY=true"),
                new CheckItem("MCP Server 能力", !requireMcp || mcpEnabled, mcpEnabled ? "enabled" : "unconfirmed",
                        "在 higress-config 中设置 mcpServer.enable=true，并设置 ZHUQUE_HIGRESS_MCP_ENABLED=true"));
    }

    private static String probe(java.util.function.Supplier<String> probe) {
        try {
            return probe.get();
        } catch (RuntimeException error) {
            return "unreachable";
        }
    }

    private static boolean atLeast(String current, String required) {
        int[] left = version(current);
        int[] right = version(required);
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            int a = index < left.length ? left[index] : 0;
            int b = index < right.length ? right[index] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return true;
    }

    private static int[] version(String value) {
        if (value == null || !value.matches(".*\\d.*")) {
            return new int[] {-1};
        }
        String numeric = value.replaceFirst("^[^0-9]*", "").split("[-+]", 2)[0];
        return java.util.Arrays.stream(numeric.split("\\.")).mapToInt(part -> {
            try { return Integer.parseInt(part.replaceAll("[^0-9].*$", "")); }
            catch (NumberFormatException ignored) { return 0; }
        }).toArray();
    }
}
