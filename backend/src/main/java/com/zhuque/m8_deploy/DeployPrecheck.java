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
 *
 * Higress 属于独立运行面，由平台侧手工配置 Nacos3 服务来源、MCP 能力、
 * Redis 与鉴权；这些状态不会阻塞 GateForge 向 Nacos 发布。
 *
 * Nacos 不满足时直接拒绝发布，并给出具体缺口和修复指引。
 */
@Component
public class DeployPrecheck {

    private final ControlPlaneRepository repository;
    private final NacosTarget nacos;

    @Value("${zhuque.nacos.min-version:3.0.1}")
    private String defaultNacosVersion;

    public DeployPrecheck(ControlPlaneRepository repository, NacosTarget nacos) {
        this.repository = repository;
        this.nacos = nacos;
    }

    public record CheckItem(String name, boolean ok, String current, String fix) {}

    /** 功能：按某 Release 的 target_constraints 逐项检查。 */
    public List<CheckItem> checkFor(UUID releaseId) {
        Map<String, Object> constraints = repository.requireRelease(releaseId).targetConstraints();
        return checks(String.valueOf(constraints.getOrDefault("nacosMinVersion", defaultNacosVersion)));
    }

    /** 功能：设置页的全局环境检查（不绑定 Release，用最新约束基线）。 */
    public List<CheckItem> checkEnvironment() {
        return checks(defaultNacosVersion);
    }

    private List<CheckItem> checks(String minNacos) {
        String nacosVersion = probe(nacos::probeVersion);
        return List.of(
                new CheckItem("Nacos Admin API", atLeast(nacosVersion, minNacos), nacosVersion,
                        "升级到 Nacos ≥ " + minNacos + "，并检查 AI MCP Admin API 与命名空间"));
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
