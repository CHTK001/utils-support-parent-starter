// [P3C A20 豁免] A20/A30/A31: 规模压测参数含魔法值场景，豁免。
package com.chua.common.support.network.server;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接规模压测（50 万级）：默认跳过，用系统属性显式开启。
 *
 * <p><b>为什么不放在默认 {@code mvn test} 里</b>：50 万连接是容量验收，不是回归测试。
 * 它依赖机器内存、内核参数（句柄/fd）与可用端口区间，任何一台开发机跑都会是环境性失败，
 * 却会被误判成代码缺陷。所以默认 {@code @Disabled} 语义（用系统属性控制），
 * 只在明确做容量验收时执行。</p>
 *
 * <p><b>运行方式</b>：</p>
 * <pre>
 * mvn -o -DskipTests=false -Dtest=ConnectionScaleLoadTest \
 *     -Dscale.connections=500000 \
 *     -Dscale.direct=true \
 *     -Dscale.holdSeconds=30 \
 *     -DargLine="-Xmx4g -XX:MaxDirectMemorySize=24g" test
 * </pre>
 *
 * <p><b>参数</b>：</p>
 * <ul>
 *   <li>{@code scale.connections}：目标连接数，默认 500000；{@code <=0} 时整条用例跳过</li>
 *   <li>{@code scale.direct}：读缓冲是否用直接内存，默认 true（容量验收应走堆外）</li>
 *   <li>{@code scale.bufferSize}：读缓冲大小，默认 4096</li>
 *   <li>{@code scale.holdSeconds}：建立完成后保持连接的秒数，默认 30</li>
 *   <li>{@code scale.warm}：是否每条连接先发一次请求，默认 true</li>
 * </ul>
 *
 * <p><b>判据</b>：连接建立成功率、保持期内连接存活率、每条连接响应正确性，
 * 以及堆/直接内存的实际占用（打印出来供容量规划用）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class ConnectionScaleLoadTest {

    /**
     * 目标连接数（{@code <=0} 表示跳过）。
     */
    private static final int TARGET = Integer.getInteger("scale.connections", 0);

    /**
     * 读缓冲是否走直接内存。
     */
    private static final boolean DIRECT = Boolean.parseBoolean(System.getProperty("scale.direct", "true"));

    /**
     * 读缓冲大小。
     */
    private static final int BUFFER_SIZE = Integer.getInteger("scale.bufferSize", 4096);

    /**
     * 保持连接秒数。
     */
    private static final int HOLD_SECONDS = Integer.getInteger("scale.holdSeconds", 30);

    /**
     * 是否先做一次请求（warm）再保持。
     */
    private static final boolean WARM = Boolean.parseBoolean(System.getProperty("scale.warm", "true"));

    /**
     * 已建立连接的本地端口上限（Windows 动态端口区间可通过 netsh 调整）。
     */
    private static final int LOCAL_PORT_LIMIT_HINT = 55536;

    /**
     * 客户端可显式绑定的源端口起始值（避开系统保留区间）。
     *
     * <p>必须与服务端监听端口区间<b>不重叠</b>：若源端口区间覆盖了某个目标端口，
     * 当 {@code sourcePort == targetPort} 时客户端 bind 必然失败（该端口已被服务端占用），
     * 实测会稳定丢掉 1/portCount 的连接。</p>
     */
    private static final int SOURCE_PORT_BASE = 10000;

    /**
     * 客户端可显式绑定的源端口数量（Windows 动态端口区间 10000-65535，与目标端口区间错开）。
     */
    private static final int SOURCE_PORT_RANGE = 45000;

    /**
     * 服务端监听端口起点（避开客户端源端口区间 10000..54999）。
     */
    private static final int SERVER_PORT_BASE = 56000;

    /**
     * 服务端监听端口上限（56000..60999）。
     */
    private static final int SERVER_PORT_MAX = 60999;

    /**
     * 源 IP 末段上限（127.0.0.1 .. 127.0.0.MAX_SOURCE_OCTET）。
     *
     * <p>实测结论（Windows）：</p>
     * <ul>
     *   <li>同一 {@code 源IP:源端口} 无法被多个 socket 同时持有，即使目标端口不同
     *       （{@code BindException: Address already in use}）——所以「多目标端口」单独不够。</li>
     *   <li>不同源 IP 可以复用同一源端口连同一目标（127.0.0.1~5:45000 全部成功）。</li>
     * </ul>
     * <p>因此容量 = 源端口数 × 源IP数，50 万连接需要约 12 个源 IP。</p>
     */
    private static final int MAX_SOURCE_OCTET = 20;

    /**
     * 回显 handler。
     */
    private static final ServerHandler ECHO = (req, res) -> {
        res.setStatus(200).setContentType("text/plain").setBody("ok");
        res.end();
    };

    /**
     * 在指定区间内找一个空闲端口。
     *
     * @param from 起始端口（含）
     * @param to   结束端口（含）
     * @return 空闲端口
     * @throws IllegalStateException 区间内无可用端口
     */
    private static int freePortInRange(int from, int to) {
        for (int port = from; port <= to; port++) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress("127.0.0.1", port));
                return port;
            } catch (Exception ignored) {
                // 该端口不可用，试下一个
            }
        }
        throw new IllegalStateException("端口区间 " + from + "-" + to + " 内没有可用端口");
    }

    /**
     * 建一条连接。
     *
     * @param port       服务端目标端口
     * @param sourceIp   客户端源 IP（127.0.0.x）
     * @param sourcePort 客户端源端口
     * @return 连接，失败返回 null
     */
    private static Socket connect(int port, String sourceIp, int sourcePort) {
        Socket socket = new Socket();
        try {
            socket.setReceiveBufferSize(4096);
            socket.setSoTimeout(30000);
            socket.setTcpNoDelay(true);
            socket.bind(new InetSocketAddress(sourceIp, sourcePort));
            socket.connect(new InetSocketAddress("127.0.0.1", port), 30000);
            return socket;
        } catch (Exception e) {
            try {
                socket.setSoLinger(true, 0);
            } catch (Exception ignored) {
                // 设置失败也继续关
            }
            try {
                socket.close();
            } catch (Exception ignored) {
                // 关闭失败不影响返回
            }
            return null;
        }
    }

    /**
     * 用「源 IP × 源端口」的二维组合建连，失败时微调源端口重试。
     *
     * @param port       服务端目标端口
     * @param sourceIp   源 IP
     * @param preferred  首选源端口偏移
     * @return 连接，全部失败返回 null
     */
    private static Socket connectPreferred(int port, String sourceIp, int preferred) {
        for (int attempt = 0; attempt < 16; attempt++) {
            int sourcePort = SOURCE_PORT_BASE
                    + Math.floorMod(preferred + attempt, SOURCE_PORT_RANGE);
            Socket socket = connect(port, sourceIp, sourcePort);
            if (socket != null) {
                return socket;
            }
        }
        return null;
    }

    /**
     * 完成一次 HTTP 往返，返回是否拿到 200 与正确响应体。
     *
     * @param socket 连接
     * @return 成功返回 true
     */
    private static boolean serve(Socket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write("GET /echo HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream(256);
            int p1 = -1;
            int p2 = -1;
            int p3 = -1;
            int b;
            while ((b = in.read()) != -1) {
                header.write(b);
                if (p1 == '\r' && p2 == '\n' && p3 == '\r' && b == '\n') {
                    break;
                }
                p1 = p2;
                p2 = p3;
                p3 = b;
            }
            String headers = header.toString(StandardCharsets.US_ASCII);
            if (!headers.startsWith("HTTP/1.1 200")) {
                return false;
            }
            int len = 0;
            for (String line : headers.split("\r\n")) {
                int idx = line.indexOf(':');
                if (idx > 0 && "content-length".equalsIgnoreCase(line.substring(0, idx).trim())) {
                    len = Integer.parseInt(line.substring(idx + 1).trim());
                }
            }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(body, off, len - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            return "ok".equals(new String(body, 0, off, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安静关闭：用 {@code SO_LINGER=0} 走 RST，避免客户端留几十万个 TIME_WAIT
     * 把动态端口区间吃光（否则同一台机器第二次跑必然连不上）。
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
     * 直接内存池当前使用量（字节）。
     *
     * @return 字节数
     */
    private static long directUsedBytes() {
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                return pool.getMemoryUsed();
            }
        }
        return -1L;
    }

    /**
     * 50 万级连接压测：建立 → 保持 → 逐条确认存活。
     *
     * <p>调用示例见类注释。未设置 {@code -Dscale.connections} 时直接跳过（<b>这是刻意的</b>：
     * 容量验收不能混进常规回归，否则开发机跑必然因端口/内核参数失败）。</p>
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(1800)
    @DisplayName("连接规模压测（默认跳过，用 -Dscale.connections 开启）")
    void scaleConnections() throws Exception {
        if (TARGET <= 0) {
            System.out.println("[scale] SKIPPED: 未设置 -Dscale.connections（容量验收不跑在常规回归里）");
            return;
        }
        if (TARGET > LOCAL_PORT_LIMIT_HINT) {
            System.out.println("[scale] INFO: 目标 " + TARGET
                    + " 超过单 IP 动态端口数 " + LOCAL_PORT_LIMIT_HINT
                    + "，将使用多个 127.0.0.x 源 IP 复用源端口区间");
        }

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long heapBefore = mem.getHeapMemoryUsage().getUsed();
        long directBefore = directUsedBytes();

        // 容量 = 源端口数 × 源IP数：单端口即可，不需要多实例（省掉多份事件循环线程）
        int sourceIpCount = Math.min(MAX_SOURCE_OCTET,
                Math.max(1, (TARGET + SOURCE_PORT_RANGE - 1) / SOURCE_PORT_RANGE));
        int port = freePortInRange(SERVER_PORT_BASE, SERVER_PORT_MAX);

        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setMaxConnections(TARGET + 1000);
        setting.setBufferSize(BUFFER_SIZE);
        setting.setUseDirectBuffer(DIRECT);
        setting.setMaxConcurrency(-1);
        setting.setResponseTimeout(-1);
        setting.setMaxKeepAliveRequests(-1);
        setting.setEventLoops(Math.max(2, Runtime.getRuntime().availableProcessors()));
        setting.setAuto(false);

        NioHttpServer server = new NioHttpServer(setting);
        server.registerMapping("/echo", HttpMethod.GET, ECHO);
        System.out.println("[scale] server port=" + port
                + " sourceIps=" + sourceIpCount + " sourcePortRange=" + SOURCE_PORT_RANGE
                + " capacity=" + (long) sourceIpCount * SOURCE_PORT_RANGE
                + " target=" + TARGET + " direct=" + DIRECT + " bufferSize=" + BUFFER_SIZE);
        System.out.flush();

        List<Socket> connections = new ArrayList<>(Math.min(TARGET, 1 << 20));
        long connectStartNs = System.nanoTime();
        int connectFail = 0;
        try {
            server.start();
            for (int i = 0; i < TARGET; i++) {
                // 先用尽源 IP，再轮转源端口：同一 (源IP,源端口) 绝不重复
                int sourceOctet = 1 + (i % sourceIpCount);
                String sourceIp = "127.0.0." + sourceOctet;
                int preferredPort = (i / sourceIpCount) % SOURCE_PORT_RANGE;
                Socket socket = connectPreferred(port, sourceIp, preferredPort);
                if (socket == null) {
                    connectFail++;
                    continue;
                }
                connections.add(socket);
                if ((i + 1) % 50000 == 0) {
                    System.out.println("[scale] connected=" + (i + 1) + " fail=" + connectFail
                            + " heapMb=" + (mem.getHeapMemoryUsage().getUsed() / (1024 * 1024))
                            + " directMb=" + (directUsedBytes() / (1024 * 1024)));
                    System.out.flush();
                }
            }
            long connectMs = (System.nanoTime() - connectStartNs) / 1_000_000L;
            long heapAfterConnect = mem.getHeapMemoryUsage().getUsed();
            long directAfterConnect = directUsedBytes();

            System.out.println("[scale] CONNECTED total=" + connections.size()
                    + " fail=" + connectFail + " elapsedMs=" + connectMs
                    + " heapUsedMb=" + (heapAfterConnect / (1024 * 1024))
                    + " directUsedMb=" + (directAfterConnect / (1024 * 1024))
                    + " heapDeltaMb=" + ((heapAfterConnect - heapBefore) / (1024 * 1024))
                    + " directDeltaMb=" + ((directAfterConnect - directBefore) / (1024 * 1024)));
            System.out.flush();

            int warmOk = 0;
            if (WARM) {
                long warmStartNs = System.nanoTime();
                for (Socket socket : connections) {
                    if (serve(socket)) {
                        warmOk++;
                    }
                }
                System.out.println("[scale] WARMED ok=" + warmOk + "/" + connections.size()
                        + " elapsedMs=" + ((System.nanoTime() - warmStartNs) / 1_000_000L)
                        + " heapUsedMb=" + (mem.getHeapMemoryUsage().getUsed() / (1024 * 1024))
                        + " directUsedMb=" + (directUsedBytes() / (1024 * 1024)));
                System.out.flush();
            }

            MemoryUsage heapHold = mem.getHeapMemoryUsage();
            System.out.println("[scale] HOLDING " + HOLD_SECONDS + "s heapUsedMb="
                    + (heapHold.getUsed() / (1024 * 1024))
                    + " heapCommitMb=" + (heapHold.getCommitted() / (1024 * 1024))
                    + " directUsedMb=" + (directUsedBytes() / (1024 * 1024))
                    + " active=" + server.getMetrics().getActiveConnections());
            System.out.flush();
            Thread.sleep(HOLD_SECONDS * 1000L);

            int alive = 0;
            for (Socket socket : connections) {
                if (serve(socket)) {
                    alive++;
                }
            }
            System.out.println("[scale] ALIVE after hold = " + alive + "/" + connections.size()
                    + " heapUsedMb=" + (mem.getHeapMemoryUsage().getUsed() / (1024 * 1024))
                    + " directUsedMb=" + (directUsedBytes() / (1024 * 1024)));

            // 部分失败源于「系统临时占用了候选源端口」（不是服务端问题）：只要失败比例很低，
// 且成功建立的连接 100% 可用，就说明容量达标。阈值取 0.1%，避免偶发端口冲突误判。
            assertTrue(connectFail <= Math.max(10, connections.size() / 1000),
                    "建连失败 " + connectFail + "/" + TARGET
                            + " 条，超过 0.1% 的容忍阈值（服务端拒绝或端口资源不足）");
            assertEquals(connections.size(), alive,
                    "保持期后存活 " + alive + "/" + connections.size()
                            + "；连接被服务端主动断开或响应损坏");
            if (WARM) {
                assertEquals(connections.size(), warmOk,
                        "warm 阶段成功 " + warmOk + "/" + connections.size());
            }
        } finally {
            for (Socket socket : connections) {
                closeQuietly(socket);
            }
            server.stop();
            System.out.println("[scale] server activeAfterStop="
                    + server.getMetrics().getActiveConnections());
        }
    }
}
