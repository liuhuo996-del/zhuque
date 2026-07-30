package com.zhuque.m2_agent;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M2 · 数字员工 HTTP 入口。
 *
 * 路由规划（对应前端新建向导步骤 1 与 agent 详情页）：
 *   POST /api/agents                     创建（AgentService.create）
 *   POST /api/agents/{id}/decompose      触发意图拆解，返回意图列表 + 可能的拆分建议
 *   PUT  /api/agents/{id}/intents        整体保存编辑后的意图列表（增删改排序一次提交；
 *                                        文本被改过的条目 source 置 human）
 *   GET  /api/agents?department=         列表（部门过滤）
 *   GET  /api/agents/{id}                详情（含意图、当前 release、key 引用）
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;
    private final IntentDecomposer intentDecomposer;

    public AgentController(AgentService agentService, IntentDecomposer intentDecomposer) {
        this.agentService = agentService;
        this.intentDecomposer = intentDecomposer;
    }
}
