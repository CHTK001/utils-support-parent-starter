package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.http.HttpVersion;
import com.chua.common.support.network.sse.SseClient;
import com.chua.common.support.network.sse.SseConnection;
import com.chua.common.support.network.sse.SseListener;
import com.chua.common.support.network.sse.SseRequest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 链式 HTTP 请求规格，绑定到 {@link HttpClient} 实例的轻量级请求构建器。
 *
 * <p>本类提供 <b>Fluent API</b>，支持从 {@link HttpClient} 接口直接链式创建和执行请求。
 * 与 {@link HttpClientBuilder} 的区别在于：</p>
 * <ul>
 *   <li><b>RequestSpec</b> — 绑定到已有 {@link HttpClient} 实例，使用完整 URL，轻量级链式构建</li>
 *   <li><b>HttpClientBuilder</b> — 通过 {@link HttpClientFactory#of(String)} 创建，使用 baseUrl + path 模式，功能更丰富（表单、文件上传）</li>
 * </ul>
 *
 * <p><b>典型用法：</b></p>
 * <pre>{@code
 * HttpClient client = HttpClientFactory.getClient();
 *
 * // GET 请求 + 链式配置
 * ClientResponse resp = client.get("http://api.example.com/users")
 *     .header("Authorization", "Bearer token")
 *     .header("Accept", "application/json")
 *     .query("page", "1")
 *     .cache(60000)
 *     .retry(3)
 *     .execute();
 *
 * // POST JSON 请求
 * ClientResponse resp = client.post("http://api.example.com/users")
 *     .header("Content-Type", "application/json")
 *     .body("{\"name\":\"test\"}")
 *     .execute();
 *
 * // 通用方法
 * ClientResponse resp = client.method(HttpMethod.PATCH, "http://api.example.com/users/1")
 *     .header("Content-Type", "application/json")
 *     .body("{\"age\":25}")
 *     .execute();
 *
 * // 异步执行
 * client.get("http://api.example.com/users")
 *     .header("Authorization", "Bearer token")
 *     .executeAsync()
 *     .thenApply(ClientResponse::getBodyString)
 *     .thenAccept(System.out::println);
 * }</pre>
 *
 * <p><b>线程安全性：</b>本类不是线程安全的，每个请求应使用独立的 RequestSpec 实例。
 * 但每次调用 {@code client.get(url)} 等方法都会创建新实例，因此正常使用不会有线程安全问题。</p>
 *
 * @author CH
 * @see HttpClient
 * @see HttpClientBuilder
 * @since 4.0
 */
public class RequestSpec {

    /**
     * 绑定的 HTTP 客户端实例，请求最终通过此客户端执行
     */
    private final HttpClient client;

    /**
     * 请求 URL
     */
    private String url;

    /**
     * HTTP 请求方法
     */
    private HttpMethod method;

    /**
     * 请求头集合
     */
    private final HttpHeader headers = HttpHeader.create();

    /**
     * 请求体
     */
    private Object body;

    /**
     * URL 查询参数
     */
    private Map<String, String> params;

    /**
     * 连接超时时间（毫秒）
     */
    private long connectTimeout = 30000;

    /**
     * 读取超时时间（毫秒）
     */
    private long readTimeout = 30000;

    /**
     * 写入超时时间（毫秒）
     */
    private long writeTimeout = 30000;

    /**
     * 连接保活超时时间（毫秒）
     */
    private long keepAliveTimeout = 60000;

    /**
     * HTTP 协议版本
     */
    private HttpVersion version;

    /**
     * 响应缓存有效期（毫秒），0 表示不缓存
     */
    private long cacheTtl;

    /**
     * 最大重试次数
     */
    private int maxRetries;

    /**
     * 是否跟随重定向
     */
    private boolean followRedirects = true;

    /**
     * 使用指定的客户端、URL 和请求方法创建请求规格。
     *
     * <p>包级访问权限，由 {@link HttpClient} 接口的 default 方法内部创建。</p>
     *
     * @param client 绑定的 HTTP 客户端
     * @param url    请求 URL
     * @param method HTTP 请求方法
     */
    RequestSpec(HttpClient client, String url, HttpMethod method) {
        this.client = client;
        this.url = url;
        this.method = method;
    }

    // ==================== 链式设置方法 ====================

    /**
     * 切换为 GET 请求并设置 URL。
     *
     * <p>用于静态 {@code HttpClient.header()} 创建的 RequestSpec，在预设 header 后指定方法和 URL。</p>
     *
     * @param url 请求 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec get(String url) {
        this.url = url;
        this.method = HttpMethod.GET;
        return this;
    }

    /**
     * 切换为 POST 请求并设置 URL。
     *
     * @param url 请求 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec post(String url) {
        this.url = url;
        this.method = HttpMethod.POST;
        return this;
    }

    /**
     * 切换为 PUT 请求并设置 URL。
     *
     * @param url 请求 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec put(String url) {
        this.url = url;
        this.method = HttpMethod.PUT;
        return this;
    }

    /**
     * 切换为 DELETE 请求并设置 URL。
     *
     * @param url 请求 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec delete(String url) {
        this.url = url;
        this.method = HttpMethod.DELETE;
        return this;
    }

    /**
     * 切换为 PATCH 请求并设置 URL。
     *
     * @param url 请求 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec patch(String url) {
        this.url = url;
        this.method = HttpMethod.PATCH;
        return this;
    }

    /**
     * 切换为 HEAD 请求并设置 URL。
     *
     * @param url 请求 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec head(String url) {
        this.url = url;
        this.method = HttpMethod.HEAD;
        return this;
    }

    /**
     * 切换为 OPTIONS 请求并设置 URL。
     *
     * @param url 请求 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec options(String url) {
        this.url = url;
        this.method = HttpMethod.OPTIONS;
        return this;
    }

    /**
     * 切换为指定 HTTP 方法并设置 URL。
     *
     * @param method HTTP 请求方法
     * @param url    请求 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec method(HttpMethod method, String url) {
        this.url = url;
        this.method = method;
        return this;
    }

    /**
     * 添加单个请求头。
     *
     * <p>如果同名请求头已存在，新值会覆盖旧值。</p>
     *
     * @param name  请求头名称，如 {@code "Content-Type"}、{@code "Authorization"}
     * @param value 请求头值，如 {@code "application/json"}、{@code "Bearer xxx"}
     * @return 当前实例（链式调用）
     */
    public RequestSpec header(String name, String value) {
        this.headers.add(name, value);
        return this;
    }

    /**
     * 批量添加多个请求头。
     *
     * @param headers 请求头 Map，键为请求头名称，值为请求头值
     * @return 当前实例（链式调用）
     */
    public RequestSpec headers(Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(this.headers::add);
        }
        return this;
    }

    /**
     * 设置请求体为字符串。
     *
     * @param body 请求体字符串，如 JSON 字符串
     * @return 当前实例（链式调用）
     */
    public RequestSpec body(String body) {
        this.body = body;
        return this;
    }

    /**
     * 设置请求体为字节数组。
     *
     * @param body 请求体字节数组
     * @return 当前实例（链式调用）
     */
    public RequestSpec body(byte[] body) {
        this.body = body;
        return this;
    }

    /**
     * 设置请求体（通用类型）。
     *
     * <p>支持 String、byte[] 或任意 Java 对象。
     * 非 String/byte[] 类型会调用 {@link Object#toString()} 转换。</p>
     *
     * @param body 请求体对象
     * @return 当前实例（链式调用）
     */
    public RequestSpec body(Object body) {
        this.body = body;
        return this;
    }

    /**
     * 添加单个查询参数。
     *
     * <p>多个参数会自动拼接为 {@code ?key1=value1&key2=value2} 格式，
     * 参数名和值会自动 URL 编码。</p>
     *
     * @param key   参数名
     * @param value 参数值
     * @return 当前实例（链式调用）
     */
    public RequestSpec query(String key, String value) {
        if (this.params == null) {
            this.params = new LinkedHashMap<>();
        }
        this.params.put(key, value);
        return this;
    }

    /**
     * 设置连接超时时间。
     *
     * @param timeout 连接超时时间（毫秒），默认 30000
     * @return 当前实例（链式调用）
     */
    public RequestSpec connectTimeout(long timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    /**
     * 设置读取超时时间。
     *
     * @param timeout 读取超时时间（毫秒），默认 30000
     * @return 当前实例（链式调用）
     */
    public RequestSpec readTimeout(long timeout) {
        this.readTimeout = timeout;
        return this;
    }

    /**
     * 设置写入超时时间。
     *
     * <p>JDK HttpClient 不支持此配置（会被忽略），OkHttp 和 HttpClient5 支持。</p>
     *
     * @param timeout 写入超时时间（毫秒），默认 30000
     * @return 当前实例（链式调用）
     */
    public RequestSpec writeTimeout(long timeout) {
        this.writeTimeout = timeout;
        return this;
    }

    /**
     * 设置连接保活超时时间。
     *
     * @param timeout 保活超时时间（毫秒），默认 60000
     * @return 当前实例（链式调用）
     */
    public RequestSpec keepAliveTimeout(long timeout) {
        this.keepAliveTimeout = timeout;
        return this;
    }

    /**
     * 设置 HTTP 协议版本。
     *
     * @param version HTTP 协议版本（HTTP_1_1 / HTTP_2 / HTTP_3）
     * @return 当前实例（链式调用）
     */
    public RequestSpec version(HttpVersion version) {
        this.version = version;
        return this;
    }

    /**
     * 设置响应缓存有效期（TTL）。
     *
     * <p>仅 GET 请求和 2xx 成功响应会被缓存。</p>
     *
     * @param ttlMs 缓存有效期（毫秒），0 表示不缓存
     * @return 当前实例（链式调用）
     */
    public RequestSpec cache(long ttlMs) {
        this.cacheTtl = ttlMs;
        return this;
    }

    /**
     * 设置请求失败时的最大重试次数。
     *
     * <p>重试间隔递增（100ms × 重试次数），仅对幂等请求安全。</p>
     *
     * @param retries 最大重试次数，0 表示不重试
     * @return 当前实例（链式调用）
     */
    public RequestSpec retry(int retries) {
        this.maxRetries = retries;
        return this;
    }

    /**
     * 设置是否自动跟随 HTTP 3xx 重定向。
     *
     * @param follow true 跟随重定向（默认），false 不跟随
     * @return 当前实例（链式调用）
     */
    public RequestSpec followRedirects(boolean follow) {
        this.followRedirects = follow;
        return this;
    }

    /**
     * 快捷设置 Content-Type 为 {@code application/json}。
     *
     * @return 当前实例（链式调用）
     */
    public RequestSpec json() {
        this.headers.add("Content-Type", "application/json");
        return this;
    }

    /**
     * 快捷设置 Content-Type 为 {@code application/x-www-form-urlencoded}。
     *
     * @return 当前实例（链式调用）
     */
    public RequestSpec form() {
        this.headers.add("Content-Type", "application/x-www-form-urlencoded");
        return this;
    }

    /**
     * 快捷设置 Accept 请求头。
     *
     * @param mimeType 期望的 MIME 类型
     * @return 当前实例（链式调用）
     */
    public RequestSpec accept(String mimeType) {
        this.headers.add("Accept", mimeType);
        return this;
    }

    /**
     * 快捷设置 Authorization 为 Bearer Token。
     *
     * @param token Bearer Token 字符串
     * @return 当前实例（链式调用）
     */
    public RequestSpec auth(String token) {
        this.headers.add("Authorization", "Bearer " + token);
        return this;
    }

    /**
     * 快捷设置 Authorization 为 HTTP Basic 认证。
     *
     * <p>将用户名和密码拼接为 {@code username:password}，进行 Base64 编码，
     * 设置为 {@code Authorization: Basic base64encoded}。</p>
     *
     * @param username 认证用户名
     * @param password 认证密码
     * @return 当前实例（链式调用）
     */
    public RequestSpec authBasic(String username, String password) {
        String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.headers.add("Authorization", "Basic " + encoded);
        return this;
    }

    /**
     * 设置 Content-Type 请求头。
     *
     * @param mimeType MIME 类型，如 {@code "application/xml"}、{@code "text/html"}
     * @return 当前实例（链式调用）
     */
    public RequestSpec contentType(String mimeType) {
        this.headers.add("Content-Type", mimeType);
        return this;
    }

    /**
     * 设置 User-Agent 请求头。
     *
     * @param userAgent User-Agent 字符串
     * @return 当前实例（链式调用）
     */
    public RequestSpec userAgent(String userAgent) {
        this.headers.add("User-Agent", userAgent);
        return this;
    }

    /**
     * 添加 Cookie 请求头。
     *
     * <p>多次调用会拼接为 {@code Cookie: name1=val1; name2=val2} 格式。</p>
     *
     * @param name  Cookie 名称
     * @param value Cookie 值
     * @return 当前实例（链式调用）
     */
    public RequestSpec cookie(String name, String value) {
        String existing = this.headers.get("Cookie");
        String cookieEntry = name + "=" + value;
        if (existing != null && !existing.isEmpty()) {
            this.headers.add("Cookie", existing + "; " + cookieEntry);
        } else {
            this.headers.add("Cookie", cookieEntry);
        }
        return this;
    }

    /**
     * 快捷设置 Cache-Control 为 {@code no-cache}，禁止缓存。
     *
     * @return 当前实例（链式调用）
     */
    public RequestSpec noCache() {
        this.headers.add("Cache-Control", "no-cache");
        return this;
    }

    /**
     * 快捷设置不跟随重定向（等价于 {@code followRedirects(false)}）。
     *
     * @return 当前实例（链式调用）
     */
    public RequestSpec noFollow() {
        this.followRedirects = false;
        return this;
    }

    /**
     * 设置 If-None-Match 请求头（条件请求，配合 ETag 缓存验证）。
     *
     * @param etag ETag 值
     * @return 当前实例（链式调用）
     */
    public RequestSpec ifNoneMatch(String etag) {
        this.headers.add("If-None-Match", etag);
        return this;
    }

    /**
     * 设置 Referer 请求头。
     *
     * @param referer 来源页面 URL
     * @return 当前实例（链式调用）
     */
    public RequestSpec referer(String referer) {
        this.headers.add("Referer", referer);
        return this;
    }

    // ==================== 执行方法 ====================

    /**
     * 构建 {@link ClientRequest} 并通过绑定的 {@link HttpClient} 执行请求。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>拼接查询参数到 URL</li>
     *   <li>将所有链式设置的参数封装为 {@link ClientRequest}</li>
     *   <li>委托给绑定的 {@link HttpClient#execute(ClientRequest)} 执行</li>
     * </ol>
     *
     * @return 响应对象 {@link ClientResponse}
     * @throws RuntimeException 如果请求执行过程中发生异常
     */
    public ClientResponse execute() {
        return client.execute(buildRequest());
    }

    /**
     * 异步执行请求，返回 {@link CompletableFuture}。
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> executeAsync() {
        return client.executeAsync(buildRequest());
    }

    /**
     * 异步执行请求，通过回调通知结果。
     *
     * @param callback 异步回调
     */
    public void executeAsync(Callback<ClientResponse> callback) {
        executeAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }

    // ==================== Reactor 支持 ====================

    /**
     * 以 Reactor {@link Mono} 方式异步执行请求。
     *
     * <p>将 {@link CompletableFuture} 适配为 Mono，支持 Reactor 响应式编程模型。
     * 适用于 WebFlux、Spring Cloud Gateway 等响应式框架。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * Mono<ClientResponse> mono = client.get("http://api.example.com/users")
     *     .header("Authorization", "Bearer token")
     *     .executeMono();
     *
     * mono.subscribe(resp -> System.out.println(resp.getBodyString()));
     * }</pre>
     *
     * @return Mono 包装的响应
     */
    public Mono<ClientResponse> executeMono() {
        return Mono.fromFuture(() -> client.executeAsync(buildRequest()));
    }

    /**
     * 以 Reactor {@link Mono} 方式异步执行请求，直接返回响应体字符串。
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * Mono<String> body = client.get("http://api.example.com/users")
     *     .executeMonoString();
     *
     * body.subscribe(System.out::println);
     * }</pre>
     *
     * @return Mono 包装的响应体字符串
     */
    public Mono<String> executeMonoString() {
        return executeMono().map(ClientResponse::getBodyString);
    }

    /**
     * 以 Reactor {@link Mono} 方式异步执行请求，直接返回响应体字节数组。
     *
     * @return Mono 包装的响应体字节数组
     */
    public Mono<byte[]> executeMonoBytes() {
        return executeMono().map(ClientResponse::getBody);
    }

    /**
     * 以 Reactor {@link Flux} 方式异步执行请求，按行分割响应体。
     *
     * <p>适用于逐行处理大文本响应、SSE 流式响应等场景。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * Flux<String> lines = client.get("http://api.example.com/large-file")
     *     .executeFlux();
     *
     * lines.doOnNext(line -> System.out.println("行: " + line))
     *      .subscribe();
     * }</pre>
     *
     * @return Flux 包装的逐行文本流
     */
    public Flux<String> executeFlux() {
        return executeMono()
                .map(ClientResponse::getBodyString)
                .flatMapMany(body -> Flux.fromArray(body.split("\n")));
    }

    // ==================== SSE（Server-Sent Events）支持 ====================

    /**
     * 建立 SSE 连接并开始监听事件。
     *
     * <p>将当前 RequestSpec 的所有配置（URL、方法、请求头、请求体、超时等）
     * 转换为 {@link SseRequest}，通过 {@link SseClient} 建立 SSE 连接。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * // AI 对话流式接口
     * SseConnection conn = client.post("http://api.example.com/chat/stream")
     *     .header("Authorization", "Bearer sk-xxx")
     *     .body(requestJson)
     *     .sse(event -> System.out.println(event.getData()));
     *
     * // GET SSE 事件流
     * SseConnection conn = client.get("http://api.example.com/events")
     *     .sse(new SseListener() {
     *         public void onOpen() { System.out.println("连接成功"); }
     *         public void onEvent(SseEvent event) {
     *             System.out.println("[" + event.getEvent() + "] " + event.getData());
     *         }
     *     });
     *
     * // 用完关闭
     * conn.close();
     * }</pre>
     *
     * @param listener SSE 事件监听器
     * @return SSE 连接句柄，可用于主动关闭连接
     */
    public SseConnection sse(SseListener listener) {
        return sse(listener, false);
    }

    /**
     * 建立 SSE 连接，可配置自动重连。
     *
     * <p>启用重连后，连接断开时会自动使用 Last-Event-ID 断点续传。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * SseConnection conn = client.post("http://api.example.com/chat/stream")
     *     .json()
     *     .body(requestJson)
     *     .sse(event -> System.out.println(event.getData()), true);
     * }</pre>
     *
     * @param listener  SSE 事件监听器
     * @param reconnect 是否启用自动重连
     * @return SSE 连接句柄
     */
    public SseConnection sse(SseListener listener, boolean reconnect) {
        SseClient sseClient = SseClient.create();
        SseRequest sseRequest = buildSseRequest(reconnect);
        return sseClient.connect(sseRequest, listener);
    }

    /**
     * 异步建立 SSE 连接。
     *
     * <p>在异步任务中建立 SSE 连接，返回 {@link CompletableFuture}。</p>
     *
     * @param listener SSE 事件监听器
     * @return 异步任务，完成时包含 {@link SseConnection}
     */
    public CompletableFuture<SseConnection> sseAsync(SseListener listener) {
        return SseClient.connectAsync(buildSseRequest(false), listener);
    }

    /**
     * 异步建立 SSE 连接（可配置自动重连）。
     *
     * @param listener  SSE 事件监听器
     * @param reconnect 是否启用自动重连
     * @return 异步任务，完成时包含 {@link SseConnection}
     */
    public CompletableFuture<SseConnection> sseAsync(SseListener listener, boolean reconnect) {
        return SseClient.connectAsync(buildSseRequest(reconnect), listener);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建 {@link ClientRequest} 请求对象。
     *
     * <p>将当前 RequestSpec 的所有配置转换为 {@link ClientRequest}，
     * 包括 URL 拼接查询参数、请求头、请求体、超时等。</p>
     *
     * @return 构建好的 ClientRequest 实例
     */
    private ClientRequest buildRequest() {
        ClientRequest request = new ClientRequest();
        request.setUrl(buildUrl());
        request.setMethod(method);
        request.setHeaders(headers);
        request.setBody(body);
        request.setFollowRedirects(followRedirects);
        request.setConnectTimeout(connectTimeout);
        request.setReadTimeout(readTimeout);
        request.setWriteTimeout(writeTimeout);
        request.setKeepAliveTimeout(keepAliveTimeout);
        request.setVersion(version);
        request.setCacheTtl(cacheTtl);
        request.setMaxRetries(maxRetries);
        return request;
    }

    /**
     * 构建完整 URL（拼接查询参数）。
     *
     * @return 完整的 URL 字符串
     */
    private String buildUrl() {
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            if (entry.getValue() != null) {
                sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            first = false;
        }
        return sb.toString();
    }

    /**
     * 构建 {@link SseRequest}，将当前 RequestSpec 配置转换为 SSE 请求参数。
     *
     * @param reconnect 是否启用自动重连
     * @return SseRequest 实例
     */
    private SseRequest buildSseRequest(boolean reconnect) {
        // 将 body 转为字符串（SSE 请求体通常为 JSON 字符串）
        String bodyStr = null;
        if (body != null) {
            bodyStr = body instanceof String ? (String) body : body.toString();
        }

        return SseRequest.builder()
                .url(buildUrl())
                .method(method)
                .headers(headers.toMap())
                .body(bodyStr)
                .connectTimeout(connectTimeout)
                // SSE 长连接通常不超时
                .readTimeout(readTimeout > 0 ? readTimeout : 0)
                .reconnect(reconnect)
                .build();
    }
}
