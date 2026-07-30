package com.zhuque.m9_drift;

import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * M9-1 · Spec 漂移检测。
 *
 * 定时（zhuque.drift.spec-scan-interval，默认 6h）拉取每个 api_source 的
 * spec_url 算 hash，变化则：
 * 1. 结构化 diff：复用 M1 SpecSyncService 的条目级 diff（不要重写一套）
 * 2. breaking 判定：删 endpoint / 删必填参数 / 改参数类型 / 改必填性 = breaking
 * 3. 影响面计算：受影响的 tool → 引用它们的 pack → 挂着这些 pack 的
 *    agent 的当前 released Release，全链路列出
 * 4. 落 drift_event（kind=spec, detail=diff+影响面），
 *    概览页和受影响 agent 详情页会读它
 *
 * v1 到此为止：不自动触发回归、不自动升版本。
 * 告警形态：站内列表 + 概览页角标，不做邮件轰炸。
 */
@Component
public class SpecDriftDetector {

    /** 功能：定时扫描全部 api_source。间隔可配。 */
    @Scheduled(fixedDelayString = "${zhuque.drift.spec-scan-interval:6h}")
    public void scanAll() {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：扫描单个来源（工具池页"重新拉取"也走这里，同一套 diff 逻辑）。 */
    public void scanOne(UUID apiSourceId) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：影响面查询：spec diff 触到的 tool/pack/agent/release 链路。 */
    public List<String> impactChain(UUID apiSourceId) {
        throw new UnsupportedOperationException("TODO");
    }
}
