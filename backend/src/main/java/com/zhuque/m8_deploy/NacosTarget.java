package com.zhuque.m8_deploy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;

/**
 * M8 · Nacos 目标。
 *
 * 纪律：
 * - 走 Nacos Admin API（≥3.0.1）。不是 client OpenAPI——后者不提供配置发布接口
 * - 写入内容：MCP service 定义（tools、描述、inputSchema、参数映射模板、
 *   后端服务引用、访问路径），dataId = mcp-{dept}-{slug}.json，group = mcp-server
 * - 幂等：apply 前先 read 比对 CanonicalJson hash，相同直接返回
 * - 错误信息要具体：「写入 Nacos 失败：命名空间 prod 不存在。到 设置 → Nacos 连接 检查」
 */
@Component
public class NacosTarget implements DeployTarget {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String CONFIG_PATH = "/v3/admin/cs/config";
    private static final String LOGIN_PATH = "/v3/auth/user/login";
    private static final String STATE_V3_PATH = "/v3/console/server/state";
    private static final String STATE_V1_PATH = "/v1/console/server/state";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${zhuque.nacos.server-url}")
    private String serverUrl;
    @Value("${zhuque.nacos.context-path:/nacos}")
    private String contextPath;
    @Value("${zhuque.nacos.namespace:public}")
    private String namespace;
    @Value("${zhuque.nacos.username:}")
    private String username;
    @Value("${zhuque.nacos.password:}")
    private String password;

    private volatile String accessToken;

    @Override
    public String name() {
        return "nacos";
    }

    /** 功能：PUT 配置到 Admin API（全量覆盖，不是 diff patch）。 */
    @Override
    public void apply(UUID releaseId, Map<String, Object> payload) {
        String dataId = required(payload, "dataId");
        String group = String.valueOf(payload.getOrDefault("group", "mcp-server"));
        Map<String, Object> desired = object(payload.get("service"));
        Map<String, Object> current = read(dataId.replaceFirst("\\.json$", ""));
        if (current != null && CanonicalJson.sha256(current).equals(CanonicalJson.sha256(desired))) {
            return;
        }
        String form = form(Map.of("dataId", dataId, "groupName", group, "namespaceId", namespace,
                "content", json(desired), "type", "json"));
        HttpResponse<String> response = request("POST", CONFIG_PATH, form);
        requireSuccess(response, "写入 Nacos 配置 " + dataId);
    }

    /** 功能：GET 当前配置。404 返回 null 语义（首次发布时快照即"不存在"）。 */
    @Override
    public Map<String, Object> read(String agentSlugName) {
        String dataId = agentSlugName.endsWith(".json") ? agentSlugName : agentSlugName + ".json";
        String query = configQuery(dataId, "mcp-server");
        HttpResponse<String> response = request("GET", CONFIG_PATH + query, null);
        if (response.statusCode() == 404) {
            return null;
        }
        requireSuccess(response, "读取 Nacos 配置 " + dataId);
        Map<String, Object> envelope = object(response.body());
        Object data = envelope.containsKey("data") ? envelope.get("data") : envelope;
        if (data instanceof Map<?, ?> map) {
            Object content = map.get("content");
            return content instanceof String string ? object(string) : cast(map);
        }
        if (data instanceof String string) {
            return object(string);
        }
        return null;
    }

    /** 功能：恢复快照：snapshot==null 则 DELETE 配置，否则 PUT 回旧内容。 */
    @Override
    public void restore(String agentSlugName, Map<String, Object> snapshot) {
        String dataId = agentSlugName.endsWith(".json") ? agentSlugName : agentSlugName + ".json";
        if (snapshot == null) {
            String query = configQuery(dataId, "mcp-server");
            HttpResponse<String> response = request("DELETE", CONFIG_PATH + query, null);
            if (response.statusCode() != 404) {
                requireSuccess(response, "删除 Nacos 配置 " + dataId);
            }
            return;
        }
        HttpResponse<String> response = request("POST", CONFIG_PATH, form(Map.of(
            "dataId", dataId, "groupName", "mcp-server", "namespaceId", namespace,
                "content", json(snapshot), "type", "json")));
        requireSuccess(response, "恢复 Nacos 配置 " + dataId);
    }

    public String probeVersion() {
        HttpResponse<String> response = request("GET", STATE_V3_PATH, null);
        if (response.statusCode() == 404) {
            response = request("GET", STATE_V1_PATH, null);
        }
        // 官方 3.0.1 Docker 镜像可能未映射 v3 Console state，且 v1 直接返回 410。
        // 此时用只有 3.x 才存在的 Admin API 做能力探测；400/404 都证明路由已注册。
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            HttpResponse<String> admin = request("GET", CONFIG_PATH
                    + configQuery("__zhuque_version_probe__", "__zhuque_probe__"), null);
            if (admin.statusCode() == 400 || admin.statusCode() == 404 || isSuccess(admin)) {
                return "3.0.1+";
            }
            response = admin;
        }
        requireSuccess(response, "探测 Nacos 版本");
        Map<String, Object> state = object(response.body());
        Object data = state.getOrDefault("data", state);
        if (data instanceof Map<?, ?> map) {
            Object version = map.get("version");
            return version == null ? "unknown" : String.valueOf(version);
        }
        return String.valueOf(state.getOrDefault("version", "unknown"));
    }

    /** 密钥明文只写入 Nacos 加密配置；数据库和 Release 仅保存此 dataId 引用。 */
    public String putSecret(UUID agentId, String plaintext) {
        String dataId = "cipher-aes-256-zhuque-agent-key-" + UUID.randomUUID();
        HttpResponse<String> response = request("POST", CONFIG_PATH, form(Map.of(
            "dataId", dataId, "groupName", "zhuque-secrets", "namespaceId", namespace,
                "content", plaintext, "type", "text")));
        requireSuccess(response, "托管数字员工密钥");
        return "nacos://zhuque-secrets/" + dataId;
    }

    public String resolveSecret(String keyRef) {
        if (keyRef == null || !keyRef.startsWith("nacos://zhuque-secrets/")) {
            throw ApiException.badRequest("不支持的 key_ref", "重新签发数字员工密钥");
        }
        String dataId = keyRef.substring("nacos://zhuque-secrets/".length());
        String query = configQuery(dataId, "zhuque-secrets");
        HttpResponse<String> response = request("GET", CONFIG_PATH + query, null);
        requireSuccess(response, "读取托管密钥");
        Map<String, Object> envelope = object(response.body());
        Object data = envelope.getOrDefault("data", envelope);
        if (data instanceof Map<?, ?> map && map.get("content") != null) {
            return String.valueOf(map.get("content"));
        }
        return String.valueOf(data);
    }

    public void deleteSecret(String keyRef) {
        if (keyRef == null || !keyRef.startsWith("nacos://zhuque-secrets/")) {
            return;
        }
        String dataId = keyRef.substring("nacos://zhuque-secrets/".length());
        String query = configQuery(dataId, "zhuque-secrets");
        HttpResponse<String> response = request("DELETE", CONFIG_PATH + query, null);
        if (response.statusCode() != 404) {
            requireSuccess(response, "吊销托管密钥");
        }
    }

    private HttpResponse<String> request(String method, String path, String body) {
        try {
            HttpResponse<String> response = send(method, path, body, accessToken);
            if ((response.statusCode() == 401 || response.statusCode() == 403)
                    && username != null && !username.isBlank()) {
                login();
                response = send(method, path, body, accessToken);
            }
            return response;
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Nacos 请求失败：" + error.getMessage());
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/x-www-form-urlencoded");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private synchronized void login() {
        try {
            HttpResponse<String> response = send("POST", LOGIN_PATH,
                    form(Map.of("username", username, "password", password)), null);
            requireSuccess(response, "登录 Nacos");
            accessToken = String.valueOf(object(response.body()).get("accessToken"));
            if (accessToken == null || accessToken.isBlank() || "null".equals(accessToken)) {
                throw unavailable("Nacos 登录未返回 accessToken");
            }
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Nacos 登录失败：" + error.getMessage());
        }
    }

    private URI uri(String path) {
        String base = serverUrl.replaceAll("/+$", "");
        String context = contextPath == null || contextPath.isBlank() ? "" : "/" + contextPath.replaceAll("^/+|/+$", "");
        if (!context.isBlank() && !base.endsWith(context)) {
            base += context;
        }
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    private String configQuery(String dataId, String groupName) {
        return "?" + form(Map.of("dataId", dataId, "groupName", groupName, "namespaceId", namespace));
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw ApiException.badRequest("Nacos 载荷缺少 " + key, "重新冻结 Release 生成完整载荷");
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        try {
            return JSON.readValue(String.valueOf(value), MAP);
        } catch (Exception error) {
            throw unavailable("Nacos JSON 无法解析");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw ApiException.badRequest("无法序列化 Nacos 配置", "检查 Release 的 nacos_payload");
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String action) {
        if (!isSuccess(response)) {
            throw unavailable(action + "失败（HTTP " + response.statusCode() + "）：" + response.body());
        }
    }

    private static boolean isSuccess(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static ApiException unavailable(String what) {
        return ApiException.unavailable(what, "到 设置 → Nacos 连接检查地址、命名空间和凭据（要求 ≥ 3.0.1 Admin API）");
    }
}
