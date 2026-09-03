package com.chua.common.support.network.client;

import com.chua.common.support.network.client.spi.HttpClientExecutor;
import com.chua.common.support.network.invoker.HttpInvoker;
import com.chua.common.support.network.invoker.Invoker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP 客户端拦截器链单元测试。
 */
class HttpInterceptorTest {

    /**
     * 构造一个返回固定响应的测试执行器。
     */
    private static HttpClientExecutor mockExecutor(ClientResponse response) {
        return new HttpClientExecutor() {
            @Override
            public ClientResponse execute(ClientRequest request) {
                return response;
            }

            @Override
            public String getName() {
                return "mock";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
    }

    /**
     * 验证应用层拦截器可统一注入请求头。
     */
    @Test
    void applicationInterceptorShouldInjectHeader() {
        ClientResponse ok = new ClientResponse();
        ok.setStatusCode(200);
        DefaultHttpClient client = new DefaultHttpClient(mockExecutor(ok));

        client.addInterceptor((chain, request) -> {
            request.header("Authorization", "Bearer token-123");
            return chain.proceed(request);
        });

        ClientRequest captured = new ClientRequest();
        client = new DefaultHttpClient(new HttpClientExecutor() {
            @Override
            public ClientResponse execute(ClientRequest request) {
                captured.setUrl(request.getUrl());
                captured.setHeaders(request.getHeaders());
                return ok;
            }

            @Override
            public String getName() {
                return "mock";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        });
        client.addInterceptor((chain, request) -> {
            request.header("Authorization", "Bearer token-123");
            return chain.proceed(request);
        });

        ClientResponse resp = client.execute(ClientRequest.of("http://example.com"));
        assertTrue(resp.isSuccess());
        assertEquals("Bearer token-123", captured.getHeader("Authorization"));
    }

    /**
     * 验证链顺序：应用层 → 请求级 → 网络层 → 网络调用。
     */
    @Test
    void chainOrderShouldBeAppThenRequestThenNetwork() {
        List<String> order = new ArrayList<>();
        ClientResponse ok = new ClientResponse();
        ok.setStatusCode(200);

        DefaultHttpClient client = new DefaultHttpClient(mockExecutor(ok));
        client.addInterceptor((chain, request) -> {
            order.add("app");
            return chain.proceed(request);
        });
        client.addNetworkInterceptor((chain, request) -> {
            order.add("network");
            return chain.proceed(request);
        });

        ClientRequest request = ClientRequest.of("http://example.com", com.chua.common.support.network.http.HttpMethod.GET,
                (chain, r) -> {
                    order.add("request-level");
                    return chain.proceed(r);
                });

        client.execute(request);
        assertEquals(List.of("app", "request-level", "network"), order);
    }

    /**
     * 验证应用层拦截器可短路返回（不调用 proceed）。
     */
    @Test
    void appInterceptorCanShortCircuit() {
        ClientResponse mockResp = new ClientResponse();
        mockResp.setStatusCode(200);

        DefaultHttpClient client = new DefaultHttpClient(mockExecutor(mockResp));
        // 记录是否真正走到了网络层
        boolean[] hitNetwork = {false};
        client.addNetworkInterceptor((chain, request) -> {
            hitNetwork[0] = true;
            return chain.proceed(request);
        });
        client.addInterceptor((chain, request) -> {
            ClientResponse cached = new ClientResponse();
            cached.setStatusCode(200);
            return cached; // 短路
        });

        ClientResponse resp = client.execute(ClientRequest.of("http://example.com"));
        assertTrue(resp.isSuccess());
        assertFalse(hitNetwork[0], "短路后不应发起网络请求");
    }

    /**
     * 验证网络层拦截器可观察真实响应。
     */
    @Test
    void networkInterceptorCanObserveResponse() {
        ClientResponse ok = new ClientResponse();
        ok.setStatusCode(200);

        DefaultHttpClient client = new DefaultHttpClient(mockExecutor(ok));
        client.addNetworkInterceptor((chain, request) -> {
            ClientResponse resp = chain.proceed(request);
            resp.setHeader("X-Observed", "true");
            return resp;
        });

        ClientResponse resp = client.execute(ClientRequest.of("http://example.com"));
        assertEquals("true", resp.getHeader("X-Observed"));
    }

    /**
     * 验证 HttpInvoker 链式自定义集成。
     */
    @Test
    void httpInvokerCustomizable() {
        HttpInvoker invoker = HttpInvoker.of()
                .baseUrl("http://custom.example.com")
                .addInterceptor((chain, request) -> chain.proceed(request));

        assertEquals(1, invoker.getInterceptors().size());
        assertEquals(0, invoker.getNetworkInterceptors().size());
        assertNotNull(invoker);
    }
}
