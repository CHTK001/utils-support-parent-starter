package com.chua.common.support.network.client;

import java.util.ArrayList;
import java.util.List;

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
     * 解析出实际使用的 HttpClient 实例。
     *
     * <p>解析规则：</p>
     * <ul>
     *   <li>已设置 {@link #client} — 直接使用，并将配置的拦截器注册到其上</li>
     *   <li>未设置 client 且配置了拦截器 — 创建独立的 {@code HttpClientFactory.newClient()} 实例，
     *       避免污染全局单例（防止重复注册/跨调用泄漏）</li>
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
        } else if (hasInterceptors) {
            target = HttpClientFactory.newClient();
        } else {
            target = HttpClientFactory.getClient();
        }
        for (HttpInterceptor interceptor : interceptors) {
            target.addInterceptor(interceptor);
        }
        for (HttpInterceptor interceptor : networkInterceptors) {
            target.addNetworkInterceptor(interceptor);
        }
        return target;
    }
}