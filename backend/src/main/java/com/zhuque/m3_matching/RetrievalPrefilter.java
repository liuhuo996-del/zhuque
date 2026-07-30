package com.zhuque.m3_matching;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * M3 · 检索预筛扩展点。
 *
 * v1 只留接口不实现：池子在 300~500 量级时长上下文直灌效果更好。
 * 超过 2000 条时的 v2 实现应在这里加 BM25/embedding 预筛，
 * 把候选压到模型上下文能吞下的规模——除此之外 IntentMatcher 的逻辑不变。
 */
public interface RetrievalPrefilter {

    /** 功能：按意图文本预筛候选工具。v1 的 Noop 实现原样返回。 */
    List<UUID> prefilter(List<String> intentTexts, List<UUID> allToolIds, int maxCandidates);

    /** v1 默认实现：不筛，全量透传。 */
    @Component
    class Noop implements RetrievalPrefilter {
        @Override
        public List<UUID> prefilter(List<String> intentTexts, List<UUID> allToolIds, int maxCandidates) {
            return allToolIds;
        }
    }
}
