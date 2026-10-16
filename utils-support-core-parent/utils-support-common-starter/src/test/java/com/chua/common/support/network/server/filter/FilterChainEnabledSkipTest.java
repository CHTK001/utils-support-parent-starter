package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 过滤器链「停用即跳过」契约回归测试。
 *
 * <p>背景：{@code ServerFilter#isEnabled()} 的 Javadoc 明确「返回 false 时该过滤器将被跳过」，
 * 但过滤链的过滤器列表由 {@link ServerFilterManager} 缓存，而服务管理「过滤器热启停」只把实例
 * 置为停用、不重建列表。修复前两个链实现都不逐请求判定 {@code isEnabled()}，导致停用完全不生效
 * （实测：停用 file-storage 后目录页仍返回 200）。本测试钉住「列表不变、仅改实例开关」这一
 * 热启停场景下，两个链都必须跳过停用的过滤器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class FilterChainEnabledSkipTest {

    @Test
    void blockingChainSkipsDisabledFilterWithoutRebuildingList() throws Exception {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        ToggleFilter first = new ToggleFilter("a", a, 10);
        ToggleFilter second = new ToggleFilter("b", b, 20);
        // 固定列表：模拟 ServerFilterManager 缓存后的 merge 结果，全程不重建
        List<ServerFilter> chainFilters = List.of(first, second);

        ServerHandler handler = (req, resp) -> handled.incrementAndGet();
        ServerRequest request = req("/localFileStorage/");
        ServerResponse response = resp();
        new DefaultServerFilterChain(chainFilters, handler).doFilter(request, response);
        assertEquals(1, a.get(), "启用过滤器应执行");
        assertEquals(1, b.get(), "启用过滤器应执行");
        assertEquals(1, handled.get(), "链末端处理器应执行一次");

        // 就地停用 a（不重建列表）——下一次请求必须跳过 a
        first.setEnabled(false);
        new DefaultServerFilterChain(chainFilters, handler).doFilter(req("/localFileStorage/"), resp());
        assertEquals(1, a.get(), "停用后 a 不得再执行（列表未重建）");
        assertEquals(2, b.get(), "b 仍应执行");

        // 再启用 a —— 必须恢复执行（可反复，非一次性）
        first.setEnabled(true);
        new DefaultServerFilterChain(chainFilters, handler).doFilter(req("/localFileStorage/"), resp());
        assertEquals(2, a.get(), "重新启用后 a 应恢复执行");
    }

    @Test
    void reactiveChainSkipsDisabledFilterWrappedFromStatic() {
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        ServerFilterManager manager = new ServerFilterManager(ProtocolType.HTTP);
        ToggleFilter staticFilter = new ToggleFilter("fs", hits, 10);
        manager.addFilter(staticFilter);

        ServerHandler handler = (req, resp) -> handled.incrementAndGet();
        // 列表来自 manager 的缓存（同步过滤器被 wrapStatic 适配成响应式），后续不重建
        List<ReactiveServerFilter> reactive = manager.getMergedReactiveFilters();
        assertEquals(1, reactive.size());

        await(new DefaultReactiveFilterChain(reactive, handler).doFilter(req("/localFileStorage/"), resp()));
        assertEquals(1, hits.get(), "启用过滤器应执行");

        staticFilter.setEnabled(false);
        await(new DefaultReactiveFilterChain(reactive, handler).doFilter(req("/localFileStorage/"), resp()));
        assertEquals(1, hits.get(), "停用后响应式链必须跳过（适配器需透传 isEnabled）");

        staticFilter.setEnabled(true);
        await(new DefaultReactiveFilterChain(reactive, handler).doFilter(req("/localFileStorage/"), resp()));
        assertEquals(2, hits.get(), "重新启用后应恢复执行");
        assertEquals(3, handled.get());
    }

    private static void await(CompletionStage<Void> stage) {
        stage.toCompletableFuture().join();
    }

    private static ServerRequest req(String path) {
        return new StubRequest(path);
    }

    private static ServerResponse resp() {
        return new StubResponse();
    }

    /**
     * 可热切换启用的记录型过滤器（同步接口）。
     */
    private static final class ToggleFilter implements ServerFilter {
        private final String id;
        private final AtomicInteger hits;
        private final int order;
        private volatile boolean enabled = true;

        ToggleFilter(String id, AtomicInteger hits, int order) {
            this.id = id;
            this.hits = hits;
            this.order = order;
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
            hits.incrementAndGet();
            chain.doFilter(request, response);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public String getFilterId() {
            return id;
        }
    }

    private static final class StubRequest implements ServerRequest {
        private final String path;
        private final Map<String, Object> attributes = new HashMap<>();

        StubRequest(String path) {
            this.path = path;
        }

        @Override public String getUri() { return path; }
        @Override public String getPath() { return path; }
        @Override public HttpMethod getMethod() { return HttpMethod.GET; }
        @Override public String getHeader(String name) { return null; }
        @Override public HttpHeader getHeaders() { return null; }
        @Override public Map<String, String> getParams() { return Map.of(); }
        @Override public String getParam(String name) { return null; }
        @Override public String getContentType() { return null; }
        @Override public long getContentLength() { return 0; }
        @Override public byte[] getBody() { return new byte[0]; }
        @Override public String getBodyString() { return ""; }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public String getRemoteAddress() { return "127.0.0.1"; }
        @Override public int getRemotePort() { return 0; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
    }

    private static final class StubResponse implements ServerResponse {
        private boolean ended;

        @Override public ServerResponse setStatus(int statusCode) { return this; }
        @Override public int getStatus() { return 0; }
        @Override public ServerResponse setHeader(String name, String value) { return this; }
        @Override public String getHeader(String name) { return null; }
        @Override public HttpHeader getHeaders() { return null; }
        @Override public ServerResponse setContentType(String contentType) { return this; }
        @Override public String getContentType() { return null; }
        @Override public ServerResponse setBody(byte[] body) { return this; }
        @Override public ServerResponse setBody(String body) { return this; }
        @Override public byte[] getBody() { return new byte[0]; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public ServerResponse sendRedirect(String location) { return this; }
        @Override public ServerResponse sendError(int statusCode, String message) { return this; }
        @Override public void flush() { }
        @Override public boolean isCommitted() { return false; }
        @Override public boolean isEnded() { return ended; }
        @Override public ServerResponse reset() { ended = false; return this; }
        @Override public void end() { ended = true; }
        @Override public void writeRaw(byte[] bytes) { }
    }
}
