package com.zhuque.m1_toolpool;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * M1-步骤5 · 静态标注（不调模型，纯规则，入库前跑一遍即可）。
 */
@Component
public class StaticAnnotator {

    /**
     * 功能：计算 token_cost = 该 tool（name + description + inputSchema）
     * 序列化后的 token 数。用于 M4/M7 的包预算。
     * 实现提示：不必精确到某个 tokenizer，选一种估算方式（如 cl100k 近似
     * 或 字节数/4）并全局一致——预算是相对量，一致性比精确性重要。
     */
    public int tokenCost(String name, String description, Map<String, Object> inputSchema) {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 功能：敏感字段标注。扫描 inputSchema 属性名 + outputFields 路径，
     * 与敏感词典匹配（手机/phone、身份证/id_number、金额/amount、
     * token/secret/key、地址/address 等，词典要可配置可扩展）。
     * 返回命中的字段路径列表，落 tool.sensitivity_flags。
     * v1 只到标注为止：不做风险评分体系、不做 owner 归属治理。
     */
    public List<String> sensitivityFlags(Map<String, Object> inputSchema, List<String> outputFields) {
        throw new UnsupportedOperationException("TODO");
    }
}
