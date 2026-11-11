// [P3C A20 豁免] A20/A30/A31: 测试断言与构造参数含魔法值场景，豁免。
package com.chua.socketio.support.server;

import com.chua.common.support.network.server.ServerSetting;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Socket.IO 连接预算的真实链路验证。
 *
 * <p>背景：{@code SocketIOServer} 是 netty-socketio 的薄封装，而 netty-socketio 自身
 * <b>不判连接上限</b>。修复前 {@code maxConnections} 对 Socket.IO 完全无效——连接会被
 * 一直接受到把机器资源吃光，这与「单服务支撑百万连接」的目标直接冲突（百万的目标必须
 * 有上限保护，否则一次连接风暴就打崩节点）。</p>
 *
 * <p>用真实 {@code io.socket:socket.io-client} 建连，不用 mock：只有真客户端才能
 * 触发 netty-socketio 的 connect/disconnect 回调，也就才能验证预算的准入与配对释放。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class SocketIOServerConnectionBudgetTest {

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
     * 建一个真实 Socket.IO 客户端（不重连、forceNew，保证一条客户端一条连接）。
     *
     * @param port       端口
     * @param connects   连接成功计数
     * @param disconnects 断开计数
     * @return 客户端
     */
    private static Socket newClient(int port, AtomicInteger connects, AtomicInteger disconnects) {
        IO.Options options = new IO.Options();
        options.forceNew = true;
        options.reconnection = false;
        options.timeout = 5000;
        Socket socket = IO.socket(URI.create("http://127.0.0.1:" + port), options);
        socket.on(Socket.EVENT_CONNECT, args -> connects.incrementAndGet());
        socket.on(Socket.EVENT_DISCONNECT, args -> disconnects.incrementAndGet());
        socket.connect();
        return socket;
    }

    /**
     * 等待条件成立。
     *
     * @param condition 条件
     * @param budgetMs  预算毫秒
     * @return 成立返回 true
     * @throws InterruptedException 等待被中断
     */
    private static boolean waitFor(java.util.function.BooleanSupplier condition, long budgetMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50L);
        }
        return condition.getAsBoolean();
    }

    /**
     * 统计仍处于已连接状态的客户端数。
     *
     * @param clients 客户端列表
     * @return 存活数
     */
    private static int countConnected(List<Socket> clients) {
        int alive = 0;
        for (Socket client : clients) {
            if (client.connected()) {
                alive++;
            }
        }
        return alive;
    }

    /**
     * 超过 maxConnections 的连接必须被拒绝，且断开后名额要归还。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(90)
    @DisplayName("Socket.IO 超过 maxConnections 的连接被拒绝，断开后名额归还")
    void rejectsBeyondMaxConnections() throws Exception {
        int port = freePort();
        int max = 2;
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setMaxConnections(max);
        setting.setAuto(false);

        SocketIOServer server = new SocketIOServer(setting);
        AtomicInteger connects = new AtomicInteger();
        AtomicInteger disconnects = new AtomicInteger();
        List<Socket> clients = new ArrayList<>();
        try {
            server.start();

            for (int i = 0; i < max + 1; i++) {
                clients.add(newClient(port, connects, disconnects));
            }

            // 最多只能有 max 条被准入
            assertTrue(waitFor(() -> server.getMetrics().getActiveConnections() >= max, 30_000L),
                    "应有 " + max + " 条连接被准入，实际活跃 "
                            + server.getMetrics().getActiveConnections());
            Thread.sleep(3000L);

            assertEquals(max, server.getMetrics().getActiveConnections(),
                    "maxConnections=" + max + " 时活跃连接必须恰好 " + max
                            + "，实际 " + server.getMetrics().getActiveConnections()
                            + "（预算未生效会让连接无限接纳）");
            // 超限的连接必须被服务端主动断开：存活（connected()==true）的客户端数只能等于 max。
            // 直接看客户端状态，而不只是看服务端指标——指标可能没接上，连接却已被接纳。
            assertTrue(waitFor(() -> countConnected(clients) <= max, 20_000L),
                    "存活客户端数应回落到 " + max + "，实际 " + countConnected(clients)
                            + "（超限连接未被拒绝）");
            Thread.sleep(3000L);
            int alive = countConnected(clients);
            assertEquals(max, alive,
                    "maxConnections=" + max + " 时存活客户端必须恰好 " + max + "，实际 " + alive
                            + "（预算未生效会让连接无限接纳）");

            assertTrue(server.getMetrics().getRejectedConnections() > 0,
                    "应记录到被拒绝的连接，实际 " + server.getMetrics().getRejectedConnections());

            // 主动断开客户端，名额必须归还
            for (Socket client : clients) {
                client.disconnect();
                client.close();
            }
            assertTrue(waitFor(() -> server.getMetrics().getActiveConnections() == 0, 30_000L),
                    "客户端断开后活跃连接应归零（名额未归还说明 disconnect 未配对释放），实际 "
                            + server.getMetrics().getActiveConnections());
        } finally {
            for (Socket client : clients) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // 清理失败不影响断言
                }
            }
            server.stop();
        }
    }
}
