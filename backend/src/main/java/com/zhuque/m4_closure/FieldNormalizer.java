package com.zhuque.m4_closure;

import org.springframework.stereotype.Component;

/**
 * M4 · 字段名归一化——闭包检查比对前必须先过这里。
 *
 * 归一化规则（顺序执行）：
 * 1. 路径取叶：orders[].id → id（但保留父段用于同义判断：order + id）
 * 2. snake/camel 互转统一为 snake：orderNo → order_no
 * 3. 单复数归一：orders → order
 * 4. id/code/no 同义：order_no ≡ order_id ≡ order_code
 * 5. 业务同义词典（可配置）：如 customer ≡ user ≡ member
 *
 * 精确规则命中 = 置信度 1.0；词典/语义命中 < 1.0，
 * 低置信度的匹配要进 ClosureResult.fuzzyMatches 让人确认，不能悄悄当等价。
 */
@Component
public class FieldNormalizer {

    public record NormMatch(boolean matched, double confidence, String rule) {}

    /** 功能：把参数名/字段路径规范化成可比对的 key。 */
    public String normalize(String paramOrFieldPath) {
        throw new UnsupportedOperationException("TODO");
    }

    /** 功能：判断「必填参数 requiredParam」能否由「输出字段 outputFieldPath」满足，带置信度和命中规则名。 */
    public NormMatch matches(String requiredParam, String outputFieldPath) {
        throw new UnsupportedOperationException("TODO");
    }
}
