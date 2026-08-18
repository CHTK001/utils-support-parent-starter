package com.chua.common.support.network.server.filter.proxy;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.request.AbstractServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.response.AbstractServerResponse;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * HttpReverseProxyFilter 测试
 *
 * @author CH
 * @since 2026/07/26
 */
public class HttpReverseProxyFilterTest {

    /** Backend服务器 */
    private HttpServer backendServer;
    /** Proxy过滤器 */
    private HttpReverseProxyFilter proxyFilter;
    /** Backend端口 */
    private int backendPort;

    @BeforeEach
    public void setUp() throws Exception {
        CountDownLatch serverReady = new CountDownLatch(1);
        backendServer = HttpServer.create(new InetSocketAddress(0), 0);
        backendServer.createContext("/health", exchange -> {
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
            serverReady.countDown();
        });
        backendServer.createContext("/hello", exchange -> {
            String body = "Hello from backend";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        backendServer.setExecutor(null);
        backendServer.start();
        backendPort = backendServer.getAddress().getPort();

        assertTrue(serverReady.await(5, TimeUnit.SECONDS), "Backend server failed to start");

        proxyFilter = new HttpReverseProxyFilter();
        proxyFilter.init(mock(ServerFilterConfig.class));
    }

    @AfterEach
    public void tearDown() {
        if (proxyFilter != null) {
            proxyFilter.destroy();
        }
        if (backendServer != null) {
            backendServer.stop(0);
        }
    }

    @Test
    public void testProxyHealth() throws Exception {
        Discovery backendDiscovery = Discovery.builder()
                .host("127.0.0.1")
                .port(backendPort)
                .protocol("http")
                .build();

        ServerRequest request = createMockRequest("/health", "GET");
        ServerAttribute.setBackendDiscovery(request, backendDiscovery);

        CapturingServerResponse response = new CapturingServerResponse();

        proxyFilter.doFilter(request, response, (req, res) -> {
            fail("Chain should not be called when backend discovery is set");
        });

        assertTrue(response.await(10, TimeUnit.SECONDS), "Proxy response timeout");
        assertEquals(200, response.getStatus(), "Expected 200 from backend");
        assertTrue(new String(response.getBody()).contains("status"), "Response should contain health check data");
    }

    @Test
    public void testProxyPassThroughWhenNoDiscovery() throws Exception {
        ServerRequest request = createMockRequest("/health", "GET");

        CapturingServerResponse response = new CapturingServerResponse();
        java.util.concurrent.atomic.AtomicBoolean chainCalled = new java.util.concurrent.atomic.AtomicBoolean(false);

        proxyFilter.doFilter(request, response, (req, res) -> {
            chainCalled.set(true);
        });

        assertTrue(chainCalled.get(), "Chain should be called when no backend discovery");
    }

    private ServerRequest createMockRequest(String path, String method) {
        return new AbstractServerRequest() {
            @Override
            public HttpHeader getHeaders() {
                return HttpHeader.create();
            }

            @Override
            public String getHeader(String name) {
                return null;
            }

            @Override
            public String getUri() {
                return path;
            }

            @Override
            public String getPath() {
                return path;
            }

            @Override
            public HttpMethod getMethod() {
                return HttpMethod.valueOf(method);
            }

            @Override
            public String getRemoteAddress() {
                return "127.0.0.1";
            }

            @Override
            public int getRemotePort() {
                return 12345;
            }
        };
    }

    private static class CapturingServerResponse extends AbstractServerResponse {
        /** Latch */
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void end() {
            latch.countDown();
        }

        @Override
        public boolean isEnded() {
            return latch.getCount() == 0;
        }

        @Override
        public OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override
        public void writeRaw(byte[] bytes) {
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
}
