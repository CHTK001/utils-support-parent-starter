package com.chua.kcp.support;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.kcp.support.client.KcpClient;
import com.chua.kcp.support.server.KcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KcpServer ServerFilter 体系接入测试。
 *
 * <p>验证 KCP 消息链路接入统一过滤器链：自定义 {@link ServerFilter}
 * 能拦截到 topic/payload（协议无关 request 视图），且链尾业务分发（订阅者）仍生效。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class KcpServerFilterTest {

    private static final String CLIENT_ID = "kcp-filter-client";

    private KcpServer server;
    private KcpClient client;
    private int port;

    /**
     * 记录拦截消息的自定义过滤器。
     */
    static class RecordingFilter implements ServerFilter {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
            paths.add(request.getPath());
            bodies.add(request.getBodyString());
            latch.countDown();
            chain.doFilter(request, response);
        }

        @Override
        public ProtocolType[] supportProtocols() {
            return new ProtocolType[]{ProtocolType.KCP};
        }
    }

    /**
     * 声明仅支持 UDP 的过滤器：KCP 与 UDP 兼容，应同样拦截 KCP 消息。
     */
    static class UdpCompatibleFilter implements ServerFilter {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
            paths.add(request.getPath());
            latch.countDown();
            chain.doFilter(request, response);
        }

        @Override
        public ProtocolType[] supportProtocols() {
            return new ProtocolType[]{ProtocolType.UDP};
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        server = new KcpServer(ServerSetting.builder()
                .host("127.0.0.1").port(port).protocol("kcp").build());
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignore) {
            }
        }
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    void filterInterceptsKcpMessage() throws Exception {
        RecordingFilter filter = new RecordingFilter();
        server.addFilter(filter);
        server.start();

        // 服务端订阅者：验证 filter 链尾业务分发仍生效
        CountDownLatch received = new CountDownLatch(1);
        List<String> subscriberPayloads = new CopyOnWriteArrayList<>();
        server.onSubscribe("echo/#", (topic, payload) -> {
            subscriberPayloads.add(payload);
            received.countDown();
        });

        client = new KcpClient(CLIENT_ID, "kcp://127.0.0.1:" + port);
        client.connect();
        client.publish("echo/ann", "hello-filter");

        assertTrue(filter.latch.await(3, TimeUnit.SECONDS), "自定义 filter 应在超时前拦截到消息");
        assertEquals(List.of("echo/ann"), filter.paths, "filter 应看到 topic 作为 path");
        assertEquals(List.of("hello-filter"), filter.bodies, "filter 应看到 payload 作为 body");

        assertTrue(received.await(3, TimeUnit.SECONDS), "filter 链尾订阅者应收到消息");
        assertEquals(List.of("hello-filter"), subscriberPayloads);
    }

    @Test
    void udpCompatibleFilterInterceptsKcpMessage() throws Exception {
        UdpCompatibleFilter filter = new UdpCompatibleFilter();
        server.addFilter(filter);
        server.start();

        client = new KcpClient(CLIENT_ID + "-udp", "kcp://127.0.0.1:" + port);
        client.connect();
        client.publish("echo/udp", "hello-udp-compat");

        assertTrue(filter.latch.await(3, TimeUnit.SECONDS),
                "声明支持 UDP 的 filter 应与 KCP 兼容，拦截到 KCP 消息");
        assertEquals(List.of("echo/udp"), filter.paths, "UDP filter 应看到 KCP 消息的 topic");
    }

    @Test
    void defaultFiltersDoNotBreakKcpMessage() throws Exception {
        server.start();

        CountDownLatch received = new CountDownLatch(1);
        List<String> subscriberPayloads = new CopyOnWriteArrayList<>();
        server.onSubscribe("order/#", (topic, payload) -> {
            subscriberPayloads.add(payload);
            received.countDown();
        });

        client = new KcpClient(CLIENT_ID + "-2", "kcp://127.0.0.1:" + port);
        client.connect();
        client.publish("order/ann", "ping");

        assertTrue(received.await(3, TimeUnit.SECONDS), "内置过滤器(Gzip/AccessLog 等)不应阻断 KCP 消息");
        assertEquals(List.of("ping"), subscriberPayloads);
    }

    /**
     * 获取空闲端口。
     *
     * @return 空闲端口
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
