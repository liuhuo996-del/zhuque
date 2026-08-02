package com.zhuque.m8_deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Higress 合约测试 —— 版本升级的第二道闸。
 *
 * ============================================================
 * 为什么存在
 * ============================================================
 * 「把 Higress 知识关在一个类里」只让修复成本可控，不阻止损坏发生。
 * Higress 约 1~2 个月发一版，且 MCP 相关接口正在活跃演进
 * （2.2.3 的更新日志就有 "Add type field to the MCP server config json"）。
 *
 * 本测试对着真容器断言接口形状。Higress 一改，这里立刻红——
 * 在联调阶段暴露，而不是等生产发布时炸。
 *
 * ============================================================
 * 怎么跑
 * ============================================================
 *   docker run -d --name higress-ai -p 8001:8001 -p 8080:8080 -p 8443:8443 \
 *     -e SPRINGDOC_APIDOCS_ENABLED=true \
 *     higress-registry.cn-hangzhou.cr.aliyuncs.com/higress/all-in-one:2.2.3
 *   mvn test -Dtest=HigressContractTest
 *
 * 没有容器时自动跳过（Assumptions），不阻塞日常构建。
 * CI 里应固定镜像 tag 跑——用 :latest 会让"测试变红"和
 * "Higress 发新版"这两件事混在一起，失去定位能力。
 *
 * ============================================================
 * 升 Higress 版本时的动作
 * ============================================================
 * 1. 改 PINNED_VERSION，跑本测试
 * 2. 全绿 → 改 application.yml 的 min-version，完事
 * 3. 有红 → 看红在哪条，只改 HigressAuthTarget 对应的那一处
 * 无论哪种，改动都不会溢出这两个文件。
 */
@DisplayName("Higress 接口合约（对真容器）")
class HigressContractTest {

    private static final String CONSOLE = System.getProperty("higress.url", "http://localhost:8001");
    /** 本测试验证过的版本。升级时改这里，跑一遍，看哪条红。 */
    private static final String PINNED_VERSION = "2.2.3";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode apiDocs;

    @BeforeAll
    static void requireLiveConsole() throws Exception {
        HttpResponse<String> resp;
        try {
            resp = get("/system/info");
        } catch (Exception e) {
            Assumptions.abort("Higress 未运行，跳过合约测试。启动方式见类注释");
            return;
        }
        Assumptions.assumeTrue(resp.statusCode() == 200, "Higress 未就绪，跳过");

        HttpResponse<String> docs = get("/v3/api-docs");
        Assumptions.assumeTrue(
                isJson(docs),
                "/v3/api-docs 不可用：启动容器时加 -e SPRINGDOC_APIDOCS_ENABLED=true");
        apiDocs = JSON.readTree(docs.body());
    }

    // ------------------------------------------------------------ 前置约束

    @Test
    @DisplayName("版本仍是验证过的版本（变了说明该重跑全部合约）")
    void versionIsPinned() throws Exception {
        JsonNode info = JSON.readTree(get("/system/info").body());
        String actual = info.path("version").asText();
        assertEquals(PINNED_VERSION, actual,
                "Higress 版本变了（" + PINNED_VERSION + " → " + actual + "）。"
                        + "确认下面各条合约仍成立后，更新 PINNED_VERSION 与 application.yml 的 min-version");
    }

    // -------------------------------------------------- 坑 #2 的回归防护

    @Test
    @DisplayName("SPA 兜底陷阱仍然存在——判断接口存在绝不能只看状态码")
    void spaFallbackReturns200ForNonexistentPaths() throws Exception {
        HttpResponse<String> ghost = get("/definitely-not-a-real-endpoint-" + System.nanoTime());
        assertEquals(200, ghost.statusCode(),
                "控制台不再用 SPA 兜底了？那 HigressAuthTarget.assertJson 的必要性需要重新评估");
        assertTrue(!isJson(ghost),
                "兜底页返回了 JSON——assertJson 的判据（Content-Type）失效，需换判据");
    }

    // ------------------------------------------------ HigressAuthTarget 依赖

    @Test
    @DisplayName("apply/read/restore 依赖的接口全部存在")
    void requiredEndpointsExist() {
        JsonNode paths = apiDocs.path("paths");
        record Ep(String path, String method, String usedBy) {}
        List<Ep> required = List.of(
                new Ep("/session/login", "post", "ensureSession"),
                new Ep("/v1/consumers", "post", "apply ①新建消费者"),
                new Ep("/v1/consumers/{name}", "put", "apply ①幂等替换消费者"),
                new Ep("/v1/mcpServer/consumers", "get", "read 快照"),
                new Ep("/v1/mcpServer/consumers", "put", "apply ②授权 / restore 恢复"),
                new Ep("/v1/mcpServer/consumers", "delete", "restore 快照为 null 时删除"));
        for (Ep ep : required) {
            assertTrue(paths.path(ep.path()).has(ep.method()),
                    ep.method().toUpperCase() + " " + ep.path() + " 不存在了。"
                            + "HigressAuthTarget." + ep.usedBy() + " 会失效，需改那一处");
        }
    }

    @Test
    @DisplayName("请求体字段名未变（漏字段会静默写错配置，比报错更危险）")
    void requestSchemasUnchanged() {
        assertSchemaHasFields("LoginRequest", Set.of("username", "password"));
        assertSchemaHasFields("Consumer", Set.of("name", "credentials"));
        assertSchemaHasFields("KeyAuthCredential", Set.of("source", "key", "values"));
        assertSchemaHasFields("McpServerConsumers", Set.of("mcpServerName", "consumers"));
    }

    @Test
    @DisplayName("PUT 语义仍是整体替换——apply 的幂等性建立在这上面")
    void putEndpointsAcceptFullBody() {
        JsonNode put = apiDocs.path("paths").path("/v1/mcpServer/consumers").path("put");
        assertNotNull(put.path("requestBody"),
                "PUT /v1/mcpServer/consumers 不再接受完整请求体？"
                        + "若改成 PATCH 语义，apply 将不再幂等，重复发布会累积而非替换");
        JsonNode delete = apiDocs.path("paths").path("/v1/mcpServer/consumers").path("delete");
        assertNotNull(delete.path("requestBody"),
            "DELETE /v1/mcpServer/consumers 不再接受 McpServerConsumers 请求体，restore 需同步调整");
    }

    @Test
    @DisplayName("鉴权仍是 session，且未登录访问被真正拒绝")
    void authIsStillSessionBased() throws Exception {
        assertTrue(apiDocs.path("paths").path("/session/login").has("post"),
                "登录方式变了（比如改成 OAuth/token），ensureSession 需重写");
        HttpResponse<String> unauth = get("/v1/consumers");
        assertEquals(401, unauth.statusCode(), "未登录访问竟未被拒绝——鉴权模型变了");
        assertTrue(isJson(unauth), "401 应是真 JSON 响应，不是兜底页");
    }

    // ---------------------------------------------------------------- 工具

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(CONSOLE + path))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 见 HigressAuthTarget 坑 #2：状态码不可信，Content-Type 才是判据。 */
    private static boolean isJson(HttpResponse<String> resp) {
        return resp.headers().firstValue("content-type")
                .map(ct -> ct.contains("application/json")).orElse(false);
    }

    private void assertSchemaHasFields(String schema, Set<String> fields) {
        JsonNode definition = apiDocs.path("components").path("schemas").path(schema);
        assertTrue(!definition.isMissingNode(), "schema " + schema + " 不存在了");
        for (String f : fields) {
            assertTrue(schemaHasField(definition, f, new java.util.HashSet<>()),
                    schema + "." + f + " 字段没了。HigressAuthTarget 构造该请求体的代码需同步");
        }
    }

    private boolean schemaHasField(JsonNode definition, String field, Set<String> visitedRefs) {
        if (definition.path("properties").has(field)) {
            return true;
        }
        String ref = definition.path("$ref").asText();
        if (!ref.isBlank() && visitedRefs.add(ref)) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            if (schemaHasField(apiDocs.path("components").path("schemas").path(name), field, visitedRefs)) {
                return true;
            }
        }
        for (String composition : List.of("allOf", "oneOf", "anyOf")) {
            for (JsonNode child : definition.path(composition)) {
                if (schemaHasField(child, field, visitedRefs)) {
                    return true;
                }
            }
        }
        return false;
    }
}
