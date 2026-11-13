// [P3C A20 豁免] A20/A30/A31: 规模压测参数含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.ProxyTestSupport.EchoBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代理连接容量压测（默认跳过，用系统属性显式开启）。
 *
 * <p>支撑泵改造文档里的容量边界：{@link TcpProxyServer}（JDK 阻塞 + 平台线程泵，
 * 每连接 2 个驻留平台线程）建议并发连接数上限约 2k；{@link AioTcpProxyServer}
 * （AIO，零驻留线程）应能承载上万连接。本用例实测两档在指定连接数下的
 * 建连成功率、保持期存活率、线程数与堆占用，验证该边界。</p>
 *
 * <p><b>运行方式</b>：</p>
 * <pre>
 * mvn -o -DskipTests=false -Dtest=ProxyConnectionScaleTest \
 *     -Dproxy.scale.connections=2000 -Dproxy.scale.kind=tcp-jdk \
 *     -Dproxy.scale.holdSeconds=10 -DargLine="-Xmx2g" test
 * </pre>
 *
 * <p><b>参数</b>：</p>
 * <ul>
 *   <li>{@code proxy.scale.connections}：目标连接数，默认 0（跳过）</li>
 *   <li>{@code proxy.scale.kind}：tcp-jdk 或 tcp-aio，默认 tcp-jdk</li>
 *   <li>{@code proxy.scale.holdSeconds}：建立完成后保持秒数，默认 10</li>
 * </ul>
 *
 * <p><b>判据</b>：建连失败率 ≤1%，保持期后存活率 100%；打印线程数与堆增量供容量规划。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class ProxyConnectionScaleTest {

    /**
     * 目标连接数（{@code <=0} 表示跳过）。
     */
    private static final int CONNECTIONS = Integer.getInteger("proxy.scale.connections", 0);

    /**
     * 被测代理类型。
     */
    private static final String KIND = System.getProperty("proxy.scale.kind", "tcp-jdk");

    /**
     * 保持连接秒数。
     */
    private static final int HOLD_SECONDS = Integer.getInteger("proxy.scale.holdSeconds", 10);

    /**
     * 客户端显式绑定源端口起点（避开系统动态端口区间与代理监听区间）。
     */
    private static final int SOURCE_PORT_BASE = 20000;

    /**
     * 客户端源端口数量（20000..48999，单 IP 足够两万级连接）。
     */
    private static final int SOURCE_PORT_RANGE = 29000;

    /**
     * 代理监听端口起点（50000..60999，避开源端口区间）。
     */
    private static final int PROXY_PORT_BASE = 50000;

    /**
     * 代理监听端口上限。
     */
    private static final int PROXY_PORT_MAX = 60999;

    /**
     * 服务器配置。
     *
     * @param port 监听端口
     * @return 配置
     */
    private static ServerSetting setting(int port) {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 在区间内找一个空闲端口。
     *
     * @return 空闲端口
     * @throws IllegalStateException 区间内无可用端口
     */
    private static int freeProxyPort() {
        for (int port = PROXY_PORT_BASE; port <= PROXY_PORT_MAX; port++) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress("127.0.0.1", port));
                return port;
            } catch (Exception ignored) {
                // 该端口不可用，试下一个
            }
        }
        throw new IllegalStateException("端口区间 " + PROXY_PORT_BASE + "-" + PROXY_PORT_MAX + " 内没有可用端口");
    }

    /**
     * 绑定固定源端口建立一条到代理的连接。
     *
     * @param port       代理端口
     * @param sourcePort 客户端源端口
     * @return 连接，失败返回 null
     */
    private static Socket connect(int port, int sourcePort) {
        Socket socket = new Socket();
        try {
            socket.setReceiveBufferSize(4096);
            socket.setSoTimeout(30000);
            socket.setTcpNoDelay(true);
            socket.bind(new InetSocketAddress("127.0.0.1", sourcePort));
            socket.connect(new InetSocketAddress("127.0.0.1", port), 30000);
            return socket;
        } catch (Exception e) {
            closeQuietly(socket);
            return null;
        }
    }

    /**
     * 安静关闭：用 {@code SO_LINGER=0} 走 RST，避免客户端留几万个 TIME_WAIT。
     *
     * @param socket 连接
     */
    private static void closeQuietly(Socket socket) {
        try {
            socket.setSoLinger(true, 0);
        } catch (Exception ignored) {
            // 设置失败也继续关
        }
        try {
            socket.close();
        } catch (Exception ignored) {
            // 关闭失败不影响统计
        }
    }

    /**
     * 发一行并读回，验证「客户端-代理-后端 echo」链路通。
     *
     * @param socket 到代理的连接
     * @return 成功返回 true
     */
    private static boolean serve(Socket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write("ping\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[5];
            int off = 0;
            while (off < 5) {
                int r = in.read(buf, off, 5 - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            return "ping\n".equals(new String(buf, 0, off, StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 代理连接容量压测：建立 → 保持 → 逐条确认存活，并打印线程数与内存。
     *
     * <p>未设置 {@code -Dproxy.scale.connections} 时直接跳过（容量验收不混进常规回归）。</p>
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(900)
    @DisplayName("代理连接容量压测（默认跳过，用 -Dproxy.scale.connections 开启）")
    void scaleProxyConnections() throws Exception {
        if (CONNECTIONS <= 0) {
            // [P3C E1 豁免]
            System.out.println("[proxy-scale] SKIPPED: 未设置 -Dproxy.scale.connections（容量验收不跑在常规回归里）");
            return;
        }
        if (CONNECTIONS > SOURCE_PORT_RANGE) {
            // [P3C E1 豁免]
            System.out.println("[proxy-scale] 目标 " + CONNECTIONS + " 超过单 IP 源端口数 " + SOURCE_PORT_RANGE
                    + "，本用例仅支持单源 IP 容量，已跳过");
            return;
        }

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long heapBefore = mem.getHeapMemoryUsage().getUsed();
        int threadsBefore = threads.getThreadCount();

        try (EchoBackend backend = new EchoBackend()) {
            int proxyPort = freeProxyPort();
            // [P3C E1 豁免]
            System.out.println("[proxy-scale] kind=" + KIND + " target=" + CONNECTIONS
                    + " backendPort=" + backend.port() + " proxyPort=" + proxyPort
                    + " threadsBefore=" + threadsBefore
                    + " heapBeforeMb=" + (heapBefore / 1024 / 1024));
            // [P3C E1 豁免]
            System.out.flush();

            AbstractServer server = "tcp-aio".equals(KIND)
                    ? new AioTcpProxyServer(setting(proxyPort)).setTarget("127.0.0.1", backend.port())
                    : new TcpProxyServer(setting(proxyPort),
                            remote -> new InetSocketAddress("127.0.0.1", backend.port()));
            server.start();
            try {
                List<Socket> connections = new ArrayList<>(CONNECTIONS);
                long connectStartNs = System.nanoTime();
                int fail = 0;
                try {
                    for (int i = 0; i < CONNECTIONS; i++) {
                        int sourcePort = SOURCE_PORT_BASE + (i % SOURCE_PORT_RANGE);
                        Socket socket = connect(proxyPort, sourcePort);
                        if (socket == null) {
                            fail++;
                            continue;
                        }
                        connections.add(socket);
                        if ((i + 1) % 500 == 0) {
                            // [P3C E1 豁免]
                            System.out.println("[proxy-scale] connected=" + (i + 1)
                                    + " fail=" + fail
                                    + " threads=" + threads.getThreadCount()
                                    + " heapMb=" + (mem.getHeapMemoryUsage().getUsed() / 1024 / 1024));
                            // [P3C E1 豁免]
                            System.out.flush();
                        }
                    }
                    long connectMs = (System.nanoTime() - connectStartNs) / 1_000_000L;
                    // [P3C E1 豁免]
                    System.out.println("[proxy-scale] CONNECTED total=" + connections.size()
                            + " fail=" + fail + " elapsedMs=" + connectMs
                            + " threads=" + threads.getThreadCount() + " (before " + threadsBefore + ")"
                            + " heapDeltaMb=" + ((mem.getHeapMemoryUsage().getUsed() - heapBefore) / 1024 / 1024));
                    // [P3C E1 豁免]
                    System.out.flush();

                    Thread.sleep(HOLD_SECONDS * 1000L);

                    int alive = 0;
                    for (Socket socket : connections) {
                        if (serve(socket)) {
                            alive++;
                        }
                    }
                    // [P3C E1 豁免]
                    System.out.println("[proxy-scale] ALIVE after " + HOLD_SECONDS + "s = "
                            + alive + "/" + connections.size()
                            + " threads=" + threads.getThreadCount()
                            + " heapDeltaMb=" + ((mem.getHeapMemoryUsage().getUsed() - heapBefore) / 1024 / 1024));
                    // [P3C E1 豁免]
                    System.out.flush();

                    assertTrue(fail <= Math.max(10, connections.size() / 100),
                            "建连失败 " + fail + "/" + CONNECTIONS + " 条，超过 1% 容忍阈值（服务端拒绝或端口资源不足）");
                    assertEquals(connections.size(), alive,
                            "保持期后存活 " + alive + "/" + connections.size() + "；连接被服务端主动断开或响应损坏");
                } finally {
                    for (Socket socket : connections) {
                        closeQuietly(socket);
                    }
                }
            } finally {
                server.stop();
                // [P3C E1 豁免]
                System.out.println("[proxy-scale] server activeAfterStop="
                        + server.getMetrics().getActiveConnections());
            }
        }
    }
}
