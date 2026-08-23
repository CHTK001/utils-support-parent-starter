package com.chua.common.support.network.client;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 响应式 HTTP 客户端包装器。
 *
 * <p>将任何 {@link HttpClient} 包装为响应式风格，所有方法返回 {@link Mono}/{@link Flux}，
 * 底层委托给原客户端的 {@code executeAsync()}（JDK sendAsync 基于 NIO 非阻塞 I/O）。</p>
 *
 * <p><b>使用方式：</b></p>
 * <pre>{@code
 * // 通过工厂获取响应式客户端
 * ReactiveHttpClient client = HttpClientFactory.getReactiveClient();
 *
 * // 简单 GET
 * client.reactiveGet("http://api.example.com/users")
 *     .timeout(Duration.ofSeconds(5))
 *     .subscribe(resp -> System.out.println(resp.getBodyString()));
 *
 * // 并发批量请求
 * client.reactiveGetMany(List.of("http://a.com/1", "http://b.com/2"))
 *     .flatMap(resp -> Mono.fromSupplier(() -> resp.getBodyString()))
 *     .subscribe(System.out::println);
 *
 * // 带请求体的 POST
 * client.reactivePost("http://api.example.com/users", "{\"name\":\"test\"}")
 *     .doOnNext(resp -> log.info("status: {}", resp.getStatusCode()))
 *     .block();
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
     *
     * @return 响应式 HTTP 客户端
     */
    public static ReactiveHttpClient of() {
        return new ReactiveHttpClient(HttpClientFactory.getClient());
    }

    /**
     * 获取指定执行器的响应式客户端。
     *
     * @param executorName 执行器名称（{@code "jdk"} / {@code "okhttp"} / {@code "httpclient5"}）
     * @return 响应式 HTTP 客户端
     */
    public static ReactiveHttpClient of(String executorName) {
        return new ReactiveHttpClient(HttpClientFactory.getClient(executorName));
    }

    /**
     * 获取底层委托的普通客户端（可用于同步调用）。
     */
    public HttpClient getDelegate() {
        return delegate;
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

    @Override
    public java.util.concurrent.CompletableFuture<ClientResponse> executeAsync(ClientRequest request) {
        return delegate.executeAsync(request);
    }

    @Override
    public void close() {
        delegate.close();
    }

    // ==================== 响应式接口 ====================

    @Override
    public Mono<ClientResponse> reactiveExecute(ClientRequest request) {
        return delegate.reactiveExecute(request);
    }

    @Override
    public Mono<ClientResponse> reactiveGet(String url) {
        return delegate.reactiveGet(url);
    }

    @Override
    public Mono<ClientResponse> reactivePost(String url, Object body) {
        return delegate.reactivePost(url, body);
    }

    @Override
    public Mono<ClientResponse> reactivePut(String url, Object body) {
        return delegate.reactivePut(url, body);
    }

    @Override
    public Mono<ClientResponse> reactiveDelete(String url) {
        return delegate.reactiveDelete(url);
    }

    @Override
    public Flux<ClientResponse> reactiveGetMany(List<String> urls) {
        return delegate.reactiveGetMany(urls);
    }

    @Override
    public Flux<ByteBuffer> reactiveBodyFlux(ClientRequest request) {
        return delegate.reactiveBodyFlux(request);
    }
}
