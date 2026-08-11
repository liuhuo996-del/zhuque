package com.zhuque.m8_deploy;

import java.util.Map;
import java.util.UUID;

/**
 * M8 · 外部目标的幂等快照接口。当前 Release 发布只编排 NacosTarget；
 * Higress 由运行平台独立维护，不是 GateForge 的部署目标。
 */
public interface DeployTarget {

    /** 当前写入 nacos；higress_auth 仅可能出现在历史 deploy_record 中。 */
    String name();

    /**
     * 功能：应用某 Release 在本 target 的 payload。
     * 必须幂等：同一 payload 重复 apply 结果一致（比对现状 hash，相同则跳过）。
     */
    void apply(UUID releaseId, Map<String, Object> payload);

    /**
     * 功能：读取该 agent 在本 target 的当前线上状态（原样结构）。
     * 发布补偿与 M9 配置漂移比对都用它。
     */
    Map<String, Object> read(String agentSlugName); // 传 mcp-{dept}-{slug} 全名

    /**
     * 功能：把本 target 恢复到 snapshot 的状态（事务失败时的逆向恢复）。
     * snapshot 为 apply 前 read 的返回值；null 表示 apply 前不存在 → 恢复 = 删除。
     */
    void restore(String agentSlugName, Map<String, Object> snapshot);
}
