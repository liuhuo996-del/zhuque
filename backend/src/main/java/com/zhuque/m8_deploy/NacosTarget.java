package com.zhuque.m8_deploy;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M8 · Nacos 目标。
 *
 * 纪律：
 * - 走 Nacos Admin API（≥3.0.1）。不是 client OpenAPI——后者不提供配置发布接口
 * - 写入内容：MCP service 定义（tools、描述、inputSchema、参数映射模板、
 *   后端服务引用、访问路径），dataId = mcp-{dept}-{slug}.json，group = mcp-server
 * - 幂等：apply 前先 read 比对 CanonicalJson hash，相同直接返回
 * - 错误信息要具体：「写入 Nacos 失败：命名空间 prod 不存在。到 设置 → Nacos 连接 检查」
 */
@Component
public class NacosTarget implements DeployTarget {

    @Override
    public String name() {
        return "nacos";
    }

    /** 功能：PUT 配置到 Admin API（全量覆盖，不是 diff patch）。 */
    @Override
    public void apply(UUID releaseId, Map<String, Object> payload) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：GET 当前配置。404 返回 null 语义（首次发布时快照即"不存在"）。 */
    @Override
    public Map<String, Object> read(String agentSlugName) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：恢复快照：snapshot==null 则 DELETE 配置，否则 PUT 回旧内容。 */
    @Override
    public void restore(String agentSlugName, Map<String, Object> snapshot) {
        throw new UnsupportedOperationException("TODO");
    }
}
