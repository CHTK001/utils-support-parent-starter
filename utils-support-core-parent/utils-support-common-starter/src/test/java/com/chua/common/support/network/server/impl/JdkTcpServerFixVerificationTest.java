// [P3C A20 豁免] A20/A30/A31: 测试断言与构造参数含魔法值场景，豁免。
package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkTcpServer} 三处并发/异常路径缺陷的回归验证。
 *
 * <p>这三个缺陷都在低频或竞态路径上，常规端到端用例（含 {@code TcpServerNioIntegrationTest}）
 * 全绿也照不出来，所以单独构造确定性场景：</p>
 * <ol>
 *   <li><b>写失败必须归还连接预算</b>：旧写法在 {@code writeFrame} 的 catch 里只
 *       {@code sc.close()}，预算不回收，长跑后 {@code tryAcquireConnection()} 会把
 *       正常新连接全部拒掉。</li>
 *   <li><b>{@code setHandler(null)} 不得留下必崩路径</b>：旧守卫条件把
 *       {@code frameHandler == null} 和紧跟其后的解引用写在同一个分支里。</li>
 *   <li><b>背压下写兴趣不得被并发覆盖丢失</b>：worker 置 {@code OP_WRITE} 与事件循环
 *       清 {@code OP_WRITE} 的读-改-写交错会丢掉写兴趣，响应就此永久挂起。</li>
 * </ol>
 *
 * <p>全部走真实套接字，不用 mock。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class JdkTcpServerFixVerificationTest {

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
     * 构造 TCP 服务器配置。
     *
     * <p>{@code auto=false} 是必须的：{@code autoConfig()} 会按堆内存把
     * {@code maxConnections} 覆盖成大值，显式限额只有关掉自动配置才守得住。</p>
     *
     * @param port 端口
     * @return 配置
     */
    private static ServerSetting setting(int port) {
        return ServerSetting.builder()
                .protocol("tcp")
                .host("127.0.0.1")
                .port(port)
                .auto(false)
                .maxConnections(4)
                .build();
    }

    /**
     * 缺陷 2：写失败必须归还连接预算。
     *
     * @throws Exception 反射或套接字操作失败
     */
    @Test
    @Timeout(60)
    @DisplayName("写失败必须归还连接预算")
    void writeFailureReleasesConnectionBudget() throws Exception {
        int port = freePort();
        JdkTcpServer server = new JdkTcpServer(setting(port));

        // 一对真实的已连接通道，随后把服务端侧关掉，让 write 必然抛 ClosedChannelException
        ServerSocketChannel listener = ServerSocketChannel.open();
        listener.bind(new InetSocketAddress("127.0.0.1", port));
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel channel = listener.accept();
        channel.configureBlocking(false);

        try {
            // 占住一个连接预算
            Method acquire = server.getClass().getSuperclass()
                    .getDeclaredMethod("tryAcquireConnection");
            acquire.setAccessible(true);
            assertEquals(true, acquire.invoke(server), "应成功占住连接预算");
            assertEquals(1, server.getMetrics().getActiveConnections(), "占预算后活跃数应为 1");

            // 构造连接级上下文（私有静态内部类），通道不注册到任何 Selector，
            // 使 keyFor 返回 null —— 这正是旧写法泄漏预算的形态
            Class<?> attClass = Class.forName(
                    "com.chua.common.support.network.server.impl.JdkTcpServer$Attachment");
            Constructor<?> attCtor = attClass.getDeclaredConstructor(Selector.class);
            attCtor.setAccessible(true);

            try (Selector selector = Selector.open()) {
                Object att = attCtor.newInstance(selector);
                channel.close();

                Method writeFrame = JdkTcpServer.class.getDeclaredMethod(
                        "writeFrame", SocketChannel.class, attClass, byte[].class);
                writeFrame.setAccessible(true);
                // 不抛异常：内部 catch 住并走 closeConnection
                writeFrame.invoke(server, channel, att, new byte[]{1, 2, 3});

                assertEquals(0, server.getMetrics().getActiveConnections(),
                        "写失败必须归还连接预算，否则长跑后新连接会被全部拒掉");
            }
        } finally {
            client.close();
            channel.close();
            listener.close();
        }
    }

    /**
     * 缺陷 1：运行时 {@code setHandler(null)} 后，在途连接应被干净关闭而不是挂起。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(60)
    @DisplayName("setHandler(null) 后在途连接被干净关闭且不挂起")
    void nullHandlerClosesConnectionWithoutHang() throws Exception {
        int port = freePort();
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp").host("127.0.0.1").port(port).auto(false).build();
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                return new byte[0];
            }
        });
        ServerHandler handler = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) {
                response.setStatus(200).setContentType("text/plain").setBody("ok");
                response.end();
            }
        };
        server.registerMapping("/echo", HttpMethod.GET, handler);

        byte[] frame = "GET /echo HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + frame.length];
        ByteBuffer.wrap(payload).putInt(frame.length).put(frame);

        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(8000);
                OutputStream out = socket.getOutputStream();
                DataInputStream in = new DataInputStream(socket.getInputStream());

                // 先确认链路正常
                out.write(payload);
                out.flush();
                int len = in.readInt();
                byte[] resp = new byte[len];
                in.readFully(resp);
                assertTrue(new String(resp, StandardCharsets.UTF_8).startsWith("HTTP/1.1 200"),
                        "首帧应正常响应");

                // 运行时摘掉帧处理器，再发一帧：应读到 EOF，而不是挂到超时
                server.setHandler(null);
                out.write(payload);
                out.flush();
                int closedLen = in.read(new byte[4]);
                assertEquals(-1, closedLen, "摘掉 handler 后连接应被关闭（读到 EOF）");
            }
            assertTrue(server.isRunning(), "服务端本身不应因此宕掉");
        } finally {
            server.stop();
        }
    }

    /**
     * 缺陷 3：慢客户端制造背压时，写兴趣不能被并发覆盖掉。
     *
     * <p>每轮：发一帧 → 服务端写大块响应，内核缓冲写满后剩余额挂到 {@code pendingWrite}
     * 并注册 {@code OP_WRITE} → 客户端故意晚一点再读。若置位与清位发生读-改-写交错，
     * 写兴趣会丢，响应永久挂起，这里就会读到超时。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(180)
    @DisplayName("慢客户端背压下大响应仍能完整送达")
    void slowClientStillGetsCompleteLargeResponse() throws Exception {
        int port = freePort();
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp").host("127.0.0.1").port(port).auto(false).build();
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                return new byte[0];
            }
        });
        // 1MB 响应体，远超内核发送缓冲，必然走「一次写不完」的续写路径
        char[] big = new char[1024 * 1024];
        java.util.Arrays.fill(big, 'x');
        ServerHandler handler = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) {
                response.setStatus(200).setContentType("text/plain").setBody(new String(big));
                response.end();
            }
        };
        server.registerMapping("/big", HttpMethod.GET, handler);

        byte[] frame = "GET /big HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + frame.length];
        ByteBuffer.wrap(payload).putInt(frame.length).put(frame);

        int rounds = 20;
        int failures = 0;
        try {
            server.start();
            for (int i = 0; i < rounds; i++) {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(20000);
                    OutputStream out = socket.getOutputStream();
                    out.write(payload);
                    out.flush();
                    // 让服务端先把内核缓冲写满、把剩余字节挂到 pendingWrite，再开始读
                    Thread.sleep(150);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    int len = in.readInt();
                    if (len <= 0) {
                        failures++;
                        continue;
                    }
                    byte[] resp = new byte[len];
                    in.readFully(resp);
                    String text = new String(resp, StandardCharsets.UTF_8);
                    if (!text.startsWith("HTTP/1.1 200") || text.indexOf('x') < 0) {
                        failures++;
                    }
                } catch (Exception e) {
                    failures++;
                }
            }
        } finally {
            server.stop();
        }
        assertEquals(0, failures, "背压续写下有 " + failures + "/" + rounds + " 轮未拿到完整响应");
    }

    /**
     * 缺陷 3 的加强场景：同一连接连发多帧、客户端延迟统一读取。
     *
     * <p>这是 {@code pendingWrite} 交接窗口最容易被踩中的形态：事件循环正在排空上一块
     * 大响应时，worker 恰好挂上下一块。若置空与摘写兴趣不在同一把锁里完成，
     * 后挂上的那块会被吞掉且永不续写，表现为某一条响应永远读不到。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(180)
    @DisplayName("同连接连发多帧时响应不丢")
    void pipelinedLargeFramesAreNotLost() throws Exception {
        int port = freePort();
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp").host("127.0.0.1").port(port).auto(false).build();
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                return new byte[0];
            }
        });
        char[] big = new char[256 * 1024];
        java.util.Arrays.fill(big, 'y');
        ServerHandler handler = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) {
                response.setStatus(200).setContentType("text/plain").setBody(new String(big));
                response.end();
            }
        };
        server.registerMapping("/big", HttpMethod.GET, handler);

        byte[] frame = "GET /big HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + frame.length];
        ByteBuffer.wrap(payload).putInt(frame.length).put(frame);

        int perConnection = 8;
        int connections = 8;
        int lost = 0;
        try {
            server.start();
            for (int c = 0; c < connections; c++) {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(20000);
                    OutputStream out = socket.getOutputStream();
                    // 先全部发出，不读：让服务端持续处于「写不完 → 挂 pendingWrite」状态
                    for (int i = 0; i < perConnection; i++) {
                        out.write(payload);
                    }
                    out.flush();
                    Thread.sleep(120);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    for (int i = 0; i < perConnection; i++) {
                        try {
                            int len = in.readInt();
                            if (len <= 0) {
                                lost++;
                                break;
                            }
                            byte[] resp = new byte[len];
                            in.readFully(resp);
                            String text = new String(resp, StandardCharsets.UTF_8);
                            if (!text.startsWith("HTTP/1.1 200") || text.indexOf('y') < 0) {
                                lost++;
                            }
                        } catch (Exception e) {
                            lost++;
                            break;
                        }
                    }
                }
            }
        } finally {
            server.stop();
        }
        assertEquals(0, lost, "流水线大帧下有 " + lost + " 条响应丢失或损坏");
    }

    /**
     * 缺陷 3 的决定性场景：压小内核发送缓冲 + 同连接连发多帧。
     *
     * <p>上一版用例测不到竞态，原因是回环口的发送缓冲足够大，大响应一次就写完，
     * 压根不进入「写不完 → 挂 pendingWrite → 等 OP_WRITE 续写」这条路径。
     * 这里把 {@code SO_SNDBUF} 压到 2048 字节，256KB 响应必然只能写出极小一段，
     * 续写路径被强制命中；再在同连接上连发多帧且不读，使 worker 挂新缓冲与
     * 事件循环排空旧缓冲反复重叠——正是交接竞态的窗口。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(300)
    @DisplayName("压小发送缓冲后背压续写不丢响应")
    void tinySndBufBackpressureKeepsEveryResponse() throws Exception {
        int port = freePort();
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp").host("127.0.0.1").port(port)
                .auto(false)
                .soSndBuf(2048)
                .build();
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                return new byte[0];
            }
        });
        char[] big = new char[256 * 1024];
        java.util.Arrays.fill(big, 'z');
        ServerHandler handler = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) {
                response.setStatus(200).setContentType("text/plain").setBody(new String(big));
                response.end();
            }
        };
        server.registerMapping("/big", HttpMethod.GET, handler);

        byte[] frame = "GET /big HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + frame.length];
        ByteBuffer.wrap(payload).putInt(frame.length).put(frame);

        int perConnection = 6;
        int connections = 6;
        int lost = 0;
        try {
            server.start();
            for (int c = 0; c < connections; c++) {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(30000);
                    OutputStream out = socket.getOutputStream();
                    // 全部发出且不读：服务端每条响应都只能写出 2KB 左右，剩余全靠续写链
                    for (int i = 0; i < perConnection; i++) {
                        out.write(payload);
                    }
                    out.flush();
                    Thread.sleep(200);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    for (int i = 0; i < perConnection; i++) {
                        try {
                            int len = in.readInt();
                            if (len <= 0) {
                                lost++;
                                break;
                            }
                            byte[] resp = new byte[len];
                            in.readFully(resp);
                            String text = new String(resp, StandardCharsets.UTF_8);
                            if (!text.startsWith("HTTP/1.1 200") || text.indexOf('z') < 0) {
                                lost++;
                            }
                        } catch (Exception e) {
                            lost++;
                            break;
                        }
                    }
                }
            }
        } finally {
            server.stop();
        }
        assertEquals(0, lost,
                "压小发送缓冲的背压续写下有 " + lost + "/" + (connections * perConnection) + " 条响应丢失");
    }

    /**
     * 缺陷 4（新发现）：同一连接上多个 worker 并发写会导致响应字节交错或整条丢失。
     *
     * <p>根因是 {@code doRead} 原先不做单连接串行：事件循环每读到一个完整帧就派发一个
     * worker，客户端连发多帧时这些 worker 会并发对同一个 {@link SocketChannel} 做非阻塞
     * {@code write}。而 {@code Attachment.pendingWrite} 只有一个槽位，后挂上的会覆盖前一条
     * 尚未写完的响应；更糟的是两条响应的剩余字节会交错写出，客户端读到错位的长度头之后
     * 这条连接上后续所有帧全部对不上。</p>
     *
     * <p>本用例把两个条件同时做满：handler 内 sleep 让 worker 真正重叠，
     * {@code SO_SNDBUF} 压到 2048 让每次 write 必然只写出极小一段；
     * 再让每条响应带上请求序号，这样「丢一条」和「字节错位」都能被区分出来。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(300)
    @DisplayName("同连接并发 worker 不得交错或丢失响应")
    void concurrentWorkersOnOneConnectionMustNotCorruptStream() throws Exception {
        int port = freePort();
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp").host("127.0.0.1").port(port)
                .auto(false)
                .soSndBuf(2048)
                .build();
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                return new byte[0];
            }
        });
        char[] pad = new char[256 * 1024];
        java.util.Arrays.fill(pad, 'w');
        String padding = new String(pad);
        ServerHandler handler = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) throws Exception {
                int seq = request.getBodyString().charAt(0) - '0';
                // 让同一连接上的多个 worker 真正在写阶段重叠
                Thread.sleep(60L);
                response.setStatus(200).setContentType("text/plain")
                        .setBody("seq=" + seq + "|" + padding);
                response.end();
            }
        };
        server.registerMapping("/big", HttpMethod.POST, handler);

        int frames = 8;
        byte[][] payloads = new byte[frames][];
        for (int i = 0; i < frames; i++) {
            String head = "POST /big HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n\r\n" + (char) ('0' + i);
            byte[] body = head.getBytes(StandardCharsets.US_ASCII);
            byte[] one = new byte[4 + body.length];
            ByteBuffer.wrap(one).putInt(body.length).put(body);
            payloads[i] = one;
        }

        int wrong = 0;
        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(60000);
                OutputStream out = socket.getOutputStream();
                for (byte[] p : payloads) {
                    out.write(p);
                }
                out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                for (int i = 0; i < frames; i++) {
                    int len = in.readInt();
                    if (len <= 0 || len > 4 * 1024 * 1024) {
                        wrong++;
                        break;
                    }
                    byte[] resp = new byte[len];
                    in.readFully(resp);
                    String text = new String(resp, StandardCharsets.UTF_8);
                    // 必须严格按请求顺序返回，且每条都带自己的序号：
                    // 一旦两个 worker 交错写同一个通道，这里立刻能看出来
                    if (!text.startsWith("HTTP/1.1 200") || !text.contains("seq=" + i + "|")) {
                        wrong++;
                    }
                }
            }
        } finally {
            server.stop();
        }
        assertEquals(0, wrong, "同连接并发 worker 下有 " + wrong + "/" + frames + " 条响应错位或丢失");
    }

    /**
     * 缺陷 10：响应未写尽前不得放行下一帧。
     *
     * <p>缺陷 4 的单连接串行只保证「同一时刻只有一个 worker 在跑」，但 worker 的
     * finally 原先无条件挂回 {@code OP_READ} —— 上一条响应还挂在 {@code pendingWrite}
     * 里没写完时，下一帧就被读入并派发，下一个 worker 的 {@code writeFrame} 会直接
     * 覆盖那块单槽缓冲，前一条响应的后半截永久丢失。</p>
     *
     * <p>既有背压用例看不见它，是因为响应体完全相同（同一个字符铺满），
     * 字节被换掉、被截断也仍然"看起来正常"。本用例把三个条件同时做满：</p>
     * <ul>
     *   <li>每条响应带首尾序号（{@code n=i|} 与 {@code |end=i}），截断或换体必被发现；</li>
     *   <li>{@code SO_SNDBUF} 压到 2048，256KB 响应只能写出极小一段，
     *       必然停在 {@code pendingWrite} 上；</li>
     *   <li>客户端发完所有帧后停 300ms 不读，强制"上一条没写完就要写下一条"的窗口。</li>
     * </ul>
     *
     * <p>修复前这里每轮都会出现响应错位或长度头损坏；修复后全部按序完整。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(300)
    @DisplayName("响应未写尽前放行下一帧会覆盖缓冲（缺陷 10）")
    void nextFrameDispatchedOnlyAfterPreviousResponseFlushed() throws Exception {
        int port = freePort();
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp").host("127.0.0.1").port(port)
                .auto(false)
                .soSndBuf(2048)
                .build();
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                return new byte[0];
            }
        });
        char[] pad = new char[256 * 1024];
        java.util.Arrays.fill(pad, 'p');
        String padding = new String(pad);
        ServerHandler handler = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) {
                String n = request.getParam("n");
                // 首尾都带序号：尾部被覆盖/截断时只查前缀会漏掉
                response.setStatus(200).setContentType("text/plain")
                        .setBody("n=" + n + "|" + padding + "|end=" + n);
                response.end();
            }
        };
        server.registerMapping("/big", HttpMethod.GET, handler);

        int frames = 6;
        int rounds = 2;
        int wrong = 0;
        try {
            server.start();
            for (int round = 0; round < rounds; round++) {
                // 接收缓冲必须在连接前压小：否则 Windows 自动调优会让 256KB 响应
                // 一次写完，根本进不了「写不完 → 挂 pendingWrite」这条路径，
                // 缺陷 10 的窗口也就不会出现。
                try (Socket socket = new Socket()) {
                    socket.setReceiveBufferSize(4096);
                    socket.setSoTimeout(30000);
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", port));
                    OutputStream out = socket.getOutputStream();
                    for (int i = 0; i < frames; i++) {
                        String req = "GET /big?n=" + i + " HTTP/1.1\r\nHost: x\r\n\r\n";
                        byte[] body = req.getBytes(StandardCharsets.US_ASCII);
                        byte[] one = new byte[4 + body.length];
                        ByteBuffer.wrap(one).putInt(body.length).put(body);
                        out.write(one);
                    }
                    out.flush();
                    // 不发读：让服务端每条响应都停在 pendingWrite 上；
                    // 修复前此时下一帧已被放行，下一个 worker 会覆盖这块缓冲
                    Thread.sleep(300L);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    for (int i = 0; i < frames; i++) {
                        int len = in.readInt();
                        if (len <= 0 || len > 4 * 1024 * 1024) {
                            wrong++;
                            break;
                        }
                        byte[] resp = new byte[len];
                        in.readFully(resp);
                        String text = new String(resp, StandardCharsets.UTF_8);
                        if (!text.startsWith("HTTP/1.1 200")
                                || !text.contains("n=" + i + "|")
                                || !text.contains("|end=" + i)) {
                            wrong++;
                        }
                    }
                }
            }
        } finally {
            server.stop();
        }
        assertEquals(0, wrong, "缺陷 10 下有 " + wrong + "/" + (rounds * frames) + " 条响应被覆盖或错位");
    }

    /**
     * 已取消的 {@link SelectionKey} 上做兴趣位/续写操作不得抛异常。
     *
     * <p>连接被 worker 线程并发关闭（{@code key.cancel()}）后，事件循环可能还在处理
     * 同一个键：{@code isReadable()} / {@code interestOps()} 会抛
     * {@link java.nio.channels.CancelledKeyException}。旧实现只捕获 IOException，
     * 这个运行时异常会直接打死 IO 事件循环线程——该 Selector 上所有连接的读写
     * 全部停摆（实测日志里 {@code jdk-tcp-io-0} 就是这样被杀掉的）。</p>
     *
     * <p>本用例直接构造「键已取消」的状态，再调用四个兴趣位辅助与续写方法：
     * 旧实现必抛 CancelledKeyException，修复后应全部静默返回。</p>
     *
     * @throws Exception 反射或套接字操作失败
     */
    @Test
    @Timeout(30)
    @DisplayName("已取消的 SelectionKey 上兴趣位操作不得抛异常打死事件循环")
    void cancelledKeyInterestOpsMustNotThrow() throws Exception {
        int port = freePort();
        JdkTcpServer server = new JdkTcpServer(setting(port));
        ServerSocketChannel listener = ServerSocketChannel.open();
        listener.bind(new InetSocketAddress("127.0.0.1", port));
        Socket client = new Socket();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel channel = listener.accept();
        channel.configureBlocking(false);
        try (Selector selector = Selector.open()) {
            Class<?> attClass = Class.forName(
                    "com.chua.common.support.network.server.impl.JdkTcpServer$Attachment");
            Constructor<?> attCtor = attClass.getDeclaredConstructor(Selector.class);
            attCtor.setAccessible(true);
            Object att = attCtor.newInstance(selector);
            SelectionKey key = channel.register(selector, SelectionKey.OP_READ, att);
            // 模拟 worker 线程在事件循环处理该键之前关闭了连接
            key.cancel();

            for (String name : new String[]{"addReadInterest", "clearReadInterest",
                    "addWriteInterest", "clearWriteInterest"}) {
                Method method = JdkTcpServer.class.getDeclaredMethod(name, SelectionKey.class);
                method.setAccessible(true);
                method.invoke(null, key);
            }
            Method flush = JdkTcpServer.class.getDeclaredMethod("flushPendingWrite", SelectionKey.class);
            flush.setAccessible(true);
            flush.invoke(server, key);
        } finally {
            client.close();
            channel.close();
            listener.close();
        }
    }

    /**
     * 兜底：确认服务能正常启停，避免上面的反射用例把端口/线程留在泄漏状态。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(30)
    @DisplayName("服务器可正常启停")
    void serverStartsAndStops() throws Exception {
        int port = freePort();
        JdkTcpServer server = new JdkTcpServer(setting(port));
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                return request;
            }
        });
        try {
            server.start();
            assertTrue(server.isRunning());
            assertNotNull(server.getMetrics());
        } finally {
            server.stop();
        }
        assertEquals(0, server.getMetrics().getActiveConnections(), "停止后不应残留活跃连接计数");
    }
}
