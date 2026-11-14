// [P3C A20 豁免] A20/A30/A31: 测试断言与构造参数含魔法值场景，豁免。
package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.server.websocket.AsyncBroadcaster;
import com.chua.common.support.network.server.websocket.WsSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 长连接的容量相关回归：连接注册表结构、每连接成本声明、端到端接发。
 *
 * <p>百万连接的 WebSocket 服务有三处必须成立，缺一个就上不去：</p>
 * <ol>
 *   <li><b>连接注册表不能是 O(n²) 结构</b>：{@code CopyOnWriteArrayList} 每次增删都全量复制
 *       底层数组，n 条连接总代价 O(n²)，accept 吞吐会随时间崩塌。</li>
 *   <li><b>每连接成本必须由实现声明</b>：WS 是虚拟线程 + 阻塞 socket，没有每连接读缓冲；
 *       若沿用「bufferSize + 12KB」的缓冲公式，{@code autoConfig} 会把连接数上限压到
 *       远低于机器容量（实测差一个数量级）。</li>
 *   <li><b>握手 → 注册 → 读帧 → 分发</b> 全链路在大量连接下仍然正确。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.52
 */
class WebSocketConnectionScaleTest {

    /**
     * 端到端用例的连接数（可用 {@code -Dws.scale.connections} 放大）。
     */
    private static final int CONNECTIONS = Integer.getInteger("ws.scale.connections", 2000);

    /**
     * 客户端源 IP 轮转数量（{@code -Dws.scale.sourceIps}）。
     *
     * <p>Windows 单个源 IP 的动态端口只有约 5.5 万个，超过就必须换源地址。
     * 回环 127.0.0.0/8 可直接绑定，无需额外加网卡；每个源 IP 复用 5 万个端口后切下一个。</p>
     */
    private static final int SOURCE_IPS = Integer.getInteger("ws.scale.sourceIps", 1);

    /**
     * 单个源 IP 最多复用的端口数（留出余量，避免撞 TIME_WAIT/保留端口）。
     */
    private static final int PORTS_PER_SOURCE_IP = Integer.getInteger("ws.scale.portsPerIp", 45_000);

    /**
     * 广播用例的连接数（{@code -Dws.broadcast.connections}）。
     *
     * <p>与端到端用例分开配置：广播要额外读回全部连接，单独放大才好对比耗时。</p>
     */
    private static final int BROADCAST_CONNECTIONS = Integer.getInteger("ws.broadcast.connections", 2000);

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
     * 建一条真实 WebSocket 连接（完成 101 握手）。
     *
     * @param port 端口
     * @return 已握手的连接
     * @throws Exception 握手失败
     */
    private static Socket wsConnect(int port) throws Exception {
        return wsConnect(port, null);
    }

    /**
     * 建一条真实 WebSocket 连接（可指定客户端源地址）。
     *
     * @param port    服务端端口
     * @param localIp 客户端源 IP（null 表示不绑定）
     * @return 已握手的连接
     * @throws Exception 握手失败
     */
    private static Socket wsConnect(int port, String localIp) throws Exception {
        Socket socket = new Socket();
        if (localIp != null) {
            socket.bind(new InetSocketAddress(localIp, 0));
        }
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10000);
        socket.setSoTimeout(20000);
        socket.setTcpNoDelay(true);
        String key = Base64.getEncoder().encodeToString(new byte[16]);
        String request = "GET / HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String response = readHttpHeaders(socket.getInputStream());
        if (!response.startsWith("HTTP/1.1 101")) {
            socket.close();
            throw new IllegalStateException("握手失败: " + response.replace("\r", "\\r").replace("\n", "\\n"));
        }
        return socket;
    }

    /**
     * 读取 HTTP 响应头（到空行为止）。
     *
     * @param in 输入流
     * @return 响应头文本
     * @throws Exception 读取失败
     */
    private static String readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int p1 = -1;
        int p2 = -1;
        int p3 = -1;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (p1 == '\r' && p2 == '\n' && p3 == '\r' && b == '\n') {
                break;
            }
            p1 = p2;
            p2 = p3;
            p3 = b;
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    /**
     * 发送一个带掩码的文本帧（协议要求客户端帧必须掩码）。
     *
     * @param socket 连接
     * @param text   文本
     * @throws Exception 写失败
     */
    private static void sendTextFrame(Socket socket, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 8);
        buf.write(0x81);
        if (payload.length < 126) {
            buf.write(0x80 | payload.length);
        } else {
            buf.write(0x80 | 126);
            buf.write((payload.length >> 8) & 0xFF);
            buf.write(payload.length & 0xFF);
        }
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        buf.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            buf.write(payload[i] ^ mask[i % 4]);
        }
        OutputStream out = socket.getOutputStream();
        out.write(buf.toByteArray());
        out.flush();
    }

    /**
     * 读取一个服务端发来的帧，返回文本载荷。
     *
     * <p>服务端帧按 RFC6455 不带掩码，长度字段用 7/16/64 位三档编码。
     * 用例只关心文本帧，遇到控制帧直接按载荷返回，不在这里做协议处理。</p>
     *
     * @param socket 连接
     * @return 文本载荷
     * @throws Exception 读失败或连接已关闭
     */
    private static String readTextFrame(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            throw new IllegalStateException("连接已关闭，未读到帧");
        }
        long length = b1 & 0x7F;
        if (length == 126L) {
            length = ((long) in.read() << 8) | in.read();
        } else if (length == 127L) {
            length = 0L;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | in.read();
            }
        }
        byte[] payload = new byte[(int) length];
        int offset = 0;
        while (offset < payload.length) {
            int read = in.read(payload, offset, payload.length - offset);
            if (read < 0) {
                throw new IllegalStateException("帧载荷未读完，已读 " + offset + "/" + payload.length);
            }
            offset += read;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * 当前堆使用量（字节）。
     *
     * @return 字节数
     */
    private static long heapUsed() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        return mem.getHeapMemoryUsage().getUsed();
    }

    /**
     * 连接注册表必须是 O(1) 增删的并发结构，不能是 {@code CopyOnWriteArrayList}。
     *
     * <p>这是对「百万连接」最直接的代码级护栏：COW 列表的增删是 O(n) 复制，
     * 结构一换回去，容量立刻掉一个量级。注册表现在由 {@code AsyncBroadcaster}
     * 按分片持有（注册时归档，扇出时只遍历本片），所以校验落点也移到分片集合上。</p>
     *
     * @throws Exception 反射失败
     */
    @Test
    @Timeout(30)
    @DisplayName("连接注册表必须是并发 Set（不得回退成 CopyOnWriteArrayList）")
    void connectionRegistryMustBeConcurrentSet() throws Exception {
        AsyncBroadcaster<WsSink> broadcaster = new AsyncBroadcaster<>(4, 16);
        try {
            Field field = AsyncBroadcaster.class.getDeclaredField("shardSinks");
            field.setAccessible(true);
            Object value = field.get(broadcaster);
            assertTrue(value instanceof List, "shardSinks 应为分片列表，实际 "
                    + (value == null ? "null" : value.getClass().getName()));

            List<?> shards = (List<?>) value;
            assertEquals(4, shards.size(), "分片数与构造参数不符");
            for (Object shard : shards) {
                assertTrue(shard instanceof Set,
                        "分片注册表应为 Set（O(1) 增删），实际 "
                                + (shard == null ? "null" : shard.getClass().getName()));
                assertFalse(shard.getClass().getName().contains("CopyOnWrite"),
                        "分片注册表不得是 " + shard.getClass().getName()
                                + "：每次增删全量复制底层数组，n 条连接总代价 O(n²)");
            }
        } finally {
            broadcaster.close();
        }

        // 两个自研 WS 实现必须共用这一个注册表，避免有人又加一个 O(n) 的连接表
        for (String className : new String[]{
                "com.chua.common.support.network.sync.impl.WebSocketSyncServer",
                "com.chua.common.support.network.server.websocket.JdkWebSocketServer"}) {
            Field field = Class.forName(className).getDeclaredField("broadcaster");
            assertEquals(AsyncBroadcaster.class, field.getType(),
                    className + ".broadcaster 类型不符：连接注册表必须落在扇出器里");
        }
    }

    /**
     * 虚拟线程型 WS 服务端必须声明轻量的每连接成本，否则容量被严重低估。
     *
     * @throws Exception 构造失败
     */
    @Test
    @Timeout(30)
    @DisplayName("WS 服务端声明每连接成本，autoConfig 不再按缓冲公式压容量")
    void wsServerDeclaresLightPerConnectionCost() throws Exception {
        ServerSetting setting = new ServerSetting();
        setting.setPort(freePort());
        // 构造即触发 AbstractServer 构造函数里的 autoConfig()
        WebSocketSyncServer server = new WebSocketSyncServer(setting);
        try {
            assertTrue(setting.getPerConnectionCostKb() > 0,
                    "WS 服务端未声明每连接成本，autoConfig 会按读缓冲公式低估容量");
            assertEquals(setting.getPerConnectionCostKb(), setting.effectivePerConnectionKb(),
                    "声明了每连接成本后，估算必须采用它");

            long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            long bufferedPerConnKb = Math.max(setting.getBufferSize() / 1024L, 1L) + 12L;
            assertTrue(setting.effectivePerConnectionKb() < bufferedPerConnKb,
                    "WS 每连接成本应显著低于缓冲型公式：" + setting.effectivePerConnectionKb()
                            + "KB vs " + bufferedPerConnKb + "KB");

            // 按声明成本，堆能支撑的连接数应远大于按缓冲公式算出来的（除非堆极小）
            long byDeclared = (long) (heapMb * 0.6) * 1024L / setting.effectivePerConnectionKb();
            long byBuffered = (long) (heapMb * 0.6) * 1024L / bufferedPerConnKb;
            assertTrue(byDeclared > byBuffered,
                    "声明成本后容量应更大：declared=" + byDeclared + " buffered=" + byBuffered);
        } finally {
            server.stop();
        }
    }

    /**
     * 端到端：大量真实 WebSocket 连接握手后，帧能被正确分发；并实测每连接堆占用。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(300)
    @DisplayName("大量 WS 连接握手后可收发，且每连接堆占用足够低")
    void manyWebSocketConnectionsHandshakeAndDispatch() throws Exception {
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setMaxConnections(CONNECTIONS + 1000);
        setting.setAuto(false);

        WebSocketSyncServer server = new WebSocketSyncServer(setting);
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger messaged = new AtomicInteger();
        server.addListener(new SyncServerListener() {
            @Override
            public void onClientConnected(String clientId, java.util.Map<String, Object> metadata) {
                connected.incrementAndGet();
            }

            @Override
            public void onMessage(String clientId, String topic, Object message) {
                messaged.incrementAndGet();
            }
        });

        List<Socket> sockets = new ArrayList<>(CONNECTIONS);
        try {
            server.start();
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(150L);
            }
            long heapBefore = heapUsed();

            long startNs = System.nanoTime();
            for (int i = 0; i < CONNECTIONS; i++) {
                String localIp = SOURCE_IPS > 1
                        ? "127.0.0." + (2 + (i / PORTS_PER_SOURCE_IP) % SOURCE_IPS) : null;
                sockets.add(wsConnect(port, localIp));
                if ((i + 1) % 10_000 == 0) {
                    System.out.println("[ws-scale] progress=" + (i + 1)
                            + " elapsedMs=" + ((System.nanoTime() - startNs) / 1_000_000L));
                    System.out.flush();
                }
            }
            long connectMs = (System.nanoTime() - startNs) / 1_000_000L;

            // 每条连接发一帧，验证「握手 → 注册 → 读帧 → 分发」全链路
            for (int i = 0; i < sockets.size(); i++) {
                sendTextFrame(sockets.get(i), "topic:msg-" + i);
            }
            long deadline = System.currentTimeMillis() + 30_000L;
            while (messaged.get() < CONNECTIONS && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }

            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(150L);
            }
            long heapAfter = heapUsed();
            long perConnBytes = (heapAfter - heapBefore) / Math.max(1, sockets.size());

            // [P3C E1 豁免]
            System.out.println("[ws-scale] connections=" + sockets.size()
                    + " sourceIps=" + SOURCE_IPS
                    + " handshakeMs=" + connectMs
                    + " heapDeltaMb=" + ((heapAfter - heapBefore) / (1024 * 1024))
                    + " perConnKb=" + String.format("%.2f", perConnBytes / 1024.0));
            // [P3C E1 豁免]
            System.out.flush();

            assertEquals(CONNECTIONS, sockets.size(), "应有 " + CONNECTIONS + " 条连接建立成功");
            assertEquals(CONNECTIONS, connected.get(),
                    "onClientConnected 应被回调 " + CONNECTIONS + " 次，实际 " + connected.get());
            assertEquals(CONNECTIONS, messaged.get(),
                    "onMessage 应收到 " + CONNECTIONS + " 条，实际 " + messaged.get());

            // 每连接堆占用上限：声明值 4KB，留一倍余量避免 GC 噪声；若哪天回退成
            // 「每连接读缓冲」模型（几十 KB），这条会立刻变红
            assertTrue(perConnBytes < 8192,
                    "每连接堆占用 " + (perConnBytes / 1024.0) + "KB 过高（>8KB），"
                            + "说明 WS 连接又背上了每连接缓冲");
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

        long deadline = System.currentTimeMillis() + 20_000L;
        while (server.getMetrics().getActiveConnections() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertEquals(0, server.getMetrics().getActiveConnections(),
                "客户端全部断开后服务端仍残留 " + server.getMetrics().getActiveConnections() + " 条连接（未回收）");
    }

    /**
     * 一次广播必须扇出到全部连接，且访问次数与连接数成线性。
     *
     * <p>这是「广播」最容易退化的地方：分片注册表若在扇出时退化成
     * 「遍历全部连接再按哈希过滤」，访问次数会放大到 {@code 连接数 × 分片数}，
     * 连接规模一大就直接打满 CPU。用例直接读扇出器计数，把这一点钉死。</p>
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(1200)
    @DisplayName("广播扇出到全部连接：访问次数线性、无丢帧、全量可收")
    void broadcastFanOutIsLinear() throws Exception {
        int port = freePort();
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setMaxConnections(BROADCAST_CONNECTIONS + 1000);
        setting.setAuto(false);

        WebSocketSyncServer server = new WebSocketSyncServer(setting);
        Field broadcasterField = WebSocketSyncServer.class.getDeclaredField("broadcaster");
        broadcasterField.setAccessible(true);

        List<Socket> sockets = new ArrayList<>(BROADCAST_CONNECTIONS);
        try {
            server.start();
            // 扇出器在 doStart 里创建，必须启动后再取
            AsyncBroadcaster<?> broadcaster = (AsyncBroadcaster<?>) broadcasterField.get(server);
            assertTrue(broadcaster != null, "服务端未创建扇出器，广播链路缺失");

            long startNs = System.nanoTime();
            for (int i = 0; i < BROADCAST_CONNECTIONS; i++) {
                String localIp = SOURCE_IPS > 1
                        ? "127.0.0." + (2 + (i / PORTS_PER_SOURCE_IP) % SOURCE_IPS) : null;
                sockets.add(wsConnect(port, localIp));
                if ((i + 1) % 10_000 == 0) {
                    System.out.println("[ws-broadcast] connect progress=" + (i + 1)
                            + " elapsedMs=" + ((System.nanoTime() - startNs) / 1_000_000L));
                    System.out.flush();
                }
            }
            long connectMs = (System.nanoTime() - startNs) / 1_000_000L;

            long deadline = System.currentTimeMillis() + 60_000L;
            while (server.getMetrics().getActiveConnections() < BROADCAST_CONNECTIONS
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(BROADCAST_CONNECTIONS, server.getMetrics().getActiveConnections(),
                    "服务端注册的连接数不足：广播只会送到已注册的连接");

            // 预热：一次广播 + 全量读回，确认广播链路本身是通的
            server.publish("warm", "up");
            for (Socket socket : sockets) {
                assertEquals("warm:up", readTextFrame(socket), "预热广播帧内容不符");
            }

            long visitedBefore = broadcaster.getVisitedLinks();
            long droppedBefore = broadcaster.getDroppedFrames();
            long errorsBefore = broadcaster.getWriteErrors();

            long broadcastStart = System.nanoTime();
            server.publish("scale", "hello");
            int received = 0;
            for (Socket socket : sockets) {
                if ("scale:hello".equals(readTextFrame(socket))) {
                    received++;
                }
            }
            long broadcastMs = (System.nanoTime() - broadcastStart) / 1_000_000L;

            System.out.println("[ws-broadcast] connections=" + BROADCAST_CONNECTIONS
                    + " sourceIps=" + SOURCE_IPS
                    + " connectMs=" + connectMs
                    + " broadcastMs=" + broadcastMs
                    + " perConnUs=" + String.format("%.1f", broadcastMs * 1000.0 / BROADCAST_CONNECTIONS));
            System.out.flush();

            assertEquals(BROADCAST_CONNECTIONS, received,
                    "应收到 " + BROADCAST_CONNECTIONS + " 条广播，实际 " + received);
            assertEquals(BROADCAST_CONNECTIONS, broadcaster.getVisitedLinks() - visitedBefore,
                    "一次广播只应访问 N 条连接；实际 "
                            + (broadcaster.getVisitedLinks() - visitedBefore)
                            + "（N=" + BROADCAST_CONNECTIONS + "），"
                            + "说明扇出退化成了全量遍历再过滤");
            assertEquals(0L, broadcaster.getDroppedFrames() - droppedBefore,
                    "广播被丢弃 " + (broadcaster.getDroppedFrames() - droppedBefore) + " 帧");
            assertEquals(0L, broadcaster.getWriteErrors() - errorsBefore,
                    "广播写失败 " + (broadcaster.getWriteErrors() - errorsBefore) + " 次");
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
