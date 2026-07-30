package com.zhuque.m5_release;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * M5 · Release 状态机。只允许表内迁移，其余一律拒绝（抛异常，带当前态和目标态）。
 *
 *   draft → candidate → tested → approved → released
 *   released → superseded（被新版本取代）
 *   released → rolled_back（被回滚）
 *   任意态 → draft（放弃后重开——注意语义：原 Release 保留不删，
 *                   "重开"是创建一个新的 draft Release，不是把旧的改回 draft）
 */
@Component
public class ReleaseStateMachine {

    /** 合法迁移表。实现时用它做唯一裁决，不要在业务代码里散落 if。 */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "draft", Set.of("candidate"),
            "candidate", Set.of("tested"),
            "tested", Set.of("approved"),
            "approved", Set.of("released"),
            "released", Set.of("superseded", "rolled_back"));

    /**
     * 功能：校验迁移合法性。非法迁移抛异常，信息要能直接展示：
     * 「rolled_back 不能直接变 released：回滚版本要重新上线请开新 Release」。
     */
    public void assertTransition(String from, String to) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：某状态下 manifest 是否已冻结（candidate 及之后一律 true）。 */
    public boolean isFrozen(String status) {
        throw new UnsupportedOperationException("TODO");
    }
}
