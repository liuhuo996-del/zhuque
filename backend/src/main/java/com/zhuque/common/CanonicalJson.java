package com.zhuque.common;

/**
 * JSON 规范化 + 哈希。M5 冻结、M8 幂等比对、M9 配置漂移比对共用同一套实现——
 * 三处必须用同一个规范化算法，否则 hash 对不上会产生假漂移。
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /**
     * 功能：把任意 JSON 结构规范化成确定性的字符串。
     * 规则：
     * - object 的 key 按字典序排序（递归）
     * - 去掉所有格式空白
     * - 数字统一表示（1.0 与 1 视为同值的策略要定死并写测试）
     * - null 字段保留还是剔除，选一种并全局一致（建议剔除）
     * 这是 manifest_hash 稳定性的根基：字段顺序不稳，审批绑定 hash 的机制就废了。
     */
    public static String canonicalize(Object jsonValue) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：canonicalize 后取 SHA-256，返回 "sha256:xxxx" 格式。
     * manifest_hash / payload_hash / spec_hash 全走这里。
     */
    public static String sha256(Object jsonValue) {
        throw new UnsupportedOperationException("TODO");
    }
}
