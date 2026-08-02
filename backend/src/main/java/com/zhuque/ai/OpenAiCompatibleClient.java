package com.zhuque.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;

/** OpenAI-compatible Chat Completions 适配器；API key 仅从环境配置读取。 */
@Component
public class OpenAiCompatibleClient implements AiModelClient {

    private final ObjectMapper json;
    private final HttpClient http;

    @Value("${zhuque.ai.base-url:}")
    private String baseUrl;
    @Value("${zhuque.ai.api-key:}")
    private String apiKey;
    @Value("${zhuque.ai.model:qwen-plus}")
    private String model;
    @Value("${zhuque.ai.temperature:0.1}")
    private double temperature;

    public OpenAiCompatibleClient(ObjectMapper json) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean available() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String modelName() {
        return available() ? model : "zhuque-deterministic-fallback-v1";
    }

    @Override
    public Optional<JsonNode> completeJson(String systemPrompt, String userPrompt) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "temperature", temperature,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)));
            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw ApiException.unavailable("模型调用失败：HTTP " + response.statusCode(),
                        "检查 zhuque.ai 配置、模型权限和网络连通性；也可不配置模型使用规则降级");
            }
            JsonNode root = json.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) {
                throw ApiException.unavailable("模型返回了空内容", "检查模型是否支持 JSON object 输出模式");
            }
            return Optional.of(json.readTree(stripFence(content)));
        } catch (ApiException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("模型调用被中断", "稍后重试");
        } catch (Exception error) {
            throw ApiException.unavailable("模型调用失败：" + error.getMessage(),
                    "检查 zhuque.ai.base-url、api-key 和模型是否兼容 Chat Completions");
        }
    }

    private URI endpoint() {
        String value = baseUrl.strip().replaceAll("/+$", "");
        if (!value.endsWith("/chat/completions")) {
            value += "/chat/completions";
        }
        return URI.create(value);
    }

    private static String stripFence(String value) {
        String text = value.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
