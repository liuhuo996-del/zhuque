package com.zhuque.m8_deploy;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M8 · Higress 鉴权目标。
 *
 * ！！代码结构纪律（CLAUDE.md 硬约束 7）：所有 Higress 特有的知识
 * ——API 形状、插件名、CRD 结构、版本探测——只能存在于这一个类里。
 * 将来换 Kong/APISIX，重写的应该只有这一个文件。
 * 任何 import higress 相关类型的代码出现在本包之外即违规。
 *
 * 纪律：
 * - 写入 consumer group / key 策略 / 路由级鉴权
 * - 走声明式接口（WasmPlugin CRD 或 Higress Admin 接口），
 *   绝不允许调用控制台前端接口
 * - 失败错误示例：「写入 Higress 鉴权配置失败：consumer group cg-cs 不存在。
 *   Release 已整体回滚，工具未暴露。到 设置 → Higress 连接 核对」
 */
@Component
public class HigressAuthTarget implements DeployTarget {

    @Override
    public String name() {
        return "higress_auth";
    }

    @Override
    public void apply(UUID releaseId, Map<String, Object> payload) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public Map<String, Object> read(String agentSlugName) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void restore(String agentSlugName, Map<String, Object> snapshot) {
        throw new UnsupportedOperationException("TODO");
    }
}
