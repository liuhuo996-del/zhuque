package com.zhuque.m1_toolpool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * M1-步骤3 · 从 response schema 递归抽取字段路径列表。
 *
 * 输出形如：orders[].id、data.order.status、customer.phone
 *
 * ！！这是 M4 闭包检查的唯一数据来源，必须做全：
 * - 数组用 [] 标记层级
 * - 展开嵌套 object 到叶子
 * - oneOf 并集的字段全部保留（宁多勿漏——闭包检查漏一个字段就是假 BLOCKED）
 * - 设一个深度上限（如 6 层）防循环引用，触达上限记 warning
 */
@Component
public class OutputFieldExtractor {

    /**
     * 功能：递归遍历 schema 树，产出叶子字段的完整路径列表。
     * 落库到 tool.output_fields（jsonb 数组）。
     */
    public List<String> extract(Map<String, Object> responseSchema) {
        throw new UnsupportedOperationException("TODO");
    }
}
