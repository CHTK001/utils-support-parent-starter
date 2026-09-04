package com.chua.common.support.network.client;

import com.chua.common.support.network.annotations.RequestMethod;
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

    /**
     * 验证应用层拦截器多次调用 proceed（重试）不会跳过任何层。
     *
     * <p>拦截器本体只执行一次（它是当前层），每次 {@code proceed()} 都从下一层重新执行。
     * 期望链顺序：
     * {@code [app(进入) → network → real, retry(重试标记) → network → real]}</p>
     */
    @Test
    void retryDoesNotSkipLayers() {
        List<String> order = new ArrayList<>();
        ClientResponse ok = new ClientResponse();
        ok.setStatusCode(200);

        DefaultHttpClient client = new DefaultHttpClient(new HttpClientExecutor() {
            @Override
            public ClientResponse execute(ClientRequest request) {
                order.add("real");
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
            order.add("app");
            chain.proceed(request); // 第一次
            order.add("retry");     // 重试标记
            return chain.proceed(request); // 第二次（重试）
        });
        client.addNetworkInterceptor((chain, request) -> {
            order.add("network");
            return chain.proceed(request);
        });

        client.execute(ClientRequest.of("http://example.com"));
        assertEquals(List.of("app", "network", "real", "retry", "network", "real"), order);
    }

    /**
     * 验证 HttpInvoker.addInject 注入规则端到端落地。
     *
     * <p>通过 mock 执行器捕获实际请求头，验证：</p>
     * <ul>
     *   <li>headers.X 注入到请求头</li>
     *   <li>attributes.X 注入到共享属性且可被后续规则读取（链式注入）</li>
     * </ul>
     */
    @Test
    void httpInvokerInjectRuleShouldInjectHeader() {
        ClientResponse ok = new ClientResponse();
        ok.setStatusCode(200);
        ok.setBody("{\"ok\":true}".getBytes());

        final ClientRequest[] captured = {null};
        HttpClient mockClient = new DefaultHttpClient(new HttpClientExecutor() {
            @Override
            public ClientResponse execute(ClientRequest request) {
                captured[0] = request;
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

        Invoker invoker = HttpInvoker.of()
                .client(mockClient)
                .addInject("headers.Authorization", ctx -> "Bearer token-999")
                .addInject("attributes.traceId", ctx -> "trace-abc")
                .addInject("headers.X-Trace-Id", ctx -> String.valueOf(ctx.getAttribute("traceId")));

        InjectRuleApi api = invoker.createNew(InjectRuleApi.class);
        api.getUser();

        assertNotNull(captured[0]);
        assertEquals("Bearer token-999", captured[0].getHeader("Authorization"));
        assertEquals("trace-abc", captured[0].getHeader("X-Trace-Id"));
        assertEquals("http://example.com/api/user", captured[0].getUrl());
    }

    /**
     * 声明式 HTTP API 接口（仅用于注入规则测试）。
     */
    @RequestMethod("http://example.com")
    interface InjectRuleApi {

        /**
         * 获取用户信息。
         */
        @RequestMethod(method = "GET", value = "/api/user")
        String getUser();
    }
}
