package com.zhuque.m2_agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.zhuque.m10_org.AgentLifecycleService;
import com.zhuque.persistence.ControlPlaneRepository;
import com.zhuque.persistence.ControlPlaneRepository.AgentRow;
import com.zhuque.persistence.ControlPlaneRepository.IntentRow;

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
    private final AgentLifecycleService lifecycle;
    private final IntentDecomposer intentDecomposer;
    private final ControlPlaneRepository repository;

    public AgentController(AgentService agentService, AgentLifecycleService lifecycle,
                           IntentDecomposer intentDecomposer,
                           ControlPlaneRepository repository) {
        this.agentService = agentService;
        this.lifecycle = lifecycle;
        this.intentDecomposer = intentDecomposer;
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(@RequestBody AgentService.CreateAgentCmd command) {
        return Map.of("id", agentService.create(command));
    }

    @PostMapping("/{id}/decompose")
    public DecomposeView decompose(@PathVariable UUID id) {
        AgentRow agent = repository.requireAgentEditable(id);
        IntentDecomposer.DecomposeResult result = intentDecomposer.decompose(
                agent.description(), agent.forbiddenNotes());
        List<IntentDraft> intents = result.intents().stream()
                .map(text -> new IntentDraft(text, "ai", intentDecomposer.startsWithActionVerb(text)))
                .toList();
        return new DecomposeView(intents, result.forbiddenRules(), result.splitAdvice());
    }

    @PutMapping("/{id}/intents")
    public List<IntentRow> saveIntents(@PathVariable UUID id, @RequestBody List<IntentInput> inputs) {
        List<IntentRow> existing = repository.intentsForAgent(id);
        Map<UUID, IntentRow> existingById = new LinkedHashMap<>();
        existing.forEach(intent -> existingById.put(intent.id(), intent));
        List<IntentRow> rows = java.util.stream.IntStream.range(0, inputs == null ? 0 : inputs.size())
                .mapToObj(index -> {
                    IntentInput input = inputs.get(index);
                    IntentRow old = input.id() == null ? null : existingById.get(input.id());
                    String source = old != null && old.text().equals(input.text()) ? old.source() : "human";
                    return new IntentRow(input.id(), id, input.text().trim(), index + 1, source);
                }).toList();
        repository.replaceIntents(id, rows);
        return repository.intentsForAgent(id);
    }

    @GetMapping
    public List<AgentView> list(@RequestParam(required = false) UUID department,
                                @RequestParam(defaultValue = "false") boolean trash) {
        List<AgentRow> rows = trash ? repository.retiredAgents(department) : repository.agents(department);
        return rows.stream().map(this::view).toList();
    }

    /** DELETE 是“移入回收站”，不是物理删除。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trash(@PathVariable UUID id, @RequestBody(required = false) LifecycleInput input) {
        lifecycle.retire(id, operator(input), input == null ? null : input.reason());
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID id, @RequestBody(required = false) LifecycleInput input) {
        lifecycle.restoreFromTrash(id, operator(input));
    }

    @DeleteMapping("/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable UUID id, @RequestBody(required = false) LifecycleInput input) {
        lifecycle.purge(id, operator(input));
    }

    @GetMapping("/{id}")
    public AgentDetail detail(@PathVariable UUID id) {
        AgentRow agent = repository.requireAgent(id);
        var currentRelease = hasLiveConfiguration(agent) ? repository.releasedForAgent(id).orElse(null) : null;
        return new AgentDetail(view(agent), repository.intentsForAgent(id), currentRelease,
                repository.agentKeys(id, false));
    }

    private AgentView view(AgentRow agent) {
        // 从回收站恢复的 draft 不会自动恢复线上配置；历史 released Release 仍可查询，
        // 但不能在员工卡片上伪装成当前生效版本。
        var release = hasLiveConfiguration(agent) ? repository.releasedForAgent(agent.id()).orElse(null) : null;
        boolean drift = repository.hasOpenDrift("agent", agent.id(), "config");
        return new AgentView(agent.id(), agent.departmentId(), agent.name(), agent.slug(), agent.description(),
                agent.forbiddenNotes(), agent.status(), agent.mcpUrl(), drift ? "drift" : "ok",
                release == null ? null : release.version(), repository.toolsForAgent(agent.id()).size(),
                release == null ? null : release.createdAt().toString(), agent.createdAt().toString());
    }

    public record IntentInput(UUID id, String text) {}
    public record LifecycleInput(String operator, String reason) {}
    public record IntentDraft(String text, String source, boolean actionVerbValid) {}
    public record DecomposeView(List<IntentDraft> intents, List<String> forbiddenRules, String splitAdvice) {}
    public record AgentView(UUID id, UUID departmentId, String name, String slug, String description,
                            String forbiddenNotes, String status, String mcpUrl, String health,
                            String currentVersion, int toolCount, String lastReleasedAt, String createdAt) {}
    public record AgentDetail(AgentView agent, List<IntentRow> intents,
                              ControlPlaneRepository.ReleaseRow currentRelease,
                              List<ControlPlaneRepository.AgentKeyRow> keys) {}

    private static String operator(LifecycleInput input) {
        return input == null || input.operator() == null || input.operator().isBlank()
                ? "console-user" : input.operator();
    }

    private static boolean hasLiveConfiguration(AgentRow agent) {
        return "active".equals(agent.status()) || "suspended".equals(agent.status());
    }
}
