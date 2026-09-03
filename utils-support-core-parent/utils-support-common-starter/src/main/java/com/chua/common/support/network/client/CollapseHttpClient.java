package com.chua.common.support.network.client;

import com.chua.common.support.concurrent.collapse.CollapseBatchFunction;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseFlow;
import com.chua.common.support.network.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 折叠 HTTP 客户端（装饰器）。
 *
 * <p>对底层 {@link HttpClient} 的请求执行做请求折叠：并发窗口内<b>方法为 GET 且 URL 相同</b>
 * 的请求合并为一次真实网络调用，并将响应（{@link ClientResponse}）广播给全部请求方，
 * 降低下游连接数与 I/O 次数。</p>
 *
 * <p><b>约定与限制：</b></p>
 * <ul>
 *   <li>仅折叠 GET（幂等）请求，POST/PUT/DELETE 等直接透传底层执行；</li>
 *   <li>折叠 key 为请求 URL（含查询串）；携带不同请求头的同 URL 请求也会被合并，
 *       因此仅适用于幂等且不依赖调用方私有头语义的场景；</li>
 *   <li>{@link ClientResponse} 的响应体为已物化的字节数组（{@code byte[]}），
 *       广播共享安全，可被多个请求方重复读取；</li>
 *   <li>未引入折叠实现模块时经 {@link CollapseFlow} 自动降级为直接执行，语义不变。</li>
 * </ul>
 *
 * @author CH
 * @since 2026/09/03
 * @see HttpClientFactory#collapse(HttpClient)
 */
public class CollapseHttpClient implements HttpClient {

    /**
     * 折叠执行器默认名称
     */
    private static final String DEFAULT_FLOW_NAME = "collapse-http";

    /**
     * 异步折叠执行线程（虚拟线程承载，避免阻塞平台线程）
     */
    private static final ExecutorService ASYNC_EXECUTOR;

    static {
        ThreadFactory factory = Thread.ofVirtual().name("collapse-http-async", 0).factory();
        ASYNC_EXECUTOR = Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * 底层 HTTP 客户端
     */
    private final HttpClient delegate;

    /**
     * 折叠门面
     */
    private final CollapseFlow<HttpCollapseTask, ClientResponse> flow;

    /**
     * 构造折叠 HTTP 客户端。
     *
     * @param delegate 底层 HTTP 客户端，不可为空
     */
    public CollapseHttpClient(HttpClient delegate) {
        this(delegate, null);
    }

    /**
     * 构造折叠 HTTP 客户端。
     *
     * @param delegate 底层 HTTP 客户端，不可为空
     * @param config   折叠配置，可为空（使用默认配置）
     */
    public CollapseHttpClient(HttpClient delegate, CollapseConfig config) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null.");
        String name = config == null ? DEFAULT_FLOW_NAME : config.getName();
        CollapseBatchFunction<HttpCollapseTask, ClientResponse> batchFunction = tasks -> {
            HttpCollapseTask first = tasks.iterator().next();
            return delegate.execute(first.request);
        };
        this.flow = CollapseFlow.of(name, batchFunction);
        if (config != null) {
            this.flow.threshold(config.getWaitThreshold())
                    .collectingWaitTime(config.getCollectingWaitTime())
                    .virtualThread(config.isVirtualThread());
        }
    }

    @Override
    public ClientResponse execute(ClientRequest request) {
        if (HttpMethod.GET.equals(request.getMethod())) {
            try {
                return flow.execute(new HttpCollapseTask(request.getUrl(), request));
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new IllegalStateException("折叠 HTTP 请求执行失败: " + request.getUrl(), throwable);
            }
        }
        return delegate.execute(request);
    }

    @Override
    public Mono<ClientResponse> executeAsync(ClientRequest request) {
        if (HttpMethod.GET.equals(request.getMethod())) {
            // 折叠等待在虚拟线程上执行，调用线程不被阻塞
            return Mono.fromCallable(() -> execute(request))
                    .subscribeOn(Schedulers.fromExecutorService(ASYNC_EXECUTOR));
        }
        return delegate.executeAsync(request);
    }

    @Override
    public void close() {
        flow.close();
        delegate.close();
    }

    /**
     * 折叠请求任务：以 URL 为折叠 key（equals/hashCode），携带真实请求用于执行。
     */
    private static final class HttpCollapseTask {

        /**
         * 请求 URL（折叠 key）
         */
        private final String url;

        /**
         * 真实请求对象
         */
        private final ClientRequest request;

        private HttpCollapseTask(String url, ClientRequest request) {
            this.url = url;
            this.request = request;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof HttpCollapseTask)) {
                return false;
            }
            return Objects.equals(url, ((HttpCollapseTask) obj).url);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(url);
        }
    }
}
