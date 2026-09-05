package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.CollapseHttpClient;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.HttpInterceptor;
import com.chua.common.support.network.http.HttpMethod;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollapseHttpClient} 回归测试：URL 查询参数排序规范化（B1）、GET 折叠/POST 透传、
 * executeAsync 失败广播、closeDelegate 三态。
 *
 * @author CH
 * @since 2026/09/04
 */
class CollapseHttpClientTest {

    /**
     * 计数假客户端
     */
    static final class FakeHttpClient implements HttpClient {

        private final AtomicInteger executeCalls = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public ClientResponse execute(ClientRequest request) {
            executeCalls.incrementAndGet();
            return null;
        }

        @Override
        public Mono<ClientResponse> executeAsync(ClientRequest request) {
            return Mono.empty();
        }

        @Override
        public HttpClient addInterceptor(HttpInterceptor interceptor) {
            return this;
        }

        @Override
        public HttpClient addNetworkInterceptor(HttpInterceptor interceptor) {
            return this;
        }

        @Override
        public List<HttpInterceptor> getInterceptors() {
            return Collections.emptyList();
        }

        @Override
        public List<HttpInterceptor> getNetworkInterceptors() {
            return Collections.emptyList();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        public boolean isClosed() {
            return closed.get();
        }
    }

    /**
     * 失败假客户端：execute 抛异常（用于验证折叠失败广播）
     */
    static final class FailingHttpClient implements HttpClient {

        private final AtomicInteger executeCalls = new AtomicInteger();

        @Override
        public ClientResponse execute(ClientRequest request) {
            executeCalls.incrementAndGet();
            throw new IllegalStateException("下游网络故障");
        }

        @Override
        public Mono<ClientResponse> executeAsync(ClientRequest request) {
            return Mono.empty();
        }

        @Override
        public HttpClient addInterceptor(HttpInterceptor interceptor) {
            return this;
        }

        @Override
        public HttpClient addNetworkInterceptor(HttpInterceptor interceptor) {
            return this;
        }

        @Override
        public List<HttpInterceptor> getInterceptors() {
            return Collections.emptyList();
        }

        @Override
        public List<HttpInterceptor> getNetworkInterceptors() {
            return Collections.emptyList();
        }

        @Override
        public void close() {
        }
    }

    /**
     * 并发执行同一客户端请求，返回真实调用次数
     */
    private static int runConcurrently(FakeHttpClient fake, HttpClient client, List<ClientRequest> requests) throws Exception {
        int threads = requests.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (ClientRequest request : requests) {
            futures.add(pool.submit(() -> {
                barrier.await();
                client.execute(request);
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        return fake.executeCalls.get();
    }

    /**
     * 参数顺序不同的等价 URL 合并为一次真实调用（查询参数排序规范化）
     */
    @Test
    void differentlyOrderedQueryParamsCollapseTogether() throws Exception {
        CollapseConfig config = new CollapseConfig();
        config.setName("test-url-normalize");
        config.setWaitThreshold(2);
        config.setCollectingWaitTime(30);
        FakeHttpClient fake = new FakeHttpClient();
        CollapseHttpClient client = new CollapseHttpClient(fake, config);
        try {
            List<ClientRequest> requests = new ArrayList<>();
            requests.add(ClientRequest.of("http://example.com/x?a=1&b=2", HttpMethod.GET));
            requests.add(ClientRequest.of("http://example.com/x?b=2&a=1", HttpMethod.GET));
            requests.add(ClientRequest.of("http://example.com/x?b=2&a=1", HttpMethod.GET));
            requests.add(ClientRequest.of("http://example.com/x?a=1&b=2", HttpMethod.GET));
            int realCalls = runConcurrently(fake, client, requests);
            assertTrue(realCalls < 4, "4 个参数顺序不同的等价 GET 应合并，真实调用 " + realCalls);
        } finally {
            client.close();
        }
    }

    /**
     * 回归：相同 URL 并发 GET 仍合并
     */
    @Test
    void identicalUrlStillCollapses() throws Exception {
        CollapseConfig config = new CollapseConfig();
        config.setName("test-url-same");
        config.setWaitThreshold(2);
        config.setCollectingWaitTime(30);
        FakeHttpClient fake = new FakeHttpClient();
        CollapseHttpClient client = new CollapseHttpClient(fake, config);
        try {
            List<ClientRequest> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                requests.add(ClientRequest.of("http://example.com/x?id=1", HttpMethod.GET));
            }
            int realCalls = runConcurrently(fake, client, requests);
            assertTrue(realCalls < 4, "4 个相同 URL 应合并，真实调用 " + realCalls);
        } finally {
            client.close();
        }
    }

    /**
     * POST 透传：不折叠
     */
    @Test
    void postPassesThroughWithoutCollapse() throws Exception {
        CollapseConfig config = new CollapseConfig();
        config.setName("test-post");
        config.setWaitThreshold(2);
        FakeHttpClient fake = new FakeHttpClient();
        CollapseHttpClient client = new CollapseHttpClient(fake, config);
        try {
            client.execute(ClientRequest.of("http://example.com/x", HttpMethod.POST));
            client.execute(ClientRequest.of("http://example.com/x", HttpMethod.POST));
            assertTrue(fake.executeCalls.get() == 2, "POST 应透传 2 次真实调用");
        } finally {
            client.close();
        }
    }

    /**
     * executeAsync 折叠失败路径：批量失败经 Mono onError 广播给所有调用方
     */
    @Test
    void executeAsyncFailurePropagatesToAllSubscribers() throws Exception {
        CollapseConfig config = new CollapseConfig();
        config.setName("test-async-fail");
        config.setWaitThreshold(2);
        config.setCollectingWaitTime(30);
        FailingHttpClient fake = new FailingHttpClient();
        CollapseHttpClient client = new CollapseHttpClient(fake, config);
        try {
            List<Mono<ClientResponse>> monos = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                monos.add(client.executeAsync(ClientRequest.of("http://example.com/x?id=1", HttpMethod.GET)));
            }
            ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                return thread;
            });
            AtomicInteger errors = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (Mono<ClientResponse> mono : monos) {
                futures.add(pool.submit(() -> {
                    try {
                        mono.block(Duration.ofSeconds(15));
                    } catch (Throwable ignored) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
            assertEquals(2, errors.get(), "两个调用方都应通过 Mono onError 收到折叠失败");
            assertTrue(fake.executeCalls.get() < 2, "两个 GET 应折叠为一次真实调用，实际 " + fake.executeCalls.get());
        } finally {
            client.close();
        }
    }

    /**
     * closeDelegate 双行为：工厂入口默认不级联；直接构造默认级联；三参构造显式控制
     */
    @Test
    void closeDelegateBehavior() {
        // 工厂入口：包装共享底层实例，默认不级联关闭
        FakeHttpClient shared = new FakeHttpClient();
        HttpClient viaFactory = HttpClientFactory.collapse(shared);
        viaFactory.close();
        assertFalse(shared.isClosed(), "工厂入口 close 不应级联关闭底层共享实例");

        // 直接构造：默认级联
        CollapseConfig cascadeConfig = new CollapseConfig();
        cascadeConfig.setName("test-close-cascade");
        FakeHttpClient owned = new FakeHttpClient();
        CollapseHttpClient direct = new CollapseHttpClient(owned, cascadeConfig);
        direct.close();
        assertTrue(owned.isClosed(), "直接构造 close 应级联关闭底层客户端");

        // 三参构造 closeDelegate=false：不级联
        CollapseConfig keepConfig = new CollapseConfig();
        keepConfig.setName("test-close-keep");
        FakeHttpClient kept = new FakeHttpClient();
        CollapseHttpClient nonCascade = new CollapseHttpClient(kept, keepConfig, false);
        nonCascade.close();
        assertFalse(kept.isClosed(), "closeDelegate=false 不应级联关闭底层客户端");
    }
}
