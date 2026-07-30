package com.zhuque.m5_release;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M5 · 冻结（draft → candidate）时的编译器。冻结做五件事，全部在一个事务里：
 *
 * 1. 序列化完整 manifest：pack 组成、每个 tool 的完整定义、参数映射模板、
 *    projection、意图列表、闭包检查结论
 * 2. manifest_hash = CanonicalJson.sha256(manifest)（字段顺序稳定是前提）
 * 3. 编译两份部署载荷（全量，不是 diff）：
 *    - nacos_payload：Nacos MCP service 定义
 *    - higress_auth_payload：consumer group / key 策略 / 路由鉴权
 * 4. source_spec_hashes：编译自哪几份 spec 的哪一版
 * 5. target_constraints：最低 Nacos / Higress 版本 + Redis 依赖
 *
 * 硬规则：candidate 之后 manifest 不可变。任何修改请求 → 报错并引导开新 Release。
 */
@Component
public class ManifestCompiler {

    public record CompiledRelease(
            Map<String, Object> manifest,
            String manifestHash,
            Map<String, Object> nacosPayload,
            Map<String, Object> higressAuthPayload,
            Map<String, Object> sourceSpecHashes,
            Map<String, Object> targetConstraints) {
    }

    /**
     * 功能：编译入口。从 agent 当前的 pack/intent/闭包结论组装 manifest 并产出双 payload。
     * 注意：nacos_payload 里的 MCP service 名固定为 mcp-{dept}-{slug}（agent 上已固化），
     * higress_auth_payload 引用 key_ref 而非明文。
     */
    public CompiledRelease compile(UUID agentId) {
        throw new UnsupportedOperationException("TODO");
    }
}
