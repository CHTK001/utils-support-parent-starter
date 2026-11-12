// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTestSupport.EchoBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static com.chua.common.support.network.server.proxy.ProxyTestSupport.closedPort;
import static com.chua.common.support.network.server.proxy.ProxyTestSupport.readFully;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TcpProxyServer} 的真实链路回归。
 *
 * <p><b>为什么必须有这些用例</b>：集群里 {@code ClusterNode} 就是拿 TcpProxyServer 做流量转发的，
 * 而它此前<b>一个用例都没有</b>——只被 example 和 ClusterNode new 过。这条链路挂了是集群级故障。</p>
 *
 * <p>覆盖四类真实故障面：双向转发正确、256KB 大包不丢、后端不可达要快速断开、50 并发不串包。
 * 另外校验解析器能拿到真实来源地址（按来源分流的基础）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class TcpProxyServerTest {

    /**
     * 代理侧后端连接/读写超时（毫秒）。
     */
    private static final int TIMEOUT_MS = 3000;

    /**
     * 大包用例的负载大小（字节）。
     */
    private static final int BIG_PAYLOAD = 256 * 1024;

    /**
     * 并发用例的连接数。
     */
    private static final int CONCURRENCY = 50;

    /**
     * 创建代理（绑定随机的空闲端口）。
     *
     * @param backendPort 后端端口
     * @return 已构造未启动的代理
     */
    private static TcpProxyServer newProxy(int backendPort) {
        return new TcpProxyServer(setting(),
                remote -> new InetSocketAddress("127.0.0.1", backendPort), TIMEOUT_MS, TIMEOUT_MS);
    }

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
        return setting;
    }

    /**
     * 双向字节原样转发。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("TCP 代理：双向字节原样转发")
    void forwardsBytesBothWays() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            TcpProxyServer proxy = newProxy(backend.port());
            proxy.start();
            try {
                int port = proxy.getPort();
                assertTrue(port > 0, "代理未绑定端口");
                try (Socket client = new Socket("127.0.0.1", port)) {
                    client.setSoTimeout(5000);
                    String text = "hello-proxy";
                    client.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    byte[] buffer = new byte[64];
                    int read = readFully(client.getInputStream(), buffer, text.length());
                    assertEquals(text, new String(buffer, 0, read, StandardCharsets.UTF_8),
                            "经代理往返的内容不一致");
                }
            } finally {
                proxy.stop();
            }
        }
    }

    /**
     * 256KB 单次穿越代理后逐字节一致（代理内部 64KB 缓冲会分成多轮转发）。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("TCP 代理：256KB 大包不截断")
    void forwardsLargePayloadIntact() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            TcpProxyServer proxy = newProxy(backend.port());
            proxy.start();
            try {
                byte[] payload = new byte[BIG_PAYLOAD];
                new Random(42L).nextBytes(payload);
                try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                    client.setSoTimeout(20000);
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
     * 后端不可达时必须快速让客户端收到 EOF，而不是挂着。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("TCP 代理：后端不可达时快速断开而非挂死")
    void closesWhenBackendUnreachable() throws Exception {
        TcpProxyServer proxy = newProxy(closedPort());
        proxy.start();
        try {
            try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                client.setSoTimeout(10000);
                client.getOutputStream().write('x');
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
                assertTrue(elapsed < 8000L, "后端不可达时耗时 " + elapsed + "ms，应快速断开");
            }
        } finally {
            proxy.stop();
        }
    }

    /**
     * 50 条并发连接各自拿回自己的回包（不串包）。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(120)
    @DisplayName("TCP 代理：50 并发不串包")
    void concurrentConnectionsDoNotCrosstalk() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            TcpProxyServer proxy = newProxy(backend.port());
            proxy.start();
            try {
                int port = proxy.getPort();
                Thread[] workers = new Thread[CONCURRENCY];
                Throwable[] failures = new Throwable[CONCURRENCY];
                for (int i = 0; i < CONCURRENCY; i++) {
                    final int index = i;
                    workers[i] = Thread.ofVirtual().start(() -> {
                        String payload = "payload-" + index;
                        try (Socket client = new Socket("127.0.0.1", port)) {
                            client.setSoTimeout(10000);
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
                    worker.join(30000L);
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

    /**
     * 解析器必须拿到真实客户端地址（按来源分流的基础）。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("TCP 代理：解析器收到真实客户端地址")
    void resolverSeesRemoteAddress() throws Exception {
        try (EchoBackend backend = new EchoBackend()) {
            AtomicReference<InetSocketAddress> seen = new AtomicReference<>();
            TcpProxyServer proxy = new TcpProxyServer(setting(), remote -> {
                seen.set(remote);
                return new InetSocketAddress("127.0.0.1", backend.port());
            }, TIMEOUT_MS, TIMEOUT_MS);
            proxy.start();
            try {
                try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
                    client.setSoTimeout(5000);
                    client.getOutputStream().write('y');
                    client.getOutputStream().flush();
                    assertEquals('y', client.getInputStream().read(), "转发未生效");
                }
                InetSocketAddress remote = seen.get();
                assertNotNull(remote, "解析器未被调用");
                assertTrue(remote.getAddress().isLoopbackAddress(),
                        "解析器拿到的来源不是本机回环: " + remote);
                assertTrue(remote.getPort() > 0, "解析器拿到的来源端口非法: " + remote);
            } finally {
                proxy.stop();
            }
        }
    }
}
