package com.chua.common.support.network.client;

import reactor.core.publisher.Mono;

import java.util.List;

/**
* 响应式 HTTP 客户端包装器。
*
* <p>将任何 {@link HttpClient} 包装为响应式风格，executeAsync 直接返回 Mono，
* 底层委托给原客户端的 executeAsync()（JDK sendAsync 基于 NIO 非阻塞 I/O）。</p>
*
* <p><b>使用方式：</b></p>
* <pre>{@code
* // 通过工厂获取响应式客户端
* ReactiveHttpClient client = HttpClientFactory.getReactiveClient();
*
* // 简单 GET
* client.executeAsync(ClientRequest.of("http://api.example.com/users"))
*     .timeout(Duration.ofSeconds(5))
*     .subscribe(resp -> System.out.println(resp.getBodyString()));
*
* // 同步方法仍可用
* ClientResponse resp = client.get("http://api.example.com/users");
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see HttpClientFactory#getReactiveClient()
 */
public final class ReactiveHttpClient implements HttpClient {

    private final HttpClient delegate;

    public ReactiveHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    /**
    * 获取响应式客户端（自动选择最优执行器）。
     */
    public static ReactiveHttpClient of() {
        return new ReactiveHttpClient(HttpClientFactory.getClient());
    }

    /**
    * 获取指定执行器的响应式客户端。
     */
    public static ReactiveHttpClient of(String executorName) {
        return new ReactiveHttpClient(HttpClientFactory.getClient(executorName));
    }

    /**
    * 获取底层委托的普通客户端。
     */
    public HttpClient getDelegate() {
        return delegate;
    }

    // ==================== 拦截器（委托） ====================

    /**
    * 注册应用层拦截器（委托给底层客户端）。
    *
    * @param interceptor 应用层拦截器
    * @return 当前响应式客户端实例（链式调用）
     */
    @Override
    public ReactiveHttpClient addInterceptor(HttpInterceptor interceptor) {
        delegate.addInterceptor(interceptor);
        return this;
    }

    /**
    * 注册网络层拦截器（委托给底层客户端）。
    *
    * @param interceptor 网络层拦截器
    * @return 当前响应式客户端实例（链式调用）
     */
    @Override
    public ReactiveHttpClient addNetworkInterceptor(HttpInterceptor interceptor) {
        delegate.addNetworkInterceptor(interceptor);
        return this;
    }

    /**
    * 获取底层客户端的应用层拦截器列表。
    *
    * @return 应用层拦截器列表
     */
    @Override
    public List<HttpInterceptor> getInterceptors() {
        return delegate.getInterceptors();
    }

    /**
    * 获取底层客户端的网络层拦截器列表。
    *
    * @return 网络层拦截器列表
     */
    @Override
    public List<HttpInterceptor> getNetworkInterceptors() {
        return delegate.getNetworkInterceptors();
    }

    // ==================== 同步接口（委托） ====================

    @Override
    public ClientResponse execute(ClientRequest request) {
        return delegate.execute(request);
    }

    @Override
    public ClientResponse get(String url) {
        return delegate.get(url);
    }

    @Override
    public ClientResponse post(String url, Object body) {
        return delegate.post(url, body);
    }

    @Override
    public ClientResponse put(String url, Object body) {
        return delegate.put(url, body);
    }

    @Override
    public ClientResponse delete(String url) {
        return delegate.delete(url);
    }

    // ==================== 响应式接口 ====================

    /**
    * 异步执行 HTTP 请求，返回 {@link Mono}。
    *
    * <p>底层委托给执行器的 NIO sendAsync()，零线程切换。</p>
     */
    @Override
    public Mono<ClientResponse> executeAsync(ClientRequest request) {
        return delegate.executeAsync(request);
    }

    /**
    * 异步执行 HTTP 请求，通过回调通知结果。
     */
    @Override
    public void executeAsync(ClientRequest request, Callback<ClientResponse> callback) {
        delegate.executeAsync(request).subscribe(callback::onSuccess, callback::onError);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
