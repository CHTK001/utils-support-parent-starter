// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTestSupport.EchoBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static com.chua.common.support.network.server.proxy.ProxyTestSupport.closedPort;
import static com.chua.common.support.network.server.proxy.ProxyTestSupport.readFully;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AioTcpProxyServer} 的真实链路回归。
 *
 * <p>AIO 与 JDK 版走的是完全不同的传输路径（{@code AsynchronousSocketChannel} +
 * 回调链 + 后端连接池），JDK 版通过不代表 AIO 版通过，必须单独测。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class AioTcpProxyServerTest {

    /**
     * 大包用例的负载大小（字节）。
     */
    private static final int BIG_PAYLOAD = 256 * 1024;

    /**
     * 并发用例的连接数。
     */
    private static final int CONCURRENCY = 30;

    /**
     * 代理监听配置。
     *
     * @return 配置
     */
    private static ServerSetting setting() {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        setting.setAuto(false);
        setting.setEventLoops(2);
        return setting;
    }

    /**
     * 创建指向后端的 AIO 代理（绑定随机空闲端口）。
     *
     * @param backendPort 后端端口
     * @return 已构造未启动的代理
     */
    private static AioTcpProxyServer newProxy(int backendPort) {
        return new AioTcpProxyServer(setting()).setTarget("127.0.0.1", backendPort);
    }

    /**
     * 双向字节原样转发。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("AIO TCP 代理：双向字节原样转发")
    void forwardsBytesBothWays() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            AioTcpProxyServer proxy = newProxy(backend.port());
            proxy.start();
            try {
                int port = proxy.getPort();
                assertTrue(port > 0, "代理未绑定端口");
                try (Socket client = new Socket("127.0.0.1", port)) {
                    client.setSoTimeout(8000);
                    String text = "aio-hello";
                    client.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    byte[] buffer = new byte[64];
                    int read = readFully(client.getInputStream(), buffer, text.length());
                    assertEquals(text, new String(buffer, 0, read, StandardCharsets.UTF_8),
                            "经 AIO 代理往返的内容不一致");
                }
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 256KB 穿越 AIO 代理后逐字节一致。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("AIO TCP 代理：256KB 大包不截断")
    void forwardsLargePayloadIntact() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            AioTcpProxyServer proxy = newProxy(backend.port());
            proxy.start();
            try {
                byte[] payload = new byte[BIG_PAYLOAD];
                new Random(7L).nextBytes(payload);
                try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                    client.setSoTimeout(30000);
                    OutputStream out = client.getOutputStream();
                    out.write(payload);
                    out.flush();

                    byte[] echoed = new byte[BIG_PAYLOAD];
                    int read = readFully(client.getInputStream(), echoed, BIG_PAYLOAD);
                    assertEquals(BIG_PAYLOAD, read, "大包未完整回传");
                    assertTrue(Arrays.equals(payload, echoed), "大包内容被改写");
                }
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 后端不可达时必须快速断开，不能把客户端挂住。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("AIO TCP 代理：后端不可达时快速断开")
    void closesWhenBackendUnreachable() throws Exception {
        AioTcpProxyServer proxy = newProxy(closedPort());
        proxy.start();
        try {
            try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                client.setSoTimeout(15000);
                client.getOutputStream().write('z');
                client.getOutputStream().flush();

                long start = System.currentTimeMillis();
                int read;
                boolean failedFast;
                try {
                    read = client.getInputStream().read();
                    failedFast = read == -1;
                } catch (java.io.IOException e) {
                    // 客户端有未读数据时代理关闭会发 RST，表现为 Connection reset 而不是 EOF；
                    // 判据是「快速失败」，不要绑定平台差异。
                    read = -2;
                    failedFast = true;
                }
                long elapsed = System.currentTimeMillis() - start;
                assertTrue(failedFast, "后端不可达时应返回 EOF 或连接重置，实际读到: " + read);
                assertTrue(elapsed < 12000L, "后端不可达时耗时 " + elapsed + "ms，应快速断开");
            }
        } finally {
            proxy.stop();
        }
    }

    /**
     * 30 条并发连接各自拿回自己的回包。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("AIO TCP 代理：30 并发不串包")
    void concurrentConnectionsDoNotCrosstalk() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            AioTcpProxyServer proxy = newProxy(backend.port());
            proxy.start();
            try {
                int port = proxy.getPort();
                Thread[] workers = new Thread[CONCURRENCY];
                Throwable[] failures = new Throwable[CONCURRENCY];
                for (int i = 0; i < CONCURRENCY; i++) {
                    final int index = i;
                    workers[i] = Thread.ofVirtual().start(() -> {
                        String payload = "aio-payload-" + index;
                        try (Socket client = new Socket("127.0.0.1", port)) {
                            client.setSoTimeout(15000);
                            client.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
                            client.getOutputStream().flush();
                            byte[] buffer = new byte[64];
                            int read = readFully(client.getInputStream(), buffer, payload.length());
                            String echoed = new String(buffer, 0, read, StandardCharsets.UTF_8);
                            if (!payload.equals(echoed)) {
                                throw new IllegalStateException("串包：期望 " + payload + " 实际 " + echoed);
                            }
                        } catch (Throwable e) {
                            failures[index] = e;
                        }
                    });
                }
                for (Thread worker : workers) {
                    worker.join(40000L);
                }
                for (int i = 0; i < CONCURRENCY; i++) {
                    if (failures[i] != null) {
                        throw new AssertionError("第 " + i + " 条连接失败: " + failures[i], failures[i]);
                    }
                }
            } finally {
                proxy.stop();
            }
        }
    }
}
