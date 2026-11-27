package com.chua.common.support.network.client;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.client.spi.HttpClientExecutor;
import com.chua.common.support.network.http.HttpMethod;
import reactor.core.publisher.Mono;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.CollectionUtils;

/**
 * 默认 HTTP 客户端实现，采用<b>委托模式（Delegation Pattern）</b>转发给 {@link HttpClientExecutor}。
 *
 * <p>本类是 {@link HttpClient} 接口的默认实现，内部持有 {@link HttpClientExecutor} 实例，
 * 所有请求直接委托给该执行器处理。此设计将 HTTP 请求的执行策略与客户端接口解耦：</p>
 * <ul>
 *   <li><b>接口层</b> — {@link HttpClient} 定义统一的请求方法</li>
 *   <li><b>委派层</b> — {@link DefaultHttpClient} 实现接口，将请求转发给执行器</li>
 *   <li><b>执行层</b> — {@link HttpClientExecutor} SPI 实现底层 HTTP 通信（JDK / OkHttp / HttpClient5 / Netty）</li>
 * </ul>
 *
 * <p><b>拦截器支持：</b>本类内置应用层/网络层拦截器链（洋葱模型）：
 * {@code [应用层拦截器] → [请求级拦截器] → [网络层拦截器] → 执行器网络调用}。
 * 通过 {@link #addInterceptor(HttpInterceptor)} / {@link #addNetworkInterceptor(HttpInterceptor)} 注册。</p>
 *
 * <p><b>异步执行：</b>{@link #executeAsync} 返回 {@link Mono}，由执行器决定底层实现。</p>
 * <ul>
 *   <li>{@link JdkHttpClientExecutor} — JDK sendAsync()，NIO 事件驱动，返回 Mono.fromFuture()</li>
 *   <li>Netty 执行器（可扩展）— 原生响应式，零线程切换</li>
 *   <li>OkHttp 执行器（可扩展）— 回调转 Mono</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @see HttpClient
 * @see HttpClientExecutor
 * @see HttpClientFactory
 */
public class DefaultHttpClient implements HttpClient {

    /**
     * 底层 HTTP 执行器，负责实际的网络通信。
     */
    private final HttpClientExecutor executor;

    /**
     * 应用层拦截器列表（按注册顺序执行）。
     *
     * <p>最外层，适合统一加 Token、Header、日志、请求预处理、短路 Mock/缓存等。
     * 通过 {@link #addInterceptor(HttpInterceptor)} 注册。</p>
     */
    private final List<HttpInterceptor> interceptors = Wraps.list();

    /**
     * 网络层拦截器列表（按注册顺序执行）。
     *
     * <p>紧贴网络调用，适合监控、错误码统一包装、网络层日志等。
     * 通过 {@link #addNetworkInterceptor(HttpInterceptor)} 注册。</p>
     */
    private final List<HttpInterceptor> networkInterceptors = Wraps.list();

    /**
     * 使用指定的 HTTP 执行器创建默认客户端。
     *
     * @param executor 底层 HTTP 执行器，通过 {@link HttpClientFactory} 获取
     */
    public DefaultHttpClient(HttpClientExecutor executor) {
        this.executor = executor;
    }

    /**
     * 获取已注册的应用层拦截器列表。
     *
     * @return 应用层拦截器列表（只读视图）
     */
    @Override
    public List<HttpInterceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }

    /**
     * 获取已注册的网络层拦截器列表。
     *
     * @return 网络层拦截器列表（只读视图）
     */
    @Override
    public List<HttpInterceptor> getNetworkInterceptors() {
        return Collections.unmodifiableList(networkInterceptors);
    }

    /**
     * 注册应用层拦截器。
     *
     * @param interceptor 应用层拦截器，null 忽略
     * @return 当前客户端实例（链式调用）
     */
    @Override
    public HttpClient addInterceptor(HttpInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
        return this;
    }

    /**
     * 注册网络层拦截器。
     *
     * @param interceptor 网络层拦截器，null 忽略
     * @return 当前客户端实例（链式调用）
     */
    @Override
    public HttpClient addNetworkInterceptor(HttpInterceptor interceptor) {
        if (interceptor != null) {
            networkInterceptors.add(interceptor);
        }
        return this;
    }

    /**
     * 执行 HTTP 请求，组装拦截器链并委托给底层执行器处理。
     *
     * @param request 封装好的请求对象
     * @return 响应对象 {@link ClientResponse}
     * @throws RuntimeException 如果执行器抛出异常
     */
    @Override
    public ClientResponse execute(ClientRequest request) {
        // query 参数统一在本层拼回 URL（执行器不感知 params，见 appendParamsToUrl）
        appendParamsToUrl(request);
        // 无拦截器时直接走真实调用：省去每次请求的链对象与 merge 列表分配
        // （与 executeAsync 的短路一致；重试仍在 doExecute 内处理，语义不变）
        if (interceptors.isEmpty() && networkInterceptors.isEmpty() && request.getInterceptor() == null) {
            return doExecute(request);
        }
        return new InterceptorChain(interceptors, request.getInterceptor(), networkInterceptors,
                request, this::doExecute)
                .proceed(request);
    }

    /**
     * 最内层真实网络调用：委托给底层执行器，并按请求/全局配置执行重试。
     *
     * <p>重试仅发生在本层（拦截器之外）：幂等方法在遇到网络异常或可重试状态码
     * （408/425/429/5xx）时按指数退避重试，详见 {@link HttpRetrySupport}。</p>
     *
     * @param request 请求对象（已被拦截器处理）
     * @return 响应对象
     */
    private ClientResponse doExecute(ClientRequest request) {
        return HttpRetrySupport.executeWithRetry(request,
                HttpRetrySupport.resolveMaxRetries(request, null), req -> {
                    try {
                        return executor.execute(req);
                    } catch (Exception e) {
                        throw new RuntimeException("HTTP request failed: " + req.getUrl(), e);
                    }
                });
    }

    /**
     * 将 {@link ClientRequest#getParams()} 拼接为 URL 的 query 参数。
     *
     * <p>{@code HttpClientBuilder.query()} 只把参数存入 {@link ClientRequest}，并不修改 URL；
     * 各 {@link HttpClientExecutor} 实现（JDK / OkHttp / HttpClient5）均不读取 params，
     * 若不在本层拼回，query 参数会被整体丢弃。因此在委托执行器前统一完成拼接，
     * 保证「执行器拿到的是最终 URL」这一契约，与 {@link CurlFormatter} 的展示口径一致。</p>
     *
     * <p>拼接规则：键值均按 UTF-8 做 form 编码（与 {@link URLEncoder} 约定一致，
     * 空格编码为 {@code +}）；已有 query 的 URL 以 {@code &} 续接；片段（{@code #}）保持在其后；
     * 值为 {@code null} 时仅输出键名；键的插入顺序即输出顺序（builder 内为 {@code LinkedHashMap}），
     * 对依赖参数顺序的签名类接口（如 LiblibAI）必须保持稳定。</p>
     *
     * @param request 待处理的请求对象，就地更新其 URL
     */
    private static void appendParamsToUrl(ClientRequest request) {
        Map<String, String> params = request.getParams();
        if (CollectionUtils.isEmpty(params)) {
            return;
        }
        String url = request.getUrl();
        if (StringUtils.isEmpty(url)) {
            return;
        }
        int fragmentIndex = url.indexOf('#');
        String base = fragmentIndex >= 0 ? url.substring(0, fragmentIndex) : url;
        String fragment = fragmentIndex >= 0 ? url.substring(fragmentIndex) : "";
        StringBuilder sb = new StringBuilder(url.length() + params.size() * 32);
        sb.append(base);
        sb.append(base.indexOf('?') >= 0 ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            if (entry.getValue() != null) {
                sb.append('=').append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        sb.append(fragment);
        request.setUrl(sb.toString());
    }

    /**
     * 快速执行 GET 请求。
     */
    @Override
    public ClientResponse get(String url) {
        return execute(ClientRequest.of(url, HttpMethod.GET));
    }

    /**
     * 快速执行 POST 请求。
     */
    @Override
    public ClientResponse post(String url, Object body) {
        ClientRequest request = ClientRequest.of(url, HttpMethod.POST);
        request.setBody(body);
        return execute(request);
    }

    /**
     * 快速执行 PUT 请求。
     */
    @Override
    public ClientResponse put(String url, Object body) {
        ClientRequest request = ClientRequest.of(url, HttpMethod.PUT);
        request.setBody(body);
        return execute(request);
    }

    /**
     * 快速执行 DELETE 请求。
     */
    @Override
    public ClientResponse delete(String url) {
        return execute(ClientRequest.of(url, HttpMethod.DELETE));
    }

    /**
     * 异步执行 HTTP 请求，委托给底层执行器。
     *
     * <p>未注册拦截器时直接委托给执行器的 NIO {@code executeAsync()}（零阻塞）。
     * 存在拦截器时，在 Reactor {@code boundedElastic} 线程上运行拦截器链，
     * 最内层仍复用执行器的异步能力。</p>
     *
     * <p>执行器返回 {@link Mono}，由具体实现决定异步方式：
     * <ul>
     *   <li>{@link JdkHttpClientExecutor} — JDK sendAsync() + Mono.fromFuture()，NIO 事件驱动</li>
     *   <li>Netty 执行器 — 原生响应式，零线程切换</li>
     *   <li>其他执行器 — 自行实现 Mono 桥接</li>
     * </ul></p>
     *
     * @param request 封装好的请求对象
     * @return 响应 Mono
     */
    @Override
    public Mono<ClientResponse> executeAsync(ClientRequest request) {
        // query 参数统一在本层拼回 URL（执行器不感知 params，见 appendParamsToUrl）
        appendParamsToUrl(request);
        int retries = HttpRetrySupport.resolveMaxRetries(request, null);
        if (interceptors.isEmpty() && networkInterceptors.isEmpty() && request.getInterceptor() == null
                && retries <= 0) {
            return executor.executeAsync(request);
        }
        return Mono.fromCallable(() -> new InterceptorChain(
                interceptors, request.getInterceptor(), networkInterceptors,
                request, req -> HttpRetrySupport.executeWithRetry(req, retries,
                        retryRequest -> executor.executeAsync(req).block()))
                .proceed(request))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 释放底层执行器资源。
     */
    @Override
    public void close() {
        executor.close();
    }
}
