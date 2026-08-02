package com.zhuque.m8_deploy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;

/**
 * M8 · Higress 鉴权目标。
 *
 * ！！代码结构纪律（CLAUDE.md 硬约束 7）：所有 Higress 特有的知识
 * ——接口路径、请求体形状、登录方式、版本探测——只能存在于这一个类里。
 * 将来换 Kong/APISIX，重写的应该只有这一个文件。
 * 任何 Higress 概念出现在本包之外即违规。
 *
 * ============================================================
 * 接口清单（2026-08-01 对 Higress 2.2.3 实测确认，非文档推测）
 * ============================================================
 * 认证：POST /session/login  {username, password, autoLogin}
 *       → 响应 Set-Cookie，后续请求带上该 cookie。是 session 不是 token，
 *         所以要处理会话过期后重登（见 ensureSession）。
 *
 * 路由级鉴权（朱雀真正要写的东西）：
 *   GET    /v1/mcpServer/consumers?mcpServerName={name}   读当前允许的消费者
 *   PUT    /v1/mcpServer/consumers                        整体替换（天然幂等）
 *          请求体 McpServerConsumers {mcpServerName, consumers[]}
 *   DELETE /v1/mcpServer/consumers                        清空
 *
 * 消费者与凭据：
 *   GET    /v1/consumers            列表（分页）
 *   POST   /v1/consumers            新建   Consumer {name, credentials[]}
 *   GET    /v1/consumers/{name}
 *   PUT    /v1/consumers/{name}     整体替换（幂等）
 *   DELETE /v1/consumers/{name}
 *
 * 为什么用 PUT 而不是 POST：PUT 是整体替换，重复执行结果一致，
 * 满足 DeployTarget.apply 的幂等要求；POST 重复调用会撞名字冲突。
 *
 * ------------------------------------------------------------
 * 两个必须知道的坑
 * ------------------------------------------------------------
 * 1. Higress 原生没有 "consumer group" 这个一等公民。
 *    朱雀数据模型里的 department.consumer_group_ref，落到这里其实是
 *    「一组 consumer 名字的命名约定」——本类负责维护这个映射，
 *    上层业务不需要知道。
 * 2. 控制台前端是单页应用，任何未匹配路径都返回 200 + 首页 HTML。
 *    所以判断接口是否可用绝不能只看状态码，必须看 Content-Type
 *    是不是 application/json（见 assertJson）。这个坑实测踩过。
 *
 * Swagger 默认关闭（springdoc.*.enabled=false）。需要接口清单时，
 * 用 SPRINGDOC_APIDOCS_ENABLED=true 启动 console 即可，
 * 但这只影响文档页，不影响上述接口本身——它们一直可用。
 */
@Component
public class HigressAuthTarget implements DeployTarget {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String KEY_AUTH_TYPE = "key-auth";
    private static final String KEY_AUTH_SOURCE = "HEADER";
    private static final String KEY_AUTH_HEADER = "x-api-key";
    private static final String MISSING_MCP_ROUTE = "No MCP-bound route is found.";
    private static final String SNAPSHOT_MARKER = "_zhuqueHigressSnapshot";
    private final NacosTarget secretStore;

    public HigressAuthTarget(NacosTarget secretStore) {
        this.secretStore = secretStore;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${zhuque.higress.console-url}")
    private String consoleUrl;
    @Value("${zhuque.higress.username}")
    private String username;
    @Value("${zhuque.higress.password}")
    private String password;

    /** session cookie。null 表示尚未登录或已过期。 */
    private volatile String sessionCookie;

    @Override
    public String name() {
        return "higress_auth";
    }

    // ---------------------------------------------------------------- 会话

    /**
     * 功能：确保持有有效 session。首次调用或 401 之后调用。
     * 实现：POST /session/login，从响应头 Set-Cookie 取会话。
     * 失败时抛出的信息要能直接展示给用户，指向「设置 → Higress 连接」。
     */
    private void ensureSession() {
        try {
            String body = JSON.writeValueAsString(Map.of("username", username, "password", password,
                    "autoLogin", true));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/session/login"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertJson(response, "/session/login");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable("Higress 登录失败（HTTP " + response.statusCode() + "）");
            }
            sessionCookie = response.headers().allValues("set-cookie").stream()
                    .map(value -> value.split(";", 2)[0]).reduce((left, right) -> left + "; " + right)
                    .orElseThrow(() -> unavailable("Higress 登录成功但未返回 session cookie"));
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Higress 登录失败：" + error.getMessage());
        }
    }

    /**
     * 功能：带 session 发请求；遇 401 自动重登一次再重试。
     * 所有对 Higress 的调用都走这里，不允许绕过。
     */
    private HttpResponse<String> send(String method, String path, String jsonBody) {
        ensureSessionIfNeeded();
        HttpResponse<String> response = sendOnce(method, path, jsonBody);
        if (response.statusCode() == 401) {
            sessionCookie = null;
            ensureSession();
            response = sendOnce(method, path, jsonBody);
        }
        assertJson(response, path);
        return response;
    }

    /**
     * 功能：断言响应是真 JSON 而不是 SPA 兜底页。
     * 见类注释坑 #2：Content-Type 不是 application/json 就说明这个接口不存在，
     * 抛错并提示「Higress Console 版本可能不匹配，确认 ≥ 2.2.x」。
     */
    private void assertJson(HttpResponse<String> resp, String path) {
        // Higress 的 POST/PUT/DELETE 成功响应及资源 GET 404 都可能是无 body、无 Content-Type。
        // SPA 兜底页一定有非空 HTML body，因此空响应可以安全放行。
        if (resp.body() == null || resp.body().isBlank()) {
            return;
        }
        boolean json = resp.headers().firstValue("content-type")
                .map(value -> value.toLowerCase().contains("application/json")).orElse(false);
        if (!json) {
            throw unavailable("Higress 接口 " + path + " 返回的不是 JSON，Console 版本可能不匹配");
        }
    }

    // ------------------------------------------------------- DeployTarget

    /**
     * 功能：把 Release 的 higress_auth_payload 应用到 Higress。
     *
     * payload 结构（由 M5 ManifestCompiler 编译，与 Higress 形状对齐）：
     *   { "mcpServerName": "mcp-cs-aftersales",
     *     "consumers": ["agent-cs-aftersales"],
    *     "credentials": [{"name": "agent-cs-aftersales", "type": "key-auth",
    *                       "values": ["<key_ref>"]}] }
     *
     * 两步，顺序不可换：
     *   ① 先确保 consumer 及其凭据存在：PUT /v1/consumers/{name}
     *      （不存在则 POST /v1/consumers）
     *   ② 再把 consumer 挂到 MCP Server 的允许列表：PUT /v1/mcpServer/consumers
     * 理由：先建消费者后授权，反过来会引用一个不存在的 consumer 而失败。
     *
     * 幂等：两步都用 PUT 整体替换，重复 apply 结果一致。
     */
    @Override
    public void apply(UUID releaseId, Map<String, Object> payload) {
        String serverName = required(payload, "mcpServerName");
        List<Map<String, Object>> credentials = maps(payload.get("credentials"));
        for (Map<String, Object> credential : credentials) {
            String name = required(credential, "name");
            Map<String, Object> consumer = new LinkedHashMap<>();
            consumer.put("name", name);
            Object configuredValues = credential.getOrDefault("values", List.of());
            List<String> values = configuredValues instanceof List<?> list
                    ? list.stream().map(String::valueOf).map(secretStore::resolveSecret).toList() : List.of();
            consumer.put("credentials", List.of(Map.of(
                    "type", KEY_AUTH_TYPE,
                    "source", KEY_AUTH_SOURCE,
                    "key", KEY_AUTH_HEADER,
                    "values", values)));
            HttpResponse<String> existing = send("GET", "/v1/consumers/" + encode(name), null);
            HttpResponse<String> applied;
            if (existing.statusCode() == 404) {
                applied = send("POST", "/v1/consumers", json(consumer));
            } else {
                requireSuccess(existing, "读取消费者 " + name);
                applied = send("PUT", "/v1/consumers/" + encode(name), json(consumer));
            }
            requireSuccess(applied, "配置消费者 " + name);
        }
        Map<String, Object> allowList = Map.of("mcpServerName", serverName,
            "consumers", payload.getOrDefault("consumers", List.of()));
        requireSuccess(send("PUT", "/v1/mcpServer/consumers", json(allowList)), "配置 MCP 消费者允许列表");
    }

    /**
     * 功能：读取该 MCP Server 当前的鉴权现状，作为事务快照 / 漂移比对基线。
     * 实现：GET /v1/mcpServer/consumers?mcpServerName={agentSlugName}
     * 该 MCP Server 不存在时返回 null——语义是「apply 前它不存在」，
     * restore 收到 null 就执行删除。
     */
    @Override
    public Map<String, Object> read(String agentSlugName) {
        HttpResponse<String> allowResponse = send("GET", "/v1/mcpServer/consumers?mcpServerName="
                + encode(agentSlugName), null);
        Map<String, Object> allowList = null;
        if (allowResponse.statusCode() != 404 && !isMissingMcpServer(allowResponse)) {
            requireSuccess(allowResponse, "读取 MCP 消费者允许列表");
            allowList = responseData(allowResponse.body());
        }

        String consumerName = consumerName(agentSlugName);
        HttpResponse<String> consumerResponse = send("GET", "/v1/consumers/" + encode(consumerName), null);
        Map<String, Object> consumer = null;
        if (consumerResponse.statusCode() != 404) {
            requireSuccess(consumerResponse, "读取 MCP 消费者 " + consumerName);
            consumer = responseData(consumerResponse.body());
        }
        if (allowList == null && consumer == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(SNAPSHOT_MARKER, true);
        snapshot.put("allowList", allowList);
        snapshot.put("consumer", consumer);
        return snapshot;
    }

    /**
     * 功能：用快照把鉴权恢复原状（双 target 事务失败时的逆向恢复）。
     * snapshot == null → DELETE /v1/mcpServer/consumers（原本就不存在）
     * snapshot != null → PUT 回旧的 consumers 列表
     *
     * 注意：这里恢复的是一份还没有人用过的鉴权配置（因为发布顺序是
     * 先鉴权后暴露工具），所以撤销无副作用——这正是那个顺序的价值。
     */
    @Override
    public void restore(String agentSlugName, Map<String, Object> snapshot) {
        Map<String, Object> allowList = snapshot;
        Map<String, Object> consumer = null;
        if (snapshot != null && Boolean.TRUE.equals(snapshot.get(SNAPSHOT_MARKER))) {
            allowList = nullableMap(snapshot.get("allowList"));
            consumer = nullableMap(snapshot.get("consumer"));
        }
        String consumerName = consumerName(agentSlugName);

        // 恢复旧配置时先确保 consumer 存在，再恢复引用它的允许列表。
        if (consumer != null) {
            restoreConsumer(consumerName, consumer);
        }
        if (allowList == null) {
            deleteAllowList(agentSlugName);
        } else {
            Map<String, Object> restored = new LinkedHashMap<>(allowList);
            restored.putIfAbsent("mcpServerName", agentSlugName);
            requireSuccess(send("PUT", "/v1/mcpServer/consumers", json(restored)), "恢复 MCP 消费者允许列表");
        }
        if (consumer == null) {
            deleteConsumer(consumerName);
        }
    }

    // ------------------------------------------------------------ 前置检查

    /**
     * 功能：供 DeployPrecheck 调用，确认 Higress 可用且版本满足。
     * 实现：GET /system/info → {"version":"2.2.3","capabilities":[...]}
     * 该接口无需登录，适合做连通性探测。
     */
    public String probeVersion() {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/system/info"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertJson(response, "/system/info");
            requireSuccess(response, "探测 Higress 版本");
            return String.valueOf(map(response.body()).getOrDefault("version", "unknown"));
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Higress 版本探测失败：" + error.getMessage());
        }
    }

    private void ensureSessionIfNeeded() {
        if (sessionCookie == null) {
            synchronized (this) {
                if (sessionCookie == null) {
                    ensureSession();
                }
            }
        }
    }

    private HttpResponse<String> sendOnce(String method, String path, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json").header("Cookie", sessionCookie);
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception error) {
            throw unavailable("Higress 请求失败：" + error.getMessage());
        }
    }

    private URI uri(String path) {
        return URI.create(consoleUrl.replaceAll("/+$", "") + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw ApiException.badRequest("无法序列化 Higress 请求", "检查 Release 鉴权载荷");
        }
    }

    private static Map<String, Object> map(String body) {
        try {
            return JSON.readValue(body, MAP);
        } catch (Exception error) {
            throw unavailable("Higress JSON 响应无法解析");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseData(String body) {
        Map<String, Object> envelope = map(body);
        Object data = envelope.get("data");
        if (data instanceof Map<?, ?> value) {
            return (Map<String, Object>) value;
        }
        return envelope.containsKey("mcpServerName") ? envelope : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullableMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private void restoreConsumer(String name, Map<String, Object> consumer) {
        Map<String, Object> restored = new LinkedHashMap<>(consumer);
        restored.put("name", name);
        HttpResponse<String> current = send("GET", "/v1/consumers/" + encode(name), null);
        HttpResponse<String> response;
        if (current.statusCode() == 404) {
            response = send("POST", "/v1/consumers", json(restored));
        } else {
            requireSuccess(current, "读取待恢复消费者 " + name);
            response = send("PUT", "/v1/consumers/" + encode(name), json(restored));
        }
        requireSuccess(response, "恢复消费者 " + name);
    }

    private void deleteConsumer(String name) {
        HttpResponse<String> response = send("DELETE", "/v1/consumers/" + encode(name), null);
        if (response.statusCode() != 404) {
            requireSuccess(response, "删除消费者 " + name);
        }
    }

    private void deleteAllowList(String agentSlugName) {
        Map<String, Object> deleteBody = Map.of("mcpServerName", agentSlugName, "consumers", List.of());
        HttpResponse<String> response = send("DELETE", "/v1/mcpServer/consumers", json(deleteBody));
        if (response.statusCode() != 404 && !isMissingMcpServer(response)) {
            requireSuccess(response, "删除 MCP 消费者允许列表");
        }
    }

    private static String consumerName(String mcpServerName) {
        return mcpServerName.startsWith("mcp-")
                ? "agent-" + mcpServerName.substring("mcp-".length())
                : "agent-" + mcpServerName;
    }

    private static boolean isMissingMcpServer(HttpResponse<String> response) {
        if (response.statusCode() != 500 || response.body() == null || response.body().isBlank()) {
            return false;
        }
        try {
            return String.valueOf(map(response.body()).getOrDefault("message", "")).contains(MISSING_MCP_ROUTE);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList() : List.of();
    }

    private static String required(Map<String, Object> value, String key) {
        Object field = value.get(key);
        if (field == null || String.valueOf(field).isBlank()) {
            throw ApiException.badRequest("Higress 鉴权载荷缺少 " + key, "重新冻结 Release 生成完整载荷");
        }
        return String.valueOf(field);
    }

    private static void requireSuccess(HttpResponse<String> response, String action) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw unavailable(action + "失败（HTTP " + response.statusCode() + "）：" + response.body());
        }
    }

    private static ApiException unavailable(String what) {
        return ApiException.unavailable(what, "到 设置 → Higress 连接检查地址、版本和凭据（要求 ≥ 2.2.x）");
    }
}
