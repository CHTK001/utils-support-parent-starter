// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.agent.opencode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * opencode server HTTP 客户端。
 *
 * <p>面向 opencode 的 v2 agent API（{@code /api/session*}）与全局接口，负责：</p>
 * <ul>
 *   <li>统一拼接 baseUrl、Basic Auth 头与目录作用域头</li>
 *   <li>JSON 收发并把非 2xx 响应翻译成 {@link OpencodeApiException}</li>
 *   <li>为 SSE 事件流提供“不设请求超时”的开流入口</li>
 * </ul>
 *
 * <h3>鉴权</h3>
 * <p>服务端由 {@code OPENCODE_SERVER_PASSWORD} 开启 HTTP Basic Auth，用户名默认
 * {@code opencode}（可用 {@code OPENCODE_SERVER_USERNAME} 覆盖）。密码为空时不发送
 * {@code Authorization} 头，仅适用于本机无鉴权的调试场景。</p>
 *
 * <h3>目录作用域</h3>
 * <p>v2 的目录作用域由会话创建时的 {@code location.directory} 决定，普通会话接口按
 * 会话 ID 即可定位，因此本客户端<b>不强制</b>发送 {@code x-opencode-directory}；
 * 仅当目录为纯 ASCII 路径时额外携带该头，兼容 openchamber 使用的同款约定。
 * 非 ASCII 路径（如含中文的 Windows 目录）无法放进 HTTP 头，此时跳过该头，
 * 由 {@code location} 负责作用域。</p>
 *
 * <h3>线程安全</h3>
 * <p>底层 {@link HttpClient} 与字段均为不可变，可被多个虚拟线程并发复用。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public final class OpencodeHttpClient {

    /**
     * JSON 序列化器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 目录作用域请求头（openchamber 兼容）
     */
    private static final String HEADER_DIRECTORY = "x-opencode-directory";

    /**
     * 鉴权请求头
     */
    private static final String HEADER_AUTHORIZATION = "Authorization";

    /**
     * JSON Content-Type
     */
    private static final String CONTENT_TYPE_JSON = "application/json";

    /**
     * SSE Accept
     */
    private static final String CONTENT_TYPE_SSE = "text/event-stream";

    /**
     * 普通请求超时（SSE 开流不走该超时）
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60L);

    /**
     * 建连超时
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10L);

    /**
     * 错误响应体截断长度（避免日志被大段响应体淹没）
     */
    private static final int ERROR_BODY_LIMIT = 2048;

    /**
     * 默认 Basic Auth 用户名
     */
    private static final String DEFAULT_USERNAME = "opencode";

    /**
     * HTTP 客户端
     */
    private final HttpClient httpClient;

    /**
     * 服务基地址（形如 {@code http://127.0.0.1:4096}，不带尾部斜杠）
     */
    private final String baseUrl;

    /**
     * Basic Auth 头值；无密码时为 null
     */
    private final String authorization;

    /**
     * 目录作用域头值；非 ASCII 或未设置时为 null
     */
    private final String directory;

    /**
     * 构造客户端。
     *
     * @param baseUrl   服务基地址（不带尾部斜杠）
     * @param username  Basic Auth 用户名，空值回退 {@code opencode}
     * @param password  Basic Auth 密码，空值表示不发送鉴权头
     * @param directory 会话工作目录，用于携带 {@code x-opencode-directory} 兼容头
     */
    public OpencodeHttpClient(String baseUrl, String username, String password, String directory) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.authorization = buildAuthorization(username, password);
        this.directory = isHeaderSafe(directory) ? directory : null;
    }

    /**
     * GET 并解析 JSON 响应体。
     *
     * @param path 以 {@code /} 开头的接口路径（可带查询串）
     * @return 响应 JSON 根节点
     */
    public JsonNode getJson(String path) {
        HttpRequest request = newRequest(path, true).GET().build();
        return sendJson(request);
    }

    /**
     * POST JSON 并解析 JSON 响应体。
     *
     * @param path 接口路径
     * @param body 请求体，由 Jackson 序列化
     * @return 响应 JSON 根节点
     */
    public JsonNode postJson(String path, Object body) {
        HttpRequest request = newRequest(path, true)
                .header("Content-Type", CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();
        return sendJson(request);
    }

    /**
     * POST JSON，仅校验状态码（用于 204 No Content 接口）。
     *
     * @param path 接口路径
     * @param body 请求体；{@code null} 表示无请求体
     */
    public void postNoContent(String path, Object body) {
        HttpRequest.Builder builder = newRequest(path, true);
        HttpRequest request;
        if (body == null) {
            request = builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        } else {
            request = builder.header("Content-Type", CONTENT_TYPE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                    .build();
        }
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(request, response.statusCode(), response.body());
        } catch (IOException e) {
            throw new OpencodeApiException("opencode 请求失败: " + request.method() + " " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpencodeApiException("opencode 请求被中断: " + request.uri(), e);
        }
    }

    /**
     * 打开 SSE 事件流。
     *
     * <p>SSE 是长连接，<b>不能</b>设置请求总超时，否则连接会被客户端主动掐断；
     * 连接由调用方负责关闭返回的输入流。</p>
     *
     * @param path 事件流路径（可带 {@code after} 游标）
     * @return 状态码为 2xx 的流式响应
     */
    public HttpResponse<InputStream> openStream(String path) {
        HttpRequest request = newRequest(path, false)
                .header("Accept", CONTENT_TYPE_SSE)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (!isSuccess(response.statusCode())) {
                try (InputStream errorBody = response.body()) {
                    ensureSuccess(request, response.statusCode(),
                            new String(errorBody.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return response;
        } catch (IOException e) {
            throw new OpencodeApiException("opencode 事件流连接失败: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpencodeApiException("opencode 事件流连接被中断: " + request.uri(), e);
        }
    }

    /**
     * 发送并解析 JSON。
     *
     * @param request 已完成构建的请求
     * @return 响应 JSON 根节点；空响应体返回空对象节点
     */
    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(request, response.statusCode(), response.body());
            String body = response.body();
            if (body == null || body.isBlank()) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new OpencodeApiException("opencode 请求失败: " + request.method() + " " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpencodeApiException("opencode 请求被中断: " + request.uri(), e);
        }
    }

    /**
     * 构建请求；统一注入鉴权与目录作用域头。
     *
     * @param path        接口路径
     * @param withTimeout 是否设置普通请求超时（SSE 必须为 false）
     * @return 请求构建器
     */
    private HttpRequest.Builder newRequest(String path, boolean withTimeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", CONTENT_TYPE_JSON);
        if (withTimeout) {
            builder.timeout(REQUEST_TIMEOUT);
        }
        if (authorization != null) {
            builder.header(HEADER_AUTHORIZATION, authorization);
        }
        if (directory != null) {
            builder.header(HEADER_DIRECTORY, directory);
        }
        return builder;
    }

    /**
     * 校验响应状态码，非 2xx 抛 {@link OpencodeApiException}。
     *
     * @param request    请求（仅用于错误信息）
     * @param statusCode 状态码
     * @param body       响应体
     */
    private void ensureSuccess(HttpRequest request, int statusCode, String body) {
        if (isSuccess(statusCode)) {
            return;
        }
        String snippet = body == null ? "" : body;
        if (snippet.length() > ERROR_BODY_LIMIT) {
            snippet = snippet.substring(0, ERROR_BODY_LIMIT);
        }
        throw new OpencodeApiException(statusCode, snippet,
                "opencode 接口调用失败: " + request.method() + " " + request.uri()
                        + " -> HTTP " + statusCode + " " + snippet);
    }

    /**
     * 是否 2xx。
     *
     * @param statusCode 状态码
     * @return 是否成功
     */
    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * 序列化请求体。
     *
     * @param body 请求体
     * @return JSON 文本
     */
    private static String toJson(Object body) {
        if (body == null) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new OpencodeApiException("序列化请求体失败", e);
        }
    }

    /**
     * 构造 Basic Auth 头值。
     *
     * @param username 用户名
     * @param password 密码
     * @return 头值；密码为空返回 null
     */
    private static String buildAuthorization(String username, String password) {
        if (password == null || password.isBlank()) {
            return null;
        }
        String user = (username == null || username.isBlank()) ? DEFAULT_USERNAME : username.trim();
        String raw = user + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 规范化基地址（去掉尾部斜杠）。
     *
     * @param baseUrl 基地址
     * @return 规范化结果
     */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("opencode baseUrl 不能为空");
        }
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 判断值能否安全放入 HTTP 头（可打印 ASCII）。
     *
     * @param value 待判断值
     * @return 是否可安全放入请求头
     */
    private static boolean isHeaderSafe(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch > 0x7E) {
                log.debug("[opencode] 目录含非 ASCII 字符，跳过 {} 请求头: {}", HEADER_DIRECTORY, value);
                return false;
            }
        }
        return true;
    }
}