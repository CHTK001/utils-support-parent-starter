package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock / 熔断 / 请求改写 三个过滤器的纯逻辑单测（不起服务）。
 *
 * <p>用最小桩实现 {@link ServerFilterConfig}、{@link ServerRequest}、{@link ServerResponse}
 * 与 {@link ServerFilterChain}，只验证判定与路径改写，不做网络。</p>
 */
class NewServerFiltersTest {

    /** 构造配置桩。 */
    private static ServerFilterConfig config(Map<String, String> params) {
        return new ServerFilterConfig() {
            @Override
            public String getFilterName() {
                return "test";
            }

            @Override
            public String getInitParameter(String name) {
                return params.get(name);
            }

            @Override
            public Map<String, String> getInitParameters() {
                return params;
            }

            @Override
            public com.chua.common.support.network.server.ServerSetting getServerSetting() {
                return null;
            }
        };
    }

    /** 请求桩：只暴露 path 与属性。 */
    private static ServerRequest req(String path) {
        return new ServerRequest() {
            private final Map<String, Object> attrs = new HashMap<>();

            @Override
            public String getUri() {
                return path;
            }

            @Override
            public String getPath() {
                return path;
            }

            @Override
            public com.chua.common.support.network.http.HttpMethod getMethod() {
                return com.chua.common.support.network.http.HttpMethod.GET;
            }

            @Override
            public String getHeader(String name) {
                return null;
            }

            @Override
            public com.chua.common.support.network.http.HttpHeader getHeaders() {
                return null;
            }

            @Override
            public Map<String, String> getParams() {
                return Map.of();
            }

            @Override
            public String getParam(String name) {
                return null;
            }

            @Override
            public String getContentType() {
                return null;
            }

            @Override
            public long getContentLength() {
                return -1;
            }

            @Override
            public byte[] getBody() {
                return new byte[0];
            }

            @Override
            public String getBodyString() {
                return "";
            }

            @Override
            public java.io.InputStream getInputStream() {
                return java.io.InputStream.nullInputStream();
            }

            @Override
            public String getRemoteAddress() {
                return "127.0.0.1";
            }

            @Override
            public int getRemotePort() {
                return 0;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return attrs;
            }

            @Override
            public Object getAttribute(String name) {
                return attrs.get(name);
            }

            @Override
            public void setAttribute(String name, Object value) {
                attrs.put(name, value);
            }
        };
    }

    /** 响应桩。 */
    private static ServerResponse res() {
        return new ServerResponse() {
            private int status;
            private String body = "";
            private String contentType;
            private boolean ended;

            @Override
            public ServerResponse setStatus(int statusCode) {
                this.status = statusCode;
                return this;
            }

            @Override
            public int getStatus() {
                return status;
            }

            @Override
            public ServerResponse setHeader(String name, String value) {
                return this;
            }

            @Override
            public String getHeader(String name) {
                return null;
            }

            @Override
            public com.chua.common.support.network.http.HttpHeader getHeaders() {
                return null;
            }

            @Override
            public ServerResponse setContentType(String contentType) {
                this.contentType = contentType;
                return this;
            }

            @Override
            public String getContentType() {
                return contentType;
            }

            @Override
            public ServerResponse setBody(byte[] body) {
                this.body = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                return this;
            }

            @Override
            public ServerResponse setBody(String body) {
                this.body = body;
                return this;
            }

            @Override
            public byte[] getBody() {
                return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }

            @Override
            public java.io.OutputStream getOutputStream() {
                return java.io.OutputStream.nullOutputStream();
            }

            @Override
            public void writeRaw(byte[] data) {
                body = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            }

            @Override
            public ServerResponse reset() {
                status = 0;
                body = "";
                contentType = null;
                ended = false;
                return this;
            }

            @Override
            public void flush() {
            }

            @Override
            public ServerResponse sendRedirect(String location) {
                return this;
            }

            @Override
            public ServerResponse sendError(int statusCode, String message) {
                this.status = statusCode;
                return this;
            }

            @Override
            public void end() {
                this.ended = true;
            }

            @Override
            public boolean isEnded() {
                return ended;
            }

            @Override
            public boolean isCommitted() {
                return ended;
            }
        };
    }

    // ==================== Mock ====================

    /**
     * Mock：精确路径命中返回固定状态与内容，未命中走兜底。
     */
    @Test
    void mockHitsExactPathAndFallsBack() throws Exception {
        MockServerFilter filter = new MockServerFilter();
        Map<String, String> params = new HashMap<>();
        params.put("rules", "/api/demo = 200|application/json|{\"ok\":1}\n# comment\n/api/miss = 404");
        params.put("defaultStatus", "418");
        filter.init(config(params));

        ServerResponse hit = res();
        filter.doFilter(req("/api/demo"), hit, (r1, r2) -> {
            throw new AssertionError("命中后不应继续走链");
        });
        Assertions.assertEquals(200, hit.getStatus());
        Assertions.assertEquals("{\"ok\":1}", hit.getBody() == null ? "" : new String(hit.getBody()));
        Assertions.assertEquals("application/json", hit.getContentType());

        ServerResponse miss = res();
        AtomicInteger chain = new AtomicInteger(0);
        filter.doFilter(req("/other"), miss, (r1, r2) -> chain.incrementAndGet());
        Assertions.assertEquals(418, miss.getStatus());
        Assertions.assertEquals(0, chain.get(), "未命中规则时兜底，不应走链");
    }

    /**
     * Mock：通配路径匹配（单段 * / 双段 **）。
     */
    @Test
    void mockGlobMatching() {
        Assertions.assertTrue(MockServerFilter.match("/api/*", "/api/users"));
        Assertions.assertFalse(MockServerFilter.match("/api/*", "/api/users/1"), "单层通配只吃一段，两段要用 /**");
        Assertions.assertFalse(MockServerFilter.match("/api/*", "/api/users/1/roles"));
        Assertions.assertTrue(MockServerFilter.match("/api/**", "/api/users/1/roles"));
    }

    // ==================== CircuitBreaker ====================

    private static ServerFilterChain failingChain(int times, AtomicInteger calls, int status) {
        return (r1, r2) -> {
            calls.incrementAndGet();
            if (calls.get() <= times) {
                r2.setStatus(status);
            } else {
                r2.setStatus(200);
            }
        };
    }

    /**
     * 连续失败达到阈值后打开，打开期间直接 503，不再请求链。
     */
    @Test
    void circuitBreakerOpensAfterThreshold() throws Exception {
        CircuitBreakerServerFilter filter = new CircuitBreakerServerFilter();
        Map<String, String> params = new HashMap<>();
        params.put("failureThreshold", "3");
        params.put("openMs", "60000");
        filter.init(config(params));

        AtomicInteger calls = new AtomicInteger(0);
        for (int i = 1; i <= 3; i++) {
            ServerResponse r = res();
            filter.doFilter(req("/down"), r, failingChain(99, calls, 503));
            Assertions.assertEquals(503, r.getStatus(), "第 " + i + " 次应放行并记录失败");
        }
        Assertions.assertEquals(3, calls.get());

        ServerResponse r = res();
        filter.doFilter(req("/down"), r, (a, b) -> calls.incrementAndGet());
        Assertions.assertEquals(503, r.getStatus(), "打开期间应快速失败");
        Assertions.assertEquals(3, calls.get(), "打开期间不允许走链");
    }

    // ==================== RequestRewrite ====================

    /**
     * 路径改写：去前缀 + 加前缀。
     */
    @Test
    void rewritePathStripAndAdd() {
        RequestRewriteServerFilter filter = new RequestRewriteServerFilter();
        Map<String, String> params = new HashMap<>();
        params.put("stripPrefix", "1");
        params.put("addPrefix", "/api");
        filter.init(config(params));

        Assertions.assertEquals("/api/b/c", filter.rewritePath("/a/b/c"));
        Assertions.assertEquals("/", filter.rewritePath("/a"));
    }

    /**
     * 请求改写：路径变量捕获写入请求属性。
     */
    @Test
    void rewriteCapturesPathVariable() throws Exception {
        RequestRewriteServerFilter filter = new RequestRewriteServerFilter();
        Map<String, String> params = new HashMap<>();
        params.put("stripPrefix", "0");
        params.put("addPrefix", "");
        params.put("pathVar.id", "\\d+");
        filter.init(config(params));

        ServerRequest request = req("/users/123");
        ServerResponse response = res();
        filter.doFilter(request, response, (r1, r2) -> {
        });

        Object id = request.getAttribute("id");
        Assertions.assertEquals("123", id, "路径变量应写入属性");
    }
}