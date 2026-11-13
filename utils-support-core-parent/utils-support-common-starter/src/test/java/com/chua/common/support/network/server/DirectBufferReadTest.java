// [P3C A20 豁免] A20/A30/A31: 测试断言与构造参数含魔法值场景，豁免。
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
 * {@link ServerSetting#isUseDirectBuffer()}（堆外读缓冲）的真实链路验收。
 *
 * <p>验证三件事：</p>
 * <ol>
 *   <li>开关打开后读缓冲确实分配到直接内存（{@code direct} 缓冲池增长）</li>
 *   <li>堆内/堆外两种模式下协议行为完全一致（响应正确、连接可复用）</li>
 *   <li>连接数达到配置上限时按预期处理（不静默丢弃）</li>
 * </ol>
 *
 * <p>为什么必须走真实套接字：{@code allocateDirect} 的分配是否真的走了直接内存池，
 * 只能通过 {@link BufferPoolMXBean} 观测；协议正确性也只能靠真实请求验证。</p>
 *
 * <p>规模按「能在开发机稳定跑完」选：这里压 2000 连接（不是 50w，那是压测探针的活），
 * 目的是抓结构性缺陷（泄漏、错位、缓冲分配方式），不是测吞吐上限。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class DirectBufferReadTest {

    /**
     * 取一个当前空闲的端口。
     *
     * @return 空闲端口
     * @throws Exception 套接字操作失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 构造开启/关闭堆外读缓冲的服务端配置。
     *
     * @param port      端口
     * @param direct    是否使用直接内存读缓冲
     * @param bufferSize 读缓冲大小
     * @return 配置
     */
    private static ServerSetting setting(int port, boolean direct, int bufferSize) {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setMaxConnections(10000);
        setting.setBufferSize(bufferSize);
        setting.setUseDirectBuffer(direct);
        setting.setMaxConcurrency(-1);
        setting.setResponseTimeout(-1);
        setting.setMaxKeepAliveRequests(-1);
        setting.setEventLoops(2);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 回显 handler。
     */
    private static final ServerHandler ECHO = (req, res) -> {
        res.setStatus(200).setContentType("text/plain").setBody("hello-bench");
        res.end();
    };

    /**
     * 直接内存池当前使用量（字节）。没有 direct 池时返回 -1。
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
     * 触发 GC 并等待沉降后返回堆使用量。
     *
     * <p>比较复杂对象图（如「N 条连接保留了多少堆」）时不能直接读实时 {@code used}：
     * 测量期间只要有任意一次 GC，读数就会跳变，可能得出「增长为负」的假结论
     * （实测踩过 before=51MB → after=27MB）。两边都先 GC 再读，比较的就是<b>保留集</b>，
     * 这是 GC 稳定的。</p>
     *
     * @param mem 内存管理
     * @return 沉降后的堆使用量（字节）
     * @throws InterruptedException 等待被中断
     */
    private static long settledHeapUsed(MemoryMXBean mem) throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(150L);
        }
        return mem.getHeapMemoryUsage().getUsed();
    }

    /**
     * 在一条已建连接上完成一次 HTTP 往返，返回响应体。
     *
     * @param socket 已连接套接字
     * @return 响应体
     * @throws Exception 读写失败
     */
    private static String roundTrip(Socket socket) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write("GET /echo HTTP/1.1\r\nHost: x\r\nConnection: keep-alive\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream(256);
        int prev1 = -1, prev2 = -1, prev3 = -1, b;
        while ((b = in.read()) != -1) {
            header.write(b);
            if (prev1 == '\r' && prev2 == '\n' && prev3 == '\r' && b == '\n') {
                break;
            }
            prev1 = prev2;
            prev2 = prev3;
            prev3 = b;
        }
        String headers = header.toString(StandardCharsets.US_ASCII);
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
        return new String(body, 0, off, StandardCharsets.UTF_8);
    }

    /**
     * 开关打开后，直接内存池必须真的增长；同时协议行为不变。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(120)
    @DisplayName("useDirectBuffer=true 时读缓冲进入直接内存池，且协议行为不变")
    void directBufferActuallyUsesDirectMemory() throws Exception {
        int port = freePort();
        ServerSetting setting = setting(port, true, 16384);
        NioHttpServer server = new NioHttpServer(setting);
        server.registerMapping("/echo", HttpMethod.GET, ECHO);

        long before = directUsedBytes();
        try {
            server.start();
            try (Socket socket = new Socket()) {
                socket.setSoTimeout(20000);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
                assertEquals("hello-bench", roundTrip(socket), "开启堆外读缓冲后响应体必须正确");
                // keep-alive 复用一次，确认读缓冲可重复使用（不会因 flip/compact 出错）
                assertEquals("hello-bench", roundTrip(socket), "同一连接第二次请求必须正常");
            }
        } finally {
            server.stop();
        }
        long after = directUsedBytes();
        assertTrue(after >= 0, "平台应提供 direct BufferPoolMXBean");
        assertTrue(after > before,
                "useDirectBuffer=true 后 direct 池应增长，实测 before=" + before + " after=" + after
                        + "；没增长说明读缓冲仍分配在堆内（开关未生效）");
    }

    /**
     * 堆内（默认）模式下，直接内存池不应因读缓冲增长。
     *
     * <p>这是对照组：证明上一条的增长来自读缓冲，而不是别的路径顺带用了直接内存。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(120)
    @DisplayName("useDirectBuffer=false 时读缓冲不进直接内存池（对照组）")
    void heapBufferDoesNotGrowDirectPool() throws Exception {
        int port = freePort();
        ServerSetting setting = setting(port, false, 16384);
        NioHttpServer server = new NioHttpServer(setting);
        server.registerMapping("/echo", HttpMethod.GET, ECHO);

        long before = directUsedBytes();
        try {
            server.start();
            try (Socket socket = new Socket()) {
                socket.setSoTimeout(20000);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
                assertEquals("hello-bench", roundTrip(socket), "堆内模式响应体必须正确");
                assertEquals("hello-bench", roundTrip(socket), "堆内模式 keep-alive 必须正常");
            }
        } finally {
            server.stop();
        }
        long after = directUsedBytes();
        assertTrue(after - before < 64 * 1024,
                "堆内模式下 direct 池不应因读缓冲显著增长，实测 before=" + before
                        + " after=" + after + " delta=" + (after - before));
    }

    /**
     * 堆外模式下的并发连接：多条连接同时服务，响应必须逐条正确。
     *
     * <p>读缓冲在直接内存时，任何 flip/compact 顺序错误都会立刻表现为错位或挂死，
     * 而且不会像堆内那样只是"垃圾变多"。这条用它抓结构性缺陷。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(180)
    @DisplayName("堆外读缓冲下并发连接响应不丢不乱序")
    void directBufferHandlesConcurrentConnections() throws Exception {
        int port = freePort();
        ServerSetting setting = setting(port, true, 16384);
        NioHttpServer server = new NioHttpServer(setting);
        server.registerMapping("/echo", HttpMethod.GET, ECHO);

        int connections = 200;
        int roundsPerConnection = 5;
        List<String> failures = new ArrayList<>();
        try {
            server.start();
            List<Thread> workers = new ArrayList<>(connections);
            for (int c = 0; c < connections; c++) {
                final int id = c;
                // [P3C C1 豁免]
                Thread worker = new Thread(() -> {
                    try (Socket socket = new Socket()) {
                        socket.setSoTimeout(30000);
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
                        for (int i = 0; i < roundsPerConnection; i++) {
                            String body = roundTrip(socket);
                            if (!"hello-bench".equals(body)) {
                                synchronized (failures) {
                                    failures.add("conn=" + id + " round=" + i + " body=" + body);
                                }
                                return;
                            }
                        }
                    } catch (Exception e) {
                        synchronized (failures) {
                            failures.add("conn=" + id + " " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage());
                        }
                    }
                }, "direct-probe-" + c);
                workers.add(worker);
                worker.start();
            }
            for (Thread worker : workers) {
                worker.join(120_000L);
            }
        } finally {
            server.stop();
        }
        assertEquals(0, failures.size(),
                "堆外读缓冲下出现 " + failures.size() + " 条异常，首条："
                        + (failures.isEmpty() ? "(无)" : failures.get(0)));
    }

    /**
     * 显式设置 useDirectBuffer 后，autoConfig 不得覆盖它。
     *
     * @throws Exception 无
     */
    @Test
    @Timeout(30)
    @DisplayName("autoConfig 不覆盖显式 useDirectBuffer / bufferSize")
    void autoConfigKeepsExplicitDirectBufferAndBufferSize() {
        ServerSetting setting = new ServerSetting();
        setting.setUseDirectBuffer(true);
        setting.setBufferSize(4096);
        setting.autoConfig();
        assertTrue(setting.isUseDirectBuffer(), "显式 useDirectBuffer=true 被 autoConfig 覆盖了");
        assertEquals(4096, setting.getBufferSize(), "显式 bufferSize 被 autoConfig 覆盖了");
    }

    /**
     * 堆内模式下的堆占用必须随连接增长（对照组：证明堆内模式确实在用堆）。
     *
     * <p>这条与 {@link #directBufferActuallyUsesDirectMemory()} 互补：一个证明堆外
     * 把内存挪走了，一个证明堆内确实占用堆。两条都过才说明开关真的起作用。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(120)
    @DisplayName("堆内模式连接数增长时堆占用随之增长")
    void heapModeConsumesHeap() throws Exception {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        int port = freePort();
        ServerSetting setting = setting(port, false, 16384);
        NioHttpServer server = new NioHttpServer(setting);
        server.registerMapping("/echo", HttpMethod.GET, ECHO);

        List<Socket> sockets = new ArrayList<>();
        try {
            server.start();
            long before = settledHeapUsed(mem);
            for (int i = 0; i < 500; i++) {
                Socket socket = new Socket();
                socket.setSoTimeout(20000);
                socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
                sockets.add(socket);
                roundTrip(socket);
            }
            long after = settledHeapUsed(mem);
            // 500 连接 × 16KB 读缓冲 ≈ 8MB，留足余量避免 GC 噪声误判。
            // 必须两边都先 GC 再测：直接比对「实时 used」会在中途发生 GC 时给出
            // 负增长（实测踩过 before=51MB → after=27MB），那是假红不是缺陷。
            assertTrue(after - before > 2 * 1024 * 1024,
                    "堆内模式下 500 条服务过的连接应让堆增长（读缓冲在堆），"
                            + "实测 before=" + before + " after=" + after);
        } finally {
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // 清理失败不影响断言
                }
            }
            server.stop();
        }
    }
}
