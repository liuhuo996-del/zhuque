package com.zhuque.m8_deploy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * - MCP 服务走 Nacos 3.x AI Registry Admin API；普通 Config Center 只用于密钥托管
 * - 写入内容：serverSpecification / toolSpecification / endpointSpecification
 * - 幂等：apply 前先 read 比对 CanonicalJson hash，相同直接返回
 * - 错误信息要具体：「写入 Nacos 失败：命名空间 prod 不存在。到 设置 → Nacos 连接 检查」
 */
@Component
public class NacosTarget implements DeployTarget {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String CONFIG_PATH = "/v3/admin/cs/config";
    private static final String MCP_PATH = "/v3/admin/ai/mcp";
    private static final String INSTANCE_LIST_PATH = "/v3/admin/ns/instance/list";
    private static final String LOGIN_PATH = "/v3/auth/user/login";
    private static final String STATE_V3_PATH = "/v3/admin/core/state";
    private static final String SNAPSHOT_MARKER = "_gateforgeNacosMcpSnapshot";
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
    @Value("${zhuque.nacos.mcp-visible-timeout:PT30S}")
    private Duration mcpVisibleTimeout = Duration.ofSeconds(30);
    @Value("${zhuque.nacos.mcp-poll-interval:PT0.25S}")
    private Duration mcpPollInterval = Duration.ofMillis(250);
    @Value("${zhuque.nacos.mcp-delete-settle-window:PT2S}")
    private Duration mcpDeleteSettleWindow = Duration.ofSeconds(2);

    private volatile String accessToken;

    @Override
    public String name() {
        return "nacos";
    }

    /** 功能：把冻结载荷创建/全量更新到 Nacos AI MCP Registry。 */
    @Override
    public void apply(UUID releaseId, Map<String, Object> payload) {
        String mcpName = required(payload, "mcpName");
        Map<String, Object> desired = object(payload.get("service"));
        Map<String, Object> before = read(mcpName);
        Map<String, Object> current = snapshotService(before);
        if (current != null && CanonicalJson.sha256(current).equals(CanonicalJson.sha256(desired))) {
            return;
        }
        writeMcp(mcpName, desired, before == null ? null : String.valueOf(before.get("mcpId")));
    }

    /**
     * 功能：读取 MCP Registry 当前状态并归一化为冻结载荷的 service 形状。
     * 返回值同时携带 mcpId 供事务恢复使用；404 表示首次发布前不存在。
     */
    @Override
    public Map<String, Object> read(String agentSlugName) {
        String mcpName = stripJsonSuffix(agentSlugName);
        HttpResponse<String> response = request("GET", MCP_PATH + mcpQuery(mcpName), null);
        if (isResourceNotFound(response)) {
            return null;
        }
        requireSuccess(response, "读取 Nacos MCP 服务 " + mcpName);
        return snapshot(responseData(response.body()));
    }

    /**
     * Nacos 创建 MCP Server 后 detail 最终一致。发布成功前在有界时间内确认
     * Registry 已可读；这里只验证 Nacos，不推导或操作任何 Higress 资源。
     */
    public Map<String, Object> awaitMcpVisible(String mcpName) {
        String normalizedName = stripJsonSuffix(mcpName);
        long timeoutNanos = Math.max(0L, mcpVisibleTimeout.toNanos());
        long deadline = saturatingAdd(System.nanoTime(), timeoutNanos);
        RuntimeException lastIncomplete = null;
        while (true) {
            HttpResponse<String> response = request("GET", MCP_PATH + mcpQuery(normalizedName), null);
            if (!isResourceNotFound(response)) {
                requireSuccess(response, "确认 Nacos MCP detail 可见 " + normalizedName);
                try {
                    // MCP detail 与它引用的 naming endpoint 并非同时可见。只有二者都能
                    // 完整读回才算 Registry 发布完成，避免把短暂 cluster-not-found 当失败。
                    return snapshot(responseData(response.body()));
                } catch (RuntimeException incomplete) {
                    lastIncomplete = incomplete;
                }
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw unavailable("等待 Nacos MCP detail 可见超时（" + mcpVisibleTimeout
                        + "）：" + normalizedName + (lastIncomplete == null ? ""
                        : "；最后状态：" + lastIncomplete.getMessage()));
            }
            sleepForPoll(remainingNanos, "等待 Nacos MCP detail 可见：" + normalizedName);
        }
    }

    private Map<String, Object> snapshot(Map<String, Object> detail) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(SNAPSHOT_MARKER, true);
        snapshot.put("mcpId", required(detail, "id"));
        snapshot.put("service", normalizedService(detail));
        return snapshot;
    }

    /** 功能：恢复快照：snapshot==null 则删除新建的 MCP，否则全量写回旧版本。 */
    @Override
    public void restore(String agentSlugName, Map<String, Object> snapshot) {
        String mcpName = stripJsonSuffix(agentSlugName);
        if (snapshot == null) {
            deleteMcpEventually(mcpName);
            return;
        }
        Map<String, Object> service = snapshotService(snapshot);
        if (service == null) {
            throw ApiException.badRequest("Nacos MCP 快照缺少 service", "停止自动恢复并人工对账 Nacos Registry");
        }
        Map<String, Object> current = read(mcpName);
        String id = current == null ? null : String.valueOf(current.get("mcpId"));
        writeMcp(mcpName, service, id);
    }

    public String probeVersion() {
        HttpResponse<String> response = request("GET", STATE_V3_PATH, null);
        requireSuccess(response, "探测 Nacos 版本");
        Map<String, Object> state = object(response.body());
        Object data = state.getOrDefault("data", state);
        if (data instanceof Map<?, ?> map) {
            Object version = map.get("version");
            return version == null ? "unknown" : String.valueOf(version);
        }
        return String.valueOf(state.getOrDefault("version", "unknown"));
    }

    private void writeMcp(String mcpName, Map<String, Object> service, String mcpId) {
        Map<String, Object> server = new LinkedHashMap<>(object(service.get("serverSpecification")));
        Map<String, Object> tools = object(service.get("toolSpecification"));
        Map<String, Object> endpoint = object(service.get("endpointSpecification"));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("namespaceId", namespace);
        fields.put("mcpName", mcpName);
        fields.put("toolSpecification", json(tools));
        fields.put("endpointSpecification", json(endpoint));
        String method = "POST";
        if (mcpId != null && !mcpId.isBlank() && !"null".equals(mcpId)) {
            server.put("id", mcpId);
            fields.put("latest", "true");
            fields.put("overrideExisting", "true");
            method = "PUT";
        }
        fields.put("serverSpecification", json(server));
        HttpResponse<String> response = request(method, MCP_PATH, form(fields));
        requireSuccess(response, ("POST".equals(method) ? "创建" : "更新") + " Nacos MCP 服务 " + mcpName);
    }

    /**
     * Nacos MCP create 是最终一致的：POST 成功后 detail 可能暂时 404。因此首次
     * DELETE 看到 404 不能宣布回滚完成，必须在整个可见性窗口内持续删除。
     * 一旦观察到资源，只有连续缺失达到 settle window 才算完全撤回。
     */
    private void deleteMcpEventually(String mcpName) {
        long started = System.nanoTime();
        long visibleNanos = Math.max(0L, mcpVisibleTimeout.toNanos());
        long settleNanos = Math.max(0L, mcpDeleteSettleWindow.toNanos());
        long visibilityDeadline = saturatingAdd(started, visibleNanos);
        long hardDeadline = saturatingAdd(visibilityDeadline, settleNanos);
        boolean observed = false;
        long missingSince = -1L;
        while (true) {
            HttpResponse<String> response = request("DELETE", MCP_PATH + mcpQuery(mcpName), null);
            long now = System.nanoTime();
            if (isResourceNotFound(response)) {
                if (missingSince < 0L) {
                    missingSince = now;
                }
                boolean settled = now - missingSince >= settleNanos;
                if ((observed && settled) || (!observed && now >= visibilityDeadline && settled)) {
                    return;
                }
            } else {
                requireSuccess(response, "删除 Nacos MCP 服务 " + mcpName);
                observed = true;
                missingSince = -1L;
            }
            long deadline = observed ? hardDeadline : visibilityDeadline;
            long remainingNanos = deadline - now;
            if (remainingNanos <= 0L) {
                throw unavailable("Nacos MCP 服务在回滚窗口内未持续缺失：" + mcpName);
            }
            sleepForPoll(remainingNanos, "等待 Nacos MCP 删除稳定：" + mcpName);
        }
    }

    private void sleepForPoll(long remainingNanos, String action) {
        long configuredNanos = Math.max(1L, mcpPollInterval.toNanos());
        long sleepNanos = Math.min(configuredNanos, remainingNanos);
        long millis = Math.max(1L, (sleepNanos + 999_999L) / 1_000_000L);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable(action + "时被中断");
        }
    }

    private static long saturatingAdd(long left, long right) {
        long result = left + right;
        return right > 0L && result < left ? Long.MAX_VALUE : result;
    }

    private Map<String, Object> normalizedService(Map<String, Object> detail) {
        Map<String, Object> version = objectOrEmpty(detail.get("versionDetail"));
        Map<String, Object> remote = objectOrEmpty(detail.get("remoteServerConfig"));
        Map<String, Object> server = ordered(
                "name", detail.getOrDefault("name", ""),
                "protocol", detail.getOrDefault("protocol", ""),
                "frontProtocol", detail.getOrDefault("frontProtocol", ""),
                "description", detail.getOrDefault("description", ""),
                "versionDetail", ordered("version", version.getOrDefault("version",
                        detail.getOrDefault("version", ""))),
                "remoteServerConfig", ordered("exportPath", remote.getOrDefault("exportPath", "")),
                "enabled", !Boolean.FALSE.equals(detail.get("enabled")));
        Map<String, Object> toolSpec = objectOrEmpty(detail.get("toolSpec"));
        Map<String, Object> endpoint = readEndpoint(remote);
        return ordered("serverSpecification", server,
                "toolSpecification", removeNulls(toolSpec),
                "endpointSpecification", endpoint);
    }

    private Map<String, Object> readEndpoint(Map<String, Object> remote) {
        Map<String, Object> ref = objectOrEmpty(remote.get("serviceRef"));
        String refNamespace = String.valueOf(ref.getOrDefault("namespaceId", namespace));
        String group = required(ref, "groupName");
        String service = required(ref, "serviceName");
        String query = "?" + form(Map.of("namespaceId", refNamespace,
                "groupName", group, "serviceName", service));
        HttpResponse<String> response = request("GET", INSTANCE_LIST_PATH + query, null);
        requireSuccess(response, "读取 Nacos MCP 后端 endpoint " + service);
        Object data = object(response.body()).get("data");
        if (!(data instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> raw)) {
            throw unavailable("Nacos MCP 后端 endpoint 为空：" + service);
        }
        Map<String, Object> instance = cast(raw);
        String address = required(instance, "ip");
        Object portValue = instance.get("port");
        String port = portValue instanceof Number number
                ? String.valueOf(number.intValue()) : String.valueOf(portValue);
        return ordered("type", "DIRECT", "data", ordered("address", address, "port", port));
    }

    @SuppressWarnings("unchecked")
    private static Object removeNulls(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                if (item != null) {
                    result.put(String.valueOf(key), removeNulls(item));
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> {
                if (item != null) {
                    result.add(removeNulls(item));
                }
            });
            return result;
        }
        return value;
    }

    private static Map<String, Object> snapshotService(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Object value = Boolean.TRUE.equals(snapshot.get(SNAPSHOT_MARKER)) ? snapshot.get("service") : snapshot;
        return value instanceof Map<?, ?> map ? cast(map) : null;
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

    private String mcpQuery(String mcpName) {
        return "?" + form(Map.of("namespaceId", namespace, "mcpName", mcpName));
    }

    private static String stripJsonSuffix(String name) {
        return name != null && name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
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
    private static Map<String, Object> objectOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Map<String, Object> responseData(String body) {
        Map<String, Object> envelope = object(body);
        Object data = envelope.get("data");
        if (data instanceof Map<?, ?> map) {
            return cast(map);
        }
        throw unavailable("Nacos 响应缺少对象 data");
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

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    private static void requireSuccess(HttpResponse<String> response, String action) {
        if (!isSuccess(response)) {
            throw unavailable(action + "失败（HTTP " + response.statusCode() + "）：" + response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return;
        }
        try {
            Map<String, Object> envelope = JSON.readValue(response.body(), MAP);
            Object code = envelope.get("code");
            if (code instanceof Number number && number.intValue() != 0) {
                throw unavailable(action + "失败（Nacos code=" + number.intValue() + "）："
                        + envelope.getOrDefault("message", response.body()));
            }
        } catch (ApiException error) {
            throw error;
        } catch (Exception ignored) {
            // Config Center 的部分成功接口返回 true/纯文本；HTTP 2xx 即可。
        }
    }

    private static boolean isResourceNotFound(HttpResponse<String> response) {
        if (response.statusCode() != 404 || response.body() == null || response.body().isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = JSON.readValue(response.body(), MAP);
            Object code = body.get("code");
            return code instanceof Number number && number.intValue() == 20004;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSuccess(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static ApiException unavailable(String what) {
        return ApiException.unavailable(what, "到 设置 → Nacos 连接检查地址、命名空间和凭据（要求 ≥ 3.0.1 Admin API）");
    }
}
