// [P3C A20 豁免] A20/A30/A31: 测试构造的报文与断言消息含魔法值场景，豁免。
package com.chua.common.support.network.server;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 实现的背压写出验收：流水线 + 压小发送缓冲 + 响应带序号。
 *
 * <p>为什么单独验这一项：TCP 帧路径已经用同样的手法压出过「写不完 → 挂待写队列 → 续写」
 * 的真实路径，并因此发现了几个缺陷；而 HTTP 两个实现（{@link NioHttpServer}、
 * {@link AioHttpServer}）的待写队列此前只有单请求往返的用例覆盖，从未在同一连接上
 * 制造过真正的背压。</p>
 *
 * <p>做法：</p>
 * <ul>
 *   <li>把 {@code SO_SNDBUF} 压到 2048，保证 256KB 响应必然只能写出极小一段；</li>
 *   <li>同一连接上连发多条请求且不读，让待写队列里同时压着多条响应；</li>
 *   <li>每响应体带自己的请求序号，既能发现「丢一条」也能发现「顺序错乱」。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
class HttpBackpressureWriteTest {

    /**
     * 大响应体填充字符数
     */
    private static final int PAD = 256 * 1024;

    /**
     * 同连接流水线请求条数
     */
    private static final int PIPELINED = 6;

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
     * 构造带小发送缓冲的 HTTP 配置。
     *
     * @param port 端口
     * @return 配置
     */
    private static ServerSetting setting(int port) {
        return ServerSetting.builder()
                .protocol("http")
                .host("127.0.0.1")
                .port(port)
                .auto(false)
                .soSndBuf(2048)
                .build();
    }

    /**
     * 大响应处理器：响应体以 {@code n=<序号>|} 开头，后面是固定填充。
     *
     * @return 处理器
     */
    private static ServerHandler bigHandler() {
        char[] pad = new char[PAD];
        java.util.Arrays.fill(pad, 'p');
        String padding = new String(pad);
        return new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) {
                String n = request.getParam("n");
                response.setStatus(200).setContentType("text/plain")
                        .setBody("n=" + n + "|" + padding);
                response.end();
            }
        };
    }

    /**
     * 从流里读一个完整 HTTP 响应，返回响应体字符串。
     *
     * @param in 输入流
     * @return 响应体
     * @throws Exception 读失败或报文不完整
     */
    private static String readHttpResponse(InputStream in) throws Exception {
        StringBuilder head = new StringBuilder(256);
        int prev3 = -1;
        int prev2 = -1;
        int prev1 = -1;
        int c;
        // 读到 \r\n\r\n 为止
        while ((c = in.read()) != -1) {
            head.append((char) c);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && c == '\n') {
                break;
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = c;
            if (head.length() > 64 * 1024) {
                throw new IllegalStateException("响应头过长，疑似报文错位");
            }
        }
        if (c == -1) {
            throw new IllegalStateException("响应头未读完就 EOF，疑似报文被截断");
        }
        String headers = head.toString();
        if (!headers.startsWith("HTTP/1.1 200")) {
            throw new IllegalStateException("状态行异常: " + headers.substring(0, Math.min(80, headers.length())));
        }
        int contentLength = -1;
        for (String line : headers.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && "content-length".equalsIgnoreCase(line.substring(0, idx).trim())) {
                contentLength = Integer.parseInt(line.substring(idx + 1).trim());
            }
        }
        if (contentLength < 0) {
            throw new IllegalStateException("缺少 Content-Length，无法确定响应边界");
        }
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int r = in.read(body, off, contentLength - off);
            if (r < 0) {
                throw new IllegalStateException("响应体未读满就 EOF: " + off + "/" + contentLength);
            }
            off += r;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * 起服务、单连接流水线发包（先不读），再逐条读回并校验顺序与完整性。
     *
     * @param server 已构造但未启动的服务
     * @param port   端口
     * @return 收到的响应体列表
     * @throws Exception 套接字操作失败
     */
    private static List<String> runPipeline(AbstractServer server, int port) throws Exception {
        server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(60000);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            for (int i = 0; i < PIPELINED; i++) {
                String req = "GET /big?n=" + i + " HTTP/1.1\r\nHost: x\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
            }
            out.flush();
            // 让服务端把内核发送缓冲写满、剩余响应压进待写队列
            Thread.sleep(250L);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            List<String> bodies = new ArrayList<>(PIPELINED);
            for (int i = 0; i < PIPELINED; i++) {
                bodies.add(readHttpResponse(in));
            }
            return bodies;
        } finally {
            server.stop();
        }
    }

    /**
     * 在已打开的连接上完成一次「发一条、读一条」往返。
     *
     * @param out   输出流
     * @param in    输入流
     * @param index 请求序号
     * @return 响应体
     * @throws Exception 读写失败或报文异常
     */
    private static String roundTrip(OutputStream out, InputStream in, int index) throws Exception {
        String req = "GET /big?n=" + index + " HTTP/1.1\r\nHost: x\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return readHttpResponse(in);
    }

    /**
     * 顺序 keep-alive 往返（每次只发一条、读完再发下一条）。
     *
     * <p>这是流水线重放逻辑最可能误伤的场景：如果重放循环在"没有残留"时也错误地
     * 再解一次，就会出现重复处理、响应错位或直接挂住。本用例用真实套接字连做多次往返。</p>
     *
     * @param server 已构造未启动的服务
     * @param port   端口
     * @throws Exception 套接字操作失败
     */
    private static void runSequentialKeepAlive(AbstractServer server, int port) throws Exception {
        server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30000);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            int rounds = 5;
            for (int i = 0; i < rounds; i++) {
                String body = roundTrip(out, in, i);
                assertTrue(body.startsWith("n=" + i + "|"),
                        "第 " + i + " 次顺序往返的响应序号不符");
            }
        } finally {
            server.stop();
        }
    }

    /**
     * NIO HTTP 的顺序 keep-alive 往返。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(120)
    @DisplayName("NioHttpServer 顺序 keep-alive 多次往返正常")
    void nioHttpServerSequentialKeepAlive() throws Exception {
        int port = freePort();
        NioHttpServer server = new NioHttpServer(setting(port));
        server.registerMapping("/big", HttpMethod.GET, bigHandler());
        runSequentialKeepAlive(server, port);
    }

    /**
     * AIO HTTP 的顺序 keep-alive 往返（流水线重放改动的主要回归面）。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(120)
    @DisplayName("AioHttpServer 顺序 keep-alive 多次往返正常")
    void aioHttpServerSequentialKeepAlive() throws Exception {
        int port = freePort();
        AioHttpServer server = new AioHttpServer(setting(port));
        server.registerMapping("/big", HttpMethod.GET, bigHandler());
        runSequentialKeepAlive(server, port);
    }

    /**
     * NIO 的 TLS 流水线：走的是 {@code SslSocketChannel} + 虚拟线程阻塞路径，
     * 与 AIO 的异步 SSLEngine 完全不同，必须单独覆盖。
     *
     * @throws Exception TLS 初始化或套接字操作失败
     */
    @Test
    @Timeout(180)
    @DisplayName("NioHttpServer TLS 流水线响应不丢不乱序")
    void nioHttpServerTlsKeepsEveryPipelinedResponse() throws Exception {
        int port = freePort();
        ServerSetting tlsSetting = ServerSetting.builder()
                .protocol("http")
                .host("127.0.0.1")
                .port(port)
                .auto(false)
                .soSndBuf(2048)
                .ssl(ServerSetting.SslConfig.builder().selfSignedAuto(true).build())
                .build();
        NioHttpServer server = new NioHttpServer(tlsSetting);
        server.registerMapping("/big", HttpMethod.GET, bigHandler());

        List<String> bodies = runTlsPipeline(server, port);

        assertEquals(PIPELINED, bodies.size());
        for (int i = 0; i < PIPELINED; i++) {
            assertTrue(bodies.get(i).startsWith("n=" + i + "|"),
                    "NIO TLS 第 " + i + " 条响应序号不符");
        }
    }

    /**
     * 起服务、在一条真实 TLS 连接上流水线发包（先不读），再逐条读回。
     *
     * @param server 已构造但未启动的服务
     * @param port   端口
     * @return 响应体列表
     * @throws Exception TLS 初始化或套接字操作失败
     */
    private static List<String> runTlsPipeline(AbstractServer server, int port) throws Exception {
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        }}, new java.security.SecureRandom());

        List<String> bodies = new ArrayList<>(PIPELINED);
        server.start();
        try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) ctx.getSocketFactory()
                .createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(60000);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            for (int i = 0; i < PIPELINED; i++) {
                String req = "GET /big?n=" + i + " HTTP/1.1\r\nHost: x\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
            }
            out.flush();
            Thread.sleep(250L);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            for (int i = 0; i < PIPELINED; i++) {
                bodies.add(readHttpResponse(in));
            }
        } finally {
            server.stop();
        }
        return bodies;
    }

    /**
     * TLS 下的流水线：验证密文/明文两层残留都能被重放，不会挂死。
     *
     * <p>这条专门覆盖明文用例打不到的分支：TLS 时 readBuf 装的是密文，
     * 残留请求分别在 {@code netIn}（未解密）与 {@code tls.plain}（已解密未喂完）里。</p>
     *
     * @throws Exception TLS 初始化或套接字操作失败
     */
    @Test
    @Timeout(180)
    @DisplayName("AioHttpServer TLS 流水线响应不丢不乱序")
    void aioHttpServerTlsKeepsEveryPipelinedResponse() throws Exception {
        int port = freePort();
        ServerSetting tlsSetting = ServerSetting.builder()
                .protocol("http")
                .host("127.0.0.1")
                .port(port)
                .auto(false)
                .soSndBuf(2048)
                .ssl(ServerSetting.SslConfig.builder().selfSignedAuto(true).build())
                .build();
        AioHttpServer server = new AioHttpServer(tlsSetting);
        server.registerMapping("/big", HttpMethod.GET, bigHandler());

        List<String> bodies = runTlsPipeline(server, port);

        assertEquals(PIPELINED, bodies.size());
        for (int i = 0; i < PIPELINED; i++) {
            assertTrue(bodies.get(i).startsWith("n=" + i + "|"),
                    "AIO TLS 第 " + i + " 条响应序号不符");
        }
    }

    /**
     * NIO HTTP 实现的背压写出。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(180)
    @DisplayName("NioHttpServer 背压下流水线响应不丢不乱序")
    void nioHttpServerKeepsEveryPipelinedResponse() throws Exception {
        int port = freePort();
        NioHttpServer server = new NioHttpServer(setting(port));
        server.registerMapping("/big", HttpMethod.GET, bigHandler());

        List<String> bodies = runPipeline(server, port);

        assertEquals(PIPELINED, bodies.size());
        for (int i = 0; i < PIPELINED; i++) {
            assertTrue(bodies.get(i).startsWith("n=" + i + "|"),
                    "第 " + i + " 条响应序号不符，实际开头: "
                            + bodies.get(i).substring(0, Math.min(20, bodies.get(i).length())));
        }
    }

    /**
     * AIO HTTP 实现的背压写出。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(180)
    @DisplayName("AioHttpServer 背压下流水线响应不丢不乱序")
    void aioHttpServerKeepsEveryPipelinedResponse() throws Exception {
        int port = freePort();
        AioHttpServer server = new AioHttpServer(setting(port));
        server.registerMapping("/big", HttpMethod.GET, bigHandler());

        List<String> bodies = runPipeline(server, port);

        assertEquals(PIPELINED, bodies.size());
        for (int i = 0; i < PIPELINED; i++) {
            assertTrue(bodies.get(i).startsWith("n=" + i + "|"),
                    "第 " + i + " 条响应序号不符，实际开头: "
                            + bodies.get(i).substring(0, Math.min(20, bodies.get(i).length())));
        }
    }
}
