package com.zhuque.m10_org;

import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * M10 · 数字部门。
 *
 * 创建时同步在网关创建对应的 consumer group（走 M8 HigressAuthTarget，
 * 网关知识不许出现在这里），记录 consumer_group_ref。
 * 意义：网关侧按 consumer 分组的限流规则天然就是部门级配额——
 * v1 不做配额 UI，但执行层先接上。
 *
 * v1 不做：审批分级、部门间 pack 共享、成本归集、配额账单。
 * 但 department_id 外键和 pack.scope 字段必须存在（后置功能加代码不改表）。
 */
@Service
public class DepartmentService {

    /**
     * 功能：创建部门。slug 唯一校验 → 在网关建 consumer group →
     * 回填 consumer_group_ref。网关侧创建失败时部门也不建（保持一致）。
     */
    public UUID create(String name, String slug) {
        throw new UnsupportedOperationException("TODO");
    }
}
