package com.zhuque.m1_toolpool;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zhuque.common.ApiException;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * REST API 来源的回收站生命周期。
 *
 * 软删除只写辅助 lifecycle 元数据，核心 api_source/tool 行保持不变，历史 Release
 * 的完整快照也不受影响。即使工具仍在能力包中，来源也可以退役；此后它不会再出现在
 * 可选择工具池中，并且新的 Release 会明确要求先移除/替换这些引用。永久删除才要求
 * 来源尚未进入能力包和冻结 Release 的证据链。
 */
@Service
public class ApiSourceLifecycleService {

    private final ControlPlaneRepository repository;

    public ApiSourceLifecycleService(ControlPlaneRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void trash(UUID sourceId, String operator, String reason) {
        var source = repository.requireApiSource(sourceId);
        String actor = requireOperator(operator);
        String retirementReason = requireReason(reason);
        if (repository.isTrashed("api_source", sourceId)) {
            return;
        }
        int references = repository.sourcePackReferenceCount(sourceId);
        List<String> impactChain = repository.impactChain(sourceId);
        repository.trashResource("api_source", sourceId, actor, retirementReason);
        repository.insertAuditEvent(actor, "trash", "api_source", sourceId,
                Map.of("name", source.name(), "specHash", safe(source.specHash()),
                        "reason", retirementReason, "packReferences", references,
                        "impactChain", impactChain,
                        "retention", "核心来源、工具、能力包引用和历史 Release 均保留；"
                                + "普通工具池隐藏，新的 Release 必须先处理现有引用"));
    }

    @Transactional
    public void restore(UUID sourceId, String operator) {
        var source = repository.requireApiSource(sourceId);
        String actor = requireOperator(operator);
        if (!repository.isTrashed("api_source", sourceId)) {
            return;
        }
        repository.restoreResource("api_source", sourceId, actor);
        repository.insertAuditEvent(actor, "restore", "api_source", sourceId,
                Map.of("name", source.name()));
    }

    @Transactional
    public void purge(UUID sourceId, String operator) {
        var source = repository.requireApiSource(sourceId);
        String actor = requireOperator(operator);
        // 先完成所有引用/Release 保护检查和资源清理；只有真正删除成功才记永久删除审计。
        repository.purgeApiSource(sourceId);
        repository.insertAuditEvent(actor, "purge", "api_source", sourceId,
                Map.of("name", source.name(), "specHash", safe(source.specHash()),
                        "retention", "仅保留本条不可变审计事件"));
    }

    private static String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("操作人不能为空", "填写可审计的当前操作人");
        }
        return operator.trim();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.trim().length() < 2) {
            throw ApiException.badRequest("归档理由至少需要两个字符", "说明退役原因，便于后续审计和恢复判断");
        }
        return reason.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
