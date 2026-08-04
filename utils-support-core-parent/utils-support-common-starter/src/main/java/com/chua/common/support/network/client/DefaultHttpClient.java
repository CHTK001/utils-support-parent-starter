package com.chua.common.support.network.client;

import com.chua.common.support.network.client.spi.HttpClientExecutor;
import com.chua.common.support.network.http.HttpMethod;

import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NullUnmarked;

/**
 * 默认 HTTP 客户端实现，采用<b>委托模式（Delegation Pattern）</b>转发给 {@link HttpClientExecutor}。
 *
 * <p>本类是 {@link HttpClient} 接口的默认实现，内部持有 {@link HttpClientExecutor} 实例，
 * 所有请求直接委托给该执行器处理。此设计将 HTTP 请求的执行策略与客户端接口解耦：
 * </p>
 * <ul>
 *   <li><b>接口层</b> — {@link HttpClient} 定义统一的请求方法</li>
 *   <li><b>委派层</b> — {@link DefaultHttpClient} 实现接口，将请求转发给执行器</li>
 *   <li><b>执行层</b> — {@link HttpClientExecutor} SPI 实现底层 HTTP 通信（JDK / OkHttp / HttpClient5）</li>
 * </ul>
 *
 * <p><b>异常处理：</b></p>
 * <p>执行过程中发生的受检异常（{@link Exception}）会被包装为 {@link RuntimeException} 抛出，
 * 调用方无需逐层处理受检异常。异常消息中包含请求 URL 以方便定位问题。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * // 获取默认客户端并执行请求
 * HttpClient client = HttpClientFactory.getClient();
 * ClientResponse resp = client.get("http://api.example.com/users");
 *
 * // 指定使用 OkHttp 执行器
 * HttpClient okClient = HttpClientFactory.getClient("okhttp");
 * ClientResponse resp2 = okClient.post("http://api.example.com/users", "{\"name\":\"test\"}");
 * }</pre>
 *
 * @author CH
 * @see HttpClient
 * @see HttpClientExecutor
 * @see HttpClientFactory
 */
@NullUnmarked
public class DefaultHttpClient implements HttpClient {

    /**
     * 底层 HTTP 执行器，负责实际的网络通信。
     *
     * <p>通过 SPI 机制加载，可以是：
     * <ul>
     *   <li>{@link JdkHttpClientExecutor} — JDK 内置实现（默认回退）</li>
     *   <li>OkHttp 执行器 — 需要 okhttp3 依赖</li>
     *   <li>Apache HttpClient5 执行器 — 需要 httpclient5 依赖</li>
     * </ul>
     */
    private final HttpClientExecutor executor;

    /**
     * 使用指定的 HTTP 执行器创建默认客户端。
     *
     * @param executor 底层 HTTP 执行器，通过 {@link HttpClientFactory} 获取
     */
    public DefaultHttpClient(HttpClientExecutor executor) {
        this.executor = executor;
    }

    /**
     * 执行 HTTP 请求，委托给底层执行器处理。
     *
     * <p>此方法将 {@link ClientRequest} 转发给 {@link HttpClientExecutor#execute(ClientRequest)}，
     * 并将所有受检异常（{@link Exception}）包装为 {@link RuntimeException}。</p>
     *
     * @param request 封装好的请求对象
     * @return 响应对象 {@link ClientResponse}
     * @throws RuntimeException 如果执行器抛出异常，包装后的运行时异常
     */
    @Override
    public ClientResponse execute(ClientRequest request) {
        try {
            return executor.execute(request);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + request.getUrl(), e);
        }
    }

    /**
     * 快速执行 GET 请求。
     *
     * @param url 请求 URL
     * @return 响应对象
     */
    @Override
    public ClientResponse get(String url) {
        return execute(ClientRequest.of(url, HttpMethod.GET));
    }

    /**
     * 快速执行 POST 请求。
     *
     * @param url  请求 URL
     * @param body 请求体
     * @return 响应对象
     */
    @Override
    public ClientResponse post(String url, Object body) {
        ClientRequest request = ClientRequest.of(url, HttpMethod.POST);
        request.setBody(body);
        return execute(request);
    }

    /**
     * 快速执行 PUT 请求。
     *
     * @param url  请求 URL
     * @param body 请求体
     * @return 响应对象
     */
    @Override
    public ClientResponse put(String url, Object body) {
        ClientRequest request = ClientRequest.of(url, HttpMethod.PUT);
        request.setBody(body);
        return execute(request);
    }

    /**
     * 快速执行 DELETE 请求。
     *
     * @param url 请求 URL
     * @return 响应对象
     */
    @Override
    public ClientResponse delete(String url) {
        return execute(ClientRequest.of(url, HttpMethod.DELETE));
    }

    /**
     * 异步执行 HTTP 请求，委托给底层执行器。
     *
     * <p>此方法将 {@link ClientRequest} 转发给
     * {@link HttpClientExecutor#executeAsync(ClientRequest)}，
     * 如果执行器提供了原生异步实现（如 {@link JdkHttpClientExecutor}
     * 使用 JDK {@code sendAsync()}），则自动获得真正的异步 I/O 能力。</p>
     *
     * @param request 封装好的请求对象
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    @Override
    public CompletableFuture<ClientResponse> executeAsync(ClientRequest request) {
        return executor.executeAsync(request);
    }

    /**
     * 释放底层执行器资源。
     *
     * <p>默认实现为空（{@link JdkHttpClientExecutor} 的 {@link java.net.http.HttpClient}
     * 使用全局连接池，无需手动关闭）。
     * 如果底层执行器持有需要释放的资源（如 OkHttp 的连接池），应覆盖此方法。</p>
     */
    @Override
    public void close() {}
}
