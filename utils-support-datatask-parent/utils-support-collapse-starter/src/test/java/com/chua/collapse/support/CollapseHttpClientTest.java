package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.CollapseHttpClient;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpInterceptor;
import com.chua.common.support.network.http.HttpMethod;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollapseHttpClient} 回归测试：URL 查询参数排序规范化（B1）与 GET 折叠/POST 透传。
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
}
