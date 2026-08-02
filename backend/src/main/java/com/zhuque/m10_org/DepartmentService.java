package com.zhuque.m10_org;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.zhuque.common.ApiException;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M10 · 数字部门。
 *
 * 创建时固化 consumer_group_ref；具体数据面目标负责把该引用解释为
 * 原生分组资源或消费者命名约定，网关知识不进入本业务服务。
 * 意义：网关侧按 consumer 分组的限流规则天然就是部门级配额——
 * v1 不做配额 UI，但执行层先接上。
 *
 * v1 不做：审批分级、部门间 pack 共享、成本归集、配额账单。
 * 但 department_id 外键和 pack.scope 字段必须存在（后置功能加代码不改表）。
 */
@Service
public class DepartmentService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private final ControlPlaneRepository repository;

    public DepartmentService(ControlPlaneRepository repository) {
        this.repository = repository;
    }

    /**
    * 功能：创建部门。slug 唯一校验 → 生成并固化 consumer_group_ref。
     */
    public UUID create(String name, String slug) {
        String normalizedName = name == null ? "" : name.trim();
        String normalizedSlug = slug == null ? "" : slug.trim();
        if (normalizedName.isBlank()) {
            throw ApiException.badRequest("数字部门名称不能为空", "填写便于识别的部门名称");
        }
        if (!SLUG.matcher(normalizedSlug).matches()) {
            throw ApiException.badRequest("部门 slug 格式不合法", "仅使用小写字母、数字和单个连字符");
        }
        if (repository.departmentSlugExists(normalizedSlug)) {
            throw ApiException.conflict("部门 slug 已存在", "换一个不可变的唯一 slug");
        }
        // 引用语义由部署目标解释，业务层不依赖任何网关资源模型。
        return repository.insertDepartment(normalizedName, normalizedSlug, "department-" + normalizedSlug);
    }
}
