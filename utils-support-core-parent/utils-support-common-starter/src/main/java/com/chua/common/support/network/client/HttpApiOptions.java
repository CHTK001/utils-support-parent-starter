package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpVersion;
import com.chua.common.support.network.invoker.filter.InjectCallback;
import com.chua.common.support.network.invoker.filter.SharedInvocationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 声明式 HTTP API（{@code HttpInvoker}/{@code HttpApiFactory}）的自定义配置。
 *
 * <p>用于在创建接口动态代理时指定自定义的 baseUrl、底层 {@link HttpClient} 以及
 * 应用层/网络层拦截器，满足差异化集成与自定义需求。</p>
 *
 * <p><b>典型用途：</b></p>
 * <ul>
 *   <li><b>baseUrl</b> — 覆盖接口类级注解（{@code @RequestMethod}/{@code @RemoteService}）中的域名，<br>
 *       常用于多环境（dev/prod）或网关路由切换</li>
 *   <li><b>client</b> — 注入已注册拦截器的自定义 {@link HttpClient}（如统一加 Token、限流），<br>
 *       不设置时使用全局单例 {@code HttpClientFactory.getClient()}</li>
 *   <li><b>interceptor / networkInterceptor</b> — 仅对当前 API 代理生效的拦截器</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * HttpApiOptions options = HttpApiOptions.of()
 *     .baseUrl("http://gateway.example.com")
 *     .client(HttpClientFactory.newClient()
 *         .addInterceptor((chain, request) -> {
 *             request.header("Authorization", "Bearer " + TokenManager.getToken());
 *             return chain.proceed(request);
 *         }))
 *     .interceptor((chain, request) -> {
 *         request.header("X-Api-Key", apiKey);
 *         return chain.proceed(request);
 *     });
 *
 * UserApi api = HttpApiFactory.create(UserApi.class, options);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see HttpApiFactory
 * @see HttpApiInvocationHandler
 * @see com.chua.common.support.network.invoker.HttpInvoker
 */
public class HttpApiOptions {

    /**
     * 自定义 baseUrl，null 表示使用接口类级注解解析的 baseUrl。
     */
    private String baseUrl;

    /**
     * 自定义底层 HttpClient，null 表示使用全局单例。
     */
    private HttpClient client;

    /**
     * 应用层拦截器列表（仅对当前 API 代理生效）。
     */
    private final List<HttpInterceptor> interceptors = new ArrayList<>();

    /**
     * 网络层拦截器列表（仅对当前 API 代理生效）。
     */
    private final List<HttpInterceptor> networkInterceptors = new ArrayList<>();

    /**
     * 已被解析注册到客户端上的拦截器（按实例去重）。
     *
     * <p>防止同一 {@link HttpApiOptions} 被多次解析（如重复调用 createNew）
     * 时向客户端重复注册同一拦截器。仅按实例（identity）去重，允许逻辑不同
     * 但结构相同的拦截器各自生效。</p>
     */
    private final Set<HttpInterceptor> resolved = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * 注入规则列表（与 {@code @RemoteInject} 注解功能一致的编程式注入）。
     *
     * <p>每次远程调用前执行，将回调返回值按 target 注入到请求头（{@code headers.X}）
     * 或共享属性（{@code attributes.X}）。由 {@link HttpInvoker#addInject(String, InjectCallback)} 注册。</p>
     */
    private final List<SharedInvocationContext.InjectRule> injectRules = new ArrayList<>();

    /**
     * 默认请求头，每次远程调用自动携带。
     */
    private final Map<String, String> defaultHeaders = new java.util.LinkedHashMap<>();

    /**
     * 连接超时（毫秒），-1 表示使用执行器默认值。
     */
    private long connectTimeout = -1;

    /**
     * 读取超时（毫秒），-1 表示使用执行器默认值。
     */
    private long readTimeout = -1;

    /**
     * 写入超时（毫秒），-1 表示使用执行器默认值。
     */
    private long writeTimeout = -1;

    /**
     * 最大重试次数，-1 表示不重试（使用默认）。
     */
    private int maxRetries = -1;

    /**
     * 响应缓存有效期（毫秒），-1 表示不缓存。
     */
    private long cacheTtl = -1;

    /**
     * 是否跟随重定向，null 表示使用客户端默认。
     */
    private Boolean followRedirects;

    /**
     * HTTP 协议版本，null 表示使用执行器默认版本。
     */
    private HttpVersion version;

    /**
     * 代理主机名，null 表示不使用代理。
     */
    private String proxyHost;

    /**
     * 代理端口号。
     */
    private int proxyPort;

    /**
     * 创建空的配置实例。
     *
     * @return 新的 HttpApiOptions 实例
     */
    public static HttpApiOptions of() {
        return new HttpApiOptions();
    }

    /**
     * 获取自定义 baseUrl。
     *
     * @return 自定义 baseUrl，null 表示未设置
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 设置自定义 baseUrl。
     *
     * <p>会覆盖接口类级注解解析出的 baseUrl。支持 {@code ${...}} 占位符（环境变量/系统属性）。</p>
     *
     * @param baseUrl 自定义 baseUrl
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    /**
     * 获取自定义底层 HttpClient。
     *
     * @return 自定义 HttpClient，null 表示使用全局单例
     */
    public HttpClient getClient() {
        return client;
    }

    /**
     * 设置自定义底层 HttpClient。
     *
     * <p>使用 {@link HttpClientFactory#newClient()} 可创建独立实例，避免污染全局单例，
     * 且可以链式为其注册拦截器。</p>
     *
     * @param client 自定义 HttpClient
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions client(HttpClient client) {
        this.client = client;
        return this;
    }

    /**
     * 获取应用层拦截器列表。
     *
     * @return 应用层拦截器列表
     */
    public List<HttpInterceptor> getInterceptors() {
        return interceptors;
    }

    /**
     * 获取网络层拦截器列表。
     *
     * @return 网络层拦截器列表
     */
    public List<HttpInterceptor> getNetworkInterceptors() {
        return networkInterceptors;
    }

    /**
     * 注册应用层拦截器（仅对当前 API 代理生效）。
     *
     * <p>适合统一加 Token、Header、日志、请求预处理等；优先级低于 {@link #client} 上注册的拦截器。
     * 不设置 {@link #client} 时，会在全局单例客户端上注册拦截器。</p>
     *
     * @param interceptor 应用层拦截器
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions addInterceptor(HttpInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
        return this;
    }

    /**
     * 注册应用层拦截器（别名，便于与 {@code HttpClient.addInterceptor} 命名一致）。
     *
     * @param interceptor 应用层拦截器
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions interceptor(HttpInterceptor interceptor) {
        return addInterceptor(interceptor);
    }

    /**
     * 注册网络层拦截器（仅对当前 API 代理生效）。
     *
     * <p>紧贴网络调用，适合监控、错误码统一包装、网络层日志等；
     * 优先级低于应用层拦截器。不设置 {@link #client} 时，会在全局单例客户端上注册拦截器。</p>
     *
     * @param interceptor 网络层拦截器
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions addNetworkInterceptor(HttpInterceptor interceptor) {
        if (interceptor != null) {
            networkInterceptors.add(interceptor);
        }
        return this;
    }

    /**
     * 注册网络层拦截器（别名，便于与 {@code HttpClient.addNetworkInterceptor} 命名一致）。
     *
     * @param interceptor 网络层拦截器
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions networkInterceptor(HttpInterceptor interceptor) {
        return addNetworkInterceptor(interceptor);
    }

    /**
     * 注册编程式注入规则（与 {@code @RemoteInject} 注解功能一致）。
     *
     * <p>每次远程调用前执行回调，将返回值按 target 注入到请求头或共享属性：</p>
     * <ul>
     *   <li>{@code "headers.X"} — 注入到请求头 {@code X}</li>
     *   <li>{@code "attributes.X"} — 注入到共享属性 {@code X}（可供后续注入规则读取）</li>
     * </ul>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * HttpInvoker.of()
     *     .addInject("headers.Authorization", ctx -> "Bearer " + TokenManager.getToken())
     *     .create(UserApi.class);
     * }</pre>
     *
     * @param target   注入目标路径，如 {@code "headers.Authorization"}
     * @param callback 注入回调，每次调用时执行，返回注入值；返回 null 则跳过
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions addInject(String target, InjectCallback callback) {
        if (target != null && callback != null) {
            injectRules.add(new SharedInvocationContext.InjectRule(target, callback));
        }
        return this;
    }

    /**
     * 获取已注册的注入规则列表（只读）。
     *
     * @return 注入规则列表，不会返回 null
     */
    public List<SharedInvocationContext.InjectRule> getInjectRules() {
        return java.util.Collections.unmodifiableList(injectRules);
    }

    /**
     * 获取默认请求头（只读）。
     *
     * @return 默认请求头，不会返回 null
     */
    public Map<String, String> getDefaultHeaders() {
        return java.util.Collections.unmodifiableMap(defaultHeaders);
    }

    /**
     * 添加默认请求头（每次远程调用自动携带）。
     *
     * @param name  请求头名称
     * @param value 请求头值
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions header(String name, String value) {
        if (name != null && value != null) {
            defaultHeaders.put(name, value);
        }
        return this;
    }

    /**
     * 批量添加默认请求头。
     *
     * @param headers 请求头 Map
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions headers(Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(this::header);
        }
        return this;
    }

    /**
     * 获取连接超时（毫秒）。
     *
     * @return 连接超时，-1 表示未设置
     */
    public long getConnectTimeout() { return connectTimeout; }

    /**
     * 设置连接超时（毫秒）。
     *
     * @param timeout 连接超时，-1 表示使用执行器默认值
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions connectTimeout(long timeout) { this.connectTimeout = timeout; return this; }

    /**
     * 获取读取超时（毫秒）。
     *
     * @return 读取超时，-1 表示未设置
     */
    public long getReadTimeout() { return readTimeout; }

    /**
     * 设置读取超时（毫秒）。
     *
     * @param timeout 读取超时，-1 表示使用执行器默认值
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions readTimeout(long timeout) { this.readTimeout = timeout; return this; }

    /**
     * 获取写入超时（毫秒）。
     *
     * @return 写入超时，-1 表示未设置
     */
    public long getWriteTimeout() { return writeTimeout; }

    /**
     * 设置写入超时（毫秒）。
     *
     * @param timeout 写入超时，-1 表示使用执行器默认值
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions writeTimeout(long timeout) { this.writeTimeout = timeout; return this; }

    /**
     * 获取最大重试次数。
     *
     * @return 最大重试次数，-1 表示未设置
     */
    public int getMaxRetries() { return maxRetries; }

    /**
     * 设置最大重试次数。
     *
     * @param retries 最大重试次数，-1 表示不重试
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions retry(int retries) { this.maxRetries = retries; return this; }

    /**
     * 获取缓存有效期（毫秒）。
     *
     * @return 缓存有效期，-1 表示未设置
     */
    public long getCacheTtl() { return cacheTtl; }

    /**
     * 设置响应缓存有效期（毫秒）。
     *
     * @param ttlMs 缓存有效期，-1 表示不缓存
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions cache(long ttlMs) { this.cacheTtl = ttlMs; return this; }

    /**
     * 获取是否跟随重定向。
     *
     * @return 是否跟随重定向，null 表示未设置
     */
    public Boolean getFollowRedirects() { return followRedirects; }

    /**
     * 设置是否跟随重定向。
     *
     * @param follow true 跟随重定向，false 不跟随
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions followRedirects(boolean follow) { this.followRedirects = follow; return this; }

    /**
     * 获取 HTTP 协议版本。
     *
     * @return HTTP 版本，null 表示未设置
     */
    public HttpVersion getVersion() { return version; }

    /**
     * 设置 HTTP 协议版本。
     *
     * @param version HTTP 版本（HTTP_1_1 / HTTP_2）
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions version(HttpVersion version) { this.version = version; return this; }

    /**
     * 获取代理主机名。
     *
     * @return 代理主机名，null 表示未设置
     */
    public String getProxyHost() { return proxyHost; }

    /**
     * 获取代理端口号。
     *
     * @return 代理端口号
     */
    public int getProxyPort() { return proxyPort; }

    /**
     * 设置 HTTP 代理。
     *
     * @param host 代理主机名
     * @param port 代理端口号
     * @return 当前配置实例（链式调用）
     */
    public HttpApiOptions proxy(String host, int port) {
        this.proxyHost = host;
        this.proxyPort = port;
        return this;
    }

    /**
     * 将请求级默认配置应用到 {@link RequestSpec}。
     *
     * <p>仅应用用户显式设置的配置（哨兵值 -1 / null 跳过），由
     * {@link HttpApiInvocationHandler} 在每次远程调用前调用。</p>
     *
     * @param spec 目标 RequestSpec
     */
    public void applyTo(RequestSpec spec) {
        defaultHeaders.forEach(spec::header);
        if (connectTimeout >= 0) spec.connectTimeout(connectTimeout);
        if (readTimeout >= 0) spec.readTimeout(readTimeout);
        if (writeTimeout >= 0) spec.writeTimeout(writeTimeout);
        if (maxRetries >= 0) spec.retry(maxRetries);
        if (cacheTtl >= 0) spec.cache(cacheTtl);
        if (followRedirects != null) spec.followRedirects(followRedirects);
        if (version != null) spec.version(version);
        if (proxyHost != null && !proxyHost.isEmpty()) spec.proxy(proxyHost, proxyPort);
    }

    /**
     * 解析出实际使用的 HttpClient 实例。
     *
     * <p>解析规则：</p>
     * <ul>
     *   <li>已设置 {@link #client} — 直接使用；同一 {@link HttpApiOptions} 多次解析时，
     *       拦截器按实例去重，避免向同一客户端重复注册</li>
     *   <li>未设置 client 且配置了拦截器 — 每次解析创建独立的 {@code HttpClientFactory.newClient()} 实例，
     *       天然隔离，避免污染全局单例</li>
     *   <li>未设置 client 且无拦截器 — 使用全局单例 {@code HttpClientFactory.getClient()}</li>
     * </ul>
     *
     * @return 解析后的 HttpClient 实例
     */
    public HttpClient resolveClient() {
        boolean hasInterceptors = !interceptors.isEmpty() || !networkInterceptors.isEmpty();
        HttpClient target;
        if (client != null) {
            target = client;
            // 稳定客户端：按拦截器实例去重，防止重复注册
            for (HttpInterceptor interceptor : interceptors) {
                if (resolved.add(interceptor)) {
                    target.addInterceptor(interceptor);
                }
            }
            for (HttpInterceptor interceptor : networkInterceptors) {
                if (resolved.add(interceptor)) {
                    target.addNetworkInterceptor(interceptor);
                }
            }
        } else if (hasInterceptors) {
            // 无稳定客户端：每次创建独立实例，天然隔离，无需去重
            target = HttpClientFactory.newClient();
            for (HttpInterceptor interceptor : interceptors) {
                target.addInterceptor(interceptor);
            }
            for (HttpInterceptor interceptor : networkInterceptors) {
                target.addNetworkInterceptor(interceptor);
            }
        } else {
            target = HttpClientFactory.getClient();
        }
        return target;
    }
}