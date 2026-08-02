package com.zhuque.ai;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/** 最小模型边界；业务模块只依赖结构化 JSON，不绑定具体模型厂商。 */
public interface AiModelClient {

    boolean available();

    String modelName();

    /** 请求模型返回一个 JSON object；未配置模型时返回 empty，由业务层走确定性降级。 */
    Optional<JsonNode> completeJson(String systemPrompt, String userPrompt);
}
