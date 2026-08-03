package com.zhuque.m6_testing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;
import com.zhuque.common.JobRegistry;
import com.zhuque.persistence.ControlPlaneRepository;

/**
 * M6-L1 · 正式上游契约测试。所有 case 都针对 Release 冻结的真实 requestTemplate，
 * 不提供内置模拟目标，也不会伪造鉴权成功证据。
 *
 * 只有同时满足以下条件的工具才会被自动调用：人工复核（enrichmentStatus=reviewed）、
 * effect=read、GET/HEAD/OPTIONS，且冻结 requestTemplate 含
 * x-zhuque-l1={testSafe:true,fixture:"稳定 fixture 标识"}。这个显式标记防止把测试环境
 * 中仍可能触发真实业务副作用的 endpoint 当作安全读接口。unknown 绝不自动调用。
 * 即便来源标记为 test/staging，真实请求的 origin 也必须在服务端配置的允许列表中；空列表
 * 明确拒绝全部出网，不会因为开发便利而默认允许生产或任意内网地址。
 *
 * 有副作用的方法不自动调用：没有受控 fixture、清理与回读断言时，“重复两次 2xx”不是
 * 幂等证据，反而可能写入真实业务数据。入口 key-auth 属于发布后的 Higress 数据面，
 * 不在发布前伪装成已测。
 */
@Component
public class L1ContractTester {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            // 不能让受测目标借 30x 把 L1 转发到未审核的第二个 origin。
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ControlPlaneRepository repository;
    private final JobRegistry jobs;
    private final Executor executor;
    private final Set<String> allowedOrigins;

    @Autowired
    public L1ContractTester(ControlPlaneRepository repository, JobRegistry jobs,
                            @Qualifier("testingExecutor") Executor executor,
                            @Value("${zhuque.testing.allowed-origins:}") String configuredAllowedOrigins) {
        this.repository = repository;
        this.jobs = jobs;
        this.executor = executor;
        this.allowedOrigins = parseAllowedOrigins(configuredAllowedOrigins);
    }

    /** 便于无 Spring 上下文的单元测试；空白名单仍按正式默认值拒绝 L1 出网。 */
    public L1ContractTester(ControlPlaneRepository repository, JobRegistry jobs,
                            @Qualifier("testingExecutor") Executor executor) {
        this(repository, jobs, executor, "");
    }

    public String run(UUID releaseId, String target) {
        var release = repository.requireReleaseTestable(releaseId);
        String selected = target == null || target.isBlank() ? "live" : target.trim();
        if (!"live".equals(selected)) {
            throw ApiException.badRequest("未知 L1 target=" + selected,
                    "正式契约测试仅支持 live；请为 API 来源配置 test/staging 环境");
        }
        List<Map<String, Object>> tools = maps(release.manifest().get("tools"));
        failStaleRunIfAny(releaseId);
        String jobId = jobs.start(Math.max(1, tools.size()), "准备 L1 正式上游契约测试");
        try {
            repository.beginTestRun(releaseId, "L1", jobId, expectedReportCount(tools));
        } catch (RuntimeException error) {
            jobs.fail(jobId, "无法启动 L1 正式上游契约测试", error);
            throw error;
        }
        try {
            executor.execute(() -> execute(releaseId, tools, jobId));
        } catch (RuntimeException rejection) {
            // beginTestRun 已经持久化；若线程池拒绝任务，必须同步结束它，不能把 Release 永久
            // 留在 running 状态。即使写失败也要把进程内 job 标记失败，供操作者看见。
            try {
                repository.failTestRun(releaseId, "L1", jobId,
                        "L1 任务未能提交到执行器：" + safeFailure(rejection));
            } catch (RuntimeException persistenceFailure) {
                rejection.addSuppressed(persistenceFailure);
            } finally {
                jobs.fail(jobId, "L1 正式上游契约测试未能启动", rejection);
            }
            throw ApiException.unavailable("L1 正式上游契约测试无法排队",
                    "检查测试执行器容量后重试；本次任务已标记失败");
        }
        return jobId;
    }

    private void execute(UUID releaseId, List<Map<String, Object>> tools, String jobId) {
        try {
            // 异步排队期间 Release 可能被退役或离开 candidate，启动工作时再次确认。
            repository.requireReleaseTestable(releaseId);
            if (tools.isEmpty()) {
                report(releaseId, jobId, "L1-empty-release", "fail", "Release 没有工具", 0, Map.of());
                repository.completeTestRun(releaseId, "L1", jobId);
                jobs.done(jobId, "L1 完成：Release 没有工具");
                return;
            }
            int done = 0;
            for (Map<String, Object> tool : tools) {
                testTool(releaseId, jobId, tool);
                jobs.update(jobId, ++done, "已实测 " + tool.get("name"));
            }
            repository.completeTestRun(releaseId, "L1", jobId);
            jobs.done(jobId, "L1 正式上游契约测试完成");
        } catch (Throwable error) {
            repository.failTestRun(releaseId, "L1", jobId, "L1 任务异常中止：" + safeFailure(error));
            jobs.fail(jobId, "L1 正式上游契约测试失败", error);
        }
    }

    private void testTool(UUID releaseId, String jobId, Map<String, Object> tool) {
        // 每个工具前均重新校验，防止退役/状态切换后继续对外发请求。
        repository.requireReleaseTestable(releaseId);
        String name = String.valueOf(tool.get("name"));
        Map<String, Object> schema = map(tool.get("inputSchema"));
        Map<String, Object> template = map(tool.get("requestTemplate"));
        List<String> required = strings(schema.get("required"));
        boolean mapped = required.stream().allMatch(field -> template.toString().contains(".args." + field));
        report(releaseId, jobId, "L1-mapping-" + name, mapped ? "pass" : "fail",
                mapped ? "全部必填参数已进入冻结的请求模板" : "存在未映射必填参数", 0,
                Map.of("required", required));

        String method = String.valueOf(template.getOrDefault("method", tool.getOrDefault("method", "GET")))
                .toUpperCase(Locale.ROOT);
        String effect = String.valueOf(tool.getOrDefault("effect", "unknown"));
        boolean reviewed = "reviewed".equals(tool.get("enrichmentStatus"));
        boolean readEffect = "read".equals(effect);
        boolean safeReadMethod = List.of("GET", "HEAD", "OPTIONS").contains(method);
        boolean controlledFixture = hasControlledFixture(template);

        // 默认拒绝：来源是 test/staging 仍不足以说明某个 endpoint 可无人工值守地调用。
        // 本段在读取来源与构建网络请求之前执行，unknown/effect 非 read 不会到达 send()。
        if (!reviewed || !readEffect || !safeReadMethod || !controlledFixture) {
            List<String> missing = new java.util.ArrayList<>();
            if (!reviewed) missing.add("工具未人工复核");
            if (!readEffect) missing.add("effect 不是 read");
            if (!safeReadMethod) missing.add("HTTP method 不是 GET/HEAD/OPTIONS");
            if (!controlledFixture) missing.add("缺少 x-zhuque-l1.testSafe:true 与非空 fixture");
            report(releaseId, jobId, "L1-live-" + name, "fail",
                    "拒绝自动调用：" + String.join("；", missing), 0,
                    safeToolEvidence(method, effect, tool, template));
            report(releaseId, jobId, "L1-required-error-" + name, "skip",
                    "未满足受控只读自动调用条件，不执行缺参探测", 0, Map.of("method", method));
            report(releaseId, jobId, "L1-idempotency-" + name, readEffect ? "pass" : "fail",
                    readEffect ? "只读工具无需副作用幂等验证" : "非 read 工具不允许自动调用，未获得幂等证据", 0,
                    Map.of("method", method, "effect", effect));
            return;
        }

        var source = repository.requireApiSource(UUID.fromString(String.valueOf(tool.get("apiSourceId"))));
        boolean safeEnvironment = List.of("test", "staging").contains(source.envProfile().toLowerCase(Locale.ROOT));
        if (!safeEnvironment) {
            report(releaseId, jobId, "L1-live-" + name, "fail",
                    "正式 L1 只允许调用 test/staging 上游，拒绝自动探测 env_profile=" + source.envProfile(), 0,
                    Map.of("method", method, "envProfile", source.envProfile(),
                            "requestTemplateHash", CanonicalJson.sha256(template)));
            report(releaseId, jobId, "L1-required-error-" + name, "skip",
                    "非 test/staging 来源不执行自动缺参探测", 0, Map.of("method", method));
            report(releaseId, jobId, "L1-idempotency-" + name, "pass", "只读工具无需副作用幂等验证", 0,
                    Map.of("method", method, "envProfile", source.envProfile()));
            return;
        }

        Map<String, Object> args = sampleArgs(schema);
        try {
            PreparedRequest prepared = prepare(template, args);
            long started = System.nanoTime();
            UpstreamResponse response = send(releaseId, prepared);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            report(releaseId, jobId, "L1-live-" + name, ok ? "pass" : "fail",
                    "真实上游返回 HTTP " + response.statusCode(), durationMs,
                    responseEvidence(method, prepared.url(), response));

            if (required.isEmpty()) {
                report(releaseId, jobId, "L1-required-error-" + name, "skip",
                        "工具没有必填参数", 0, Map.of("method", method));
            } else {
                Map<String, Object> missingArgs = new LinkedHashMap<>(args);
                missingArgs.remove(required.get(0));
                PreparedRequest missingRequest = prepare(template, missingArgs);
                UpstreamResponse missing = send(releaseId, missingRequest);
                boolean structured = missing.statusCode() >= 400 && missing.statusCode() < 500
                        && isStructured(missing);
                report(releaseId, jobId, "L1-required-error-" + name, structured ? "pass" : "fail",
                        "缺少 " + required.get(0) + " 时真实上游返回 HTTP " + missing.statusCode(), 0,
                        responseEvidence(method, missingRequest.url(), missing));
            }

            report(releaseId, jobId, "L1-idempotency-" + name, "pass", "只读方法无需副作用幂等验证", 0,
                    Map.of("method", method));
        } catch (Exception error) {
            Map<String, Object> safeTemplate = Map.of("method", method,
                    "requestTemplateHash", CanonicalJson.sha256(template));
            report(releaseId, jobId, "L1-live-" + name, "fail", "真实上游调用失败：" + safeFailure(error), 0,
                    safeTemplate);
            report(releaseId, jobId, "L1-required-error-" + name, "fail",
                    "前置真实调用失败，未执行缺参探测", 0, safeTemplate);
            report(releaseId, jobId, "L1-idempotency-" + name, "pass", "只读方法无需副作用幂等验证", 0,
                    Map.of("method", method));
        }
    }

    private PreparedRequest prepare(Map<String, Object> template, Map<String, Object> args) throws Exception {
        String url = render(String.valueOf(template.getOrDefault("url", "")), args);
        URI uri = URI.create(url);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("requestTemplate.url 不是绝对地址：" + url);
        }
        String method = String.valueOf(template.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
        String body = template.get("body") == null ? null : render(String.valueOf(template.get("body")), args);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map<String, Object> header : maps(template.get("headers"))) {
            headers.put(String.valueOf(header.get("key")), render(String.valueOf(header.get("value")), args));
        }
        return new PreparedRequest(method, url, headers, body);
    }

    private UpstreamResponse send(UUID releaseId, PreparedRequest prepared) throws Exception {
        URI target = URI.create(prepared.url());
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        prepared.headers().forEach(builder::header);
        builder.method(prepared.method(), prepared.body() == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(prepared.body()));
        // 保持在真正出网的最后一刻校验；前一条 case 运行时 Release 也可能刚被退役。
        repository.requireReleaseTestable(releaseId);
        requireAllowedOrigin(target, allowedOrigins);
        HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body();
        byte[] bytes;
        if (body == null) {
            bytes = new byte[0];
        } else {
            try (body) {
                bytes = readBoundedBody(body);
            }
        }
        return new UpstreamResponse(response.statusCode(),
                response.headers().firstValue("content-type").orElse(""), bytes);
    }

    private static Map<String, Object> sampleArgs(Map<String, Object> schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map(schema.get("properties")).entrySet()) {
            Map<String, Object> definition = map(entry.getValue());
            Object value = definition.get("example");
            if (value == null) value = definition.get("default");
            List<?> enums = definition.get("enum") instanceof List<?> list ? list : List.of();
            if (value == null && !enums.isEmpty()) value = enums.get(0);
            String type = String.valueOf(definition.getOrDefault("type", "string"));
            if (value == null) value = switch (type) {
                case "integer" -> 1;
                case "number" -> 1.0;
                case "boolean" -> true;
                case "array" -> List.of("zhuque-test");
                case "object" -> Map.of("value", "zhuque-test");
                default -> "zhuque-test";
            };
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static String render(String template, Map<String, Object> args) throws Exception {
        String result = template;
        for (var entry : args.entrySet()) {
            String plain = String.valueOf(entry.getValue());
            String json = JSON.writeValueAsString(entry.getValue());
            result = result.replace("{{.args." + entry.getKey() + "}}", plain)
                    .replace("{{ .args." + entry.getKey() + " }}", plain)
                    .replace("{{ toJson .args." + entry.getKey() + " }}", json);
        }
        // 缺参探测：未提供的模板变量渲染为空值，让真实上游负责返回结构化 4xx。
        return result.replaceAll("\\{\\{\\s*(?:toJson\\s+)?\\.args\\.[^} ]+\\s*}}", "");
    }

    private void report(UUID releaseId, String jobId, String id, String result, String message, long durationMs,
                        Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>(extra);
        detail.put("message", message);
        detail.put("durationMs", durationMs);
        detail.put("target", "live");
        repository.insertTestReport(releaseId, "L1", jobId, id, result, detail, Map.of());
    }

    private static boolean isStructured(UpstreamResponse response) {
        return response.contentType().toLowerCase(Locale.ROOT).contains("json") && response.body().length > 0;
    }

    private static Map<String, Object> responseEvidence(String method, String url, UpstreamResponse response) {
        return responseEvidence(method, url, response.statusCode(), response.body());
    }

    /**
     * 仅保留响应的不可逆摘要，供测试报告审计；原始响应绝不写入控制面数据库。
     * 包可见以供无网络单元测试覆盖这个数据保留边界。
     */
    static Map<String, Object> responseEvidence(String method, String url, int statusCode, byte[] body) {
        byte[] safeBody = body == null ? new byte[0] : body;
        return Map.of("method", method, "endpointOrigin", endpointOrigin(url),
                "responseStatus", statusCode, "responseSha256", sha256(safeBody),
                "responseBytes", safeBody.length);
    }

    /** 读取到 1 MiB 即停止；不能因一个异常上游响应耗尽正式测试执行器内存。 */
    static byte[] readBoundedBody(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw ApiException.unavailable("L1 上游响应超过 1 MiB 限制",
                    "缩小测试 endpoint 的响应，或为该 endpoint 配置专用受控 fixture");
        }
        return bytes;
    }

    private static Map<String, Object> safeToolEvidence(String method, String effect,
                                                          Map<String, Object> tool,
                                                          Map<String, Object> template) {
        return Map.of("method", method, "effect", effect,
                "enrichmentStatus", String.valueOf(tool.getOrDefault("enrichmentStatus", "unknown")),
                "requestTemplateHash", CanonicalJson.sha256(template));
    }

    /**
     * 唯一允许的自动调用标记。它位于既有 requestTemplate JSON 中（和 x-arg-locations
     * 一样会被冻结到 Release），因而不需要扩展数据库表，也不会把 fixture 值落进报告。
     */
    private static boolean hasControlledFixture(Map<String, Object> template) {
        Map<String, Object> marker = map(template.get("x-zhuque-l1"));
        Object fixture = marker.get("fixture");
        return Boolean.TRUE.equals(marker.get("testSafe"))
                && fixture instanceof String value && !value.isBlank();
    }

    /**
     * 将配置值转换为严格的 origin 集合。允许末尾 `/`，默认端口会归一化；路径、查询、
     * 用户信息和非 HTTP(S) scheme 都不是 origin，启动时即拒绝，避免“看似白名单”的误配。
     */
    static Set<String> parseAllowedOrigins(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        Set<String> origins = new LinkedHashSet<>();
        for (String raw : configured.split(",")) {
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                URI uri = URI.create(value);
                if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || (uri.getRawPath() != null && !uri.getRawPath().isBlank()
                        && !"/".equals(uri.getRawPath()))) {
                    throw new IllegalArgumentException("必须是 scheme://host[:port]，不能带路径、查询、片段或用户信息");
                }
                origins.add(normalizeHttpOrigin(uri));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("zhuque.testing.allowed-origins 配置无效：" + value
                        + "（仅允许精确 http://host[:port] 或 https://host[:port] origin）", error);
            }
        }
        return Collections.unmodifiableSet(origins);
    }

    /** 出网前的第二道网络边界。空白名单是显式 deny-all，不存在隐式生产/本机例外。 */
    static void requireAllowedOrigin(URI target, Set<String> allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw ApiException.unavailable("L1 出站 origin 白名单未配置",
                    "设置 ZHUQUE_TESTING_ALLOWED_ORIGINS 为 test/staging 的精确 origin 后重试");
        }
        final String origin;
        try {
            origin = normalizeHttpOrigin(target);
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest("L1 请求地址不是允许的 HTTP(S) origin",
                    "修复冻结 requestTemplate.url，并使用 http:// 或 https:// 的绝对地址");
        }
        if (!allowedOrigins.contains(origin)) {
            throw ApiException.unavailable("L1 上游 origin 不在允许列表中",
                    "将该 test/staging origin 显式加入 ZHUQUE_TESTING_ALLOWED_ORIGINS 后重新运行");
        }
    }

    private static String normalizeHttpOrigin(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("不是完整 HTTP(S) origin");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        // URI#getHost 对 IPv6 返回未加方括号的 host；origin 文本须恢复标准 URI 形式。
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int declaredPort = uri.getPort();
        if (declaredPort > 65535) {
            throw new IllegalArgumentException("端口超出有效范围");
        }
        int port = declaredPort >= 0 ? declaredPort : ("https".equals(scheme) ? 443 : 80);
        return scheme + "://" + host + ":" + port;
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", impossible);
        }
    }

    /** 运行报告绝不落完整 URL、查询参数、请求体、响应体或异常原文。 */
    private static String endpointOrigin(String url) {
        try {
            URI uri = URI.create(url);
            int port = uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
        } catch (RuntimeException ignored) {
            return "<invalid-endpoint>";
        }
    }

    private static String safeFailure(Throwable error) {
        return error == null ? "未知错误" : error.getClass().getSimpleName();
    }

    private void failStaleRunIfAny(UUID releaseId) {
        var previous = repository.testRun(releaseId, "L1").orElse(null);
        if (previous == null || !"running".equals(previous.state())) {
            return;
        }
        com.zhuque.common.JobProgress progress;
        try {
            progress = jobs.get(previous.jobId());
        } catch (ApiException missingJob) {
            // JobRegistry 是进程内状态；若该记录已因重启消失，持久层必须明确把旧运行
            // 标记失败，不能让半途报告永久阻塞 Release。
            repository.failTestRun(releaseId, "L1", previous.jobId(), "控制面重启或任务状态丢失，原 L1 运行未完成");
            return;
        }
        if ("running".equals(progress.state())) {
            throw ApiException.conflict("L1 正式测试仍在运行", "等待当前任务完成或失败后再重跑");
        }
        repository.failTestRun(releaseId, "L1", previous.jobId(), "L1 任务未正常完成，请重新运行");
    }

    private static int expectedReportCount(List<Map<String, Object>> tools) {
        return tools.isEmpty() ? 1 : tools.size() * 4;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList() : List.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private record PreparedRequest(String method, String url, Map<String, String> headers, String body) {}
    private record UpstreamResponse(int statusCode, String contentType, byte[] body) {}
}
