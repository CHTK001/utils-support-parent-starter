// [P3C A20 豁免] A20/A30/A31: 压测参数含魔法值场景，豁免。
package com.chua.socketio.support.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Socket.IO 规模压测：默认跳过，用 {@code -Dsio.scale.connections} 开启。
 *
 * <p><b>为什么默认跳过</b>：容量验收依赖机器资源（线程、端口、内存），任何开发机跑都会
 * 是环境性结果，不该混进常规回归。</p>
 *
 * <p><b>为什么客户端要另起进程</b>：服务端每连接内存必须用「服务端堆增量 ÷ 连接数」量。
 * 若客户端与服务端同 JVM，io.socket 客户端的 OkHttp 连接、Manager、线程栈会混进来，
 * 量到的是两者之和，不能用来判断服务端容量。</p>
 *
 * <p>用法：</p>
 * <pre>
 * mvn -o -DskipTests=false -Dtest=SocketIoScaleTest \
 *     -Dsio.scale.connections=500 -Dsio.scale.holdSeconds=15 test
 * </pre>
 *
 * @author CH
 * @since 4.0.0.52
 */
class SocketIoScaleTest {

    /**
     * 目标连接数（{@code <=0} 表示跳过）。
     */
    private static final int CLIENTS = Integer.getInteger("sio.scale.connections", 0);

    /**
     * 保持秒数。
     */
    private static final int HOLD_SECONDS = Integer.getInteger("sio.scale.holdSeconds", 15);

    /**
     * 取一个空闲端口。
     *
     * @return 端口
     * @throws Exception 套接字失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 触发 GC 并沉降后返回堆使用量（比较保留集，避免中途 GC 造成假读数）。
     *
     * @param mem 内存管理
     * @return 字节数
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
     * 直接内存池当前使用量（Netty 的读写在直接内存里，只算堆会低估每连接成本）。
     *
     * @return 字节数，无 direct 池返回 -1
     */
    private static long directUsedBytes() {
        for (java.lang.management.BufferPoolMXBean pool
                : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                return pool.getMemoryUsed();
            }
        }
        return -1L;
    }

    /**
     * 启动压测客户端进程。
     *
     * @param port 端口
     * @return 进程
     * @throws Exception 启动失败
     */
    private static Process startClient(int port) throws Exception {
        String javaHome = System.getProperty("java.home");
        File javaBin = new File(new File(javaHome, "bin"), "java");
        List<String> command = new ArrayList<>();
        command.add(javaBin.getAbsolutePath());
        command.add(System.getProperty("sio.client.heap", "-Xmx2g"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SocketIoLoadClient.class.getName());
        command.add(String.valueOf(port));
        command.add(String.valueOf(CLIENTS));
        command.add(String.valueOf(HOLD_SECONDS));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return builder.start();
    }

    /**
     * 读客户端输出直到出现指定前缀（或进程退出）。
     *
     * @param reader 输出
     * @param prefix 前缀
     * @param budgetMs 预算
     * @return 命中的行，未命中返回 null
     * @throws Exception 读取失败
     */
    private static String awaitLine(BufferedReader reader, String prefix, long budgetMs) throws Exception {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            System.out.println("[child] " + line);
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    /**
     * 规模压测：N 个真实 Socket.IO 客户端接入后，量服务端每连接堆占用。
     *
     * @throws Exception 套接字或进程失败
     */
    @Test
    @Timeout(600)
    @DisplayName("Socket.IO 规模压测（默认跳过，用 -Dsio.scale.connections 开启）")
    void scaleConnections() throws Exception {
        if (CLIENTS <= 0) {
            System.out.println("[sio-scale] SKIPPED: 未设置 -Dsio.scale.connections（容量验收不跑在常规回归里）");
            return;
        }
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setMaxConnections(CLIENTS + 1000);
        setting.setAuto(false);

        SocketIOServer server = new SocketIOServer(setting);
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        Process client = null;
        try {
            server.start();
            long heapBefore = settledHeapUsed(mem);
            long directBefore = directUsedBytes();

            client = startClient(port);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String connectedLine = awaitLine(reader, "[sio-client] CONNECTED", 300_000L);
            assertTrue(connectedLine != null, "客户端进程未报告连接完成（可能超时或崩溃）");

            long heapAfter = settledHeapUsed(mem);
            long directAfter = directUsedBytes();
            long directDelta = directAfter < 0 || directBefore < 0 ? 0L : directAfter - directBefore;
            Thread.sleep(2000L);
            long active = server.getMetrics().getActiveConnections();
            long perConnBytes = (heapAfter - heapBefore + directDelta) / Math.max(1, active);

            System.out.println("[sio-scale] clients=" + CLIENTS
                    + " active=" + active
                    + " rejected=" + server.getMetrics().getRejectedConnections()
                    + " heapDeltaMb=" + ((heapAfter - heapBefore) / (1024 * 1024))
                    + " directDeltaMb=" + (directDelta / (1024 * 1024))
                    + " perConnKb=" + String.format("%.2f", perConnBytes / 1024.0)
                    + " projected1mGb=" + String.format("%.2f", perConnBytes * 1_000_000.0 / (1024 * 1024 * 1024)));
            System.out.flush();

            assertEquals(CLIENTS, active,
                    "服务端活跃连接应为 " + CLIENTS + "，实际 " + active
                            + "（少于说明连接被拒或未建立）");
            // 只做量级断言：netty-socketio 的每连接成本取决于库实现，这里记录实测值，
            // 超过 64KB/连接说明配置或用法有问题（正常情况下应在几 KB ~ 几十 KB）
            assertTrue(perConnBytes < 64 * 1024,
                    "每连接堆占用 " + (perConnBytes / 1024.0) + "KB 过高（>64KB）");

            assertTrue(awaitLine(reader, "[sio-client] ALIVE", 180_000L) != null,
                    "客户端未报告保持期存活数");
        } finally {
            if (client != null && client.isAlive()) {
                client.destroyForcibly();
                client.waitFor(10, TimeUnit.SECONDS);
            }
            server.stop();
        }

        long deadline = System.currentTimeMillis() + 30_000L;
        while (server.getMetrics().getActiveConnections() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        assertEquals(0, server.getMetrics().getActiveConnections(),
                "客户端退出后服务端仍残留 " + server.getMetrics().getActiveConnections() + " 条连接");
    }
}
