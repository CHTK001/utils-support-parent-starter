package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * TcpServerFilterChainTest — 验证 TCP 帧接入 UrlMappingServerFilter 的端到端行为。
 *
 * <p>运行方式：直接执行 {@code main}，通过内嵌 TcpServer 接收 TCP 客户端请求，
 * 校验路径路由、GET/POST、404 等场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TcpServerFilterChainTest {

    /** 服务器实例 */
    private static JdkTcpServer server;
    /** 监听端口，0 = 由系统分配 */
    private static int port;

    public static void main(String[] args) throws Exception {
        System.out.println("[TcpServerFilterChainTest] starting...");

        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(0);
        setting.setHost("127.0.0.1");
        server = new JdkTcpServer(setting);

        // 注册 URL 路由（与 HttpServer 相同 API）
        server.registerMapping("/hello", (ServerHandler) (req, res) ->
                res.end("Hello from TCP URL mapping!"));
        server.registerMapping("/echo", (req, res) -> res.end(req.getBodyString()));
        server.registerMapping("/status", (ServerHandler) (req, res) ->
                res.setStatus(201).setBody("created"));

        server.start();
        port = server.getPort();
        System.out.println("[TcpServerFilterChainTest] server started on port " + port);

        boolean allPassed = true;
        try {
            allPassed &= testGetHello();
            allPassed &= testGetStatus();
            allPassed &= testPostEcho();
            allPassed &= test404();
        } finally {
            server.stop();
            System.out.println("[TcpServerFilterChainTest] server stopped");
        }

        System.out.println(allPassed ? "[TcpServerFilterChainTest] ALL PASSED"
                : "[TcpServerFilterChainTest] SOME TESTS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /** GET /hello → 200 "Hello from TCP URL mapping!" */
    private static boolean testGetHello() throws Exception {
        String request = buildHttpFrame("GET", "/hello", "", null);
        String response = sendAndReceive(request);
        boolean ok = response.contains("HTTP/1.1 200") && response.contains("Hello from TCP URL mapping!");
        System.out.println("[testGetHello] " + (ok ? "PASS" : "FAIL")
                + " | response: " + trimToFirstLine(response));
        return ok;
    }

    /** GET /status → 201 "created" */
    private static boolean testGetStatus() throws Exception {
        String request = buildHttpFrame("GET", "/status", "", null);
        String response = sendAndReceive(request);
        boolean ok = response.contains("HTTP/1.1 201") && response.contains("created");
        System.out.println("[testGetStatus] " + (ok ? "PASS" : "FAIL")
                + " | response: " + trimToFirstLine(response));
        return ok;
    }

    /** POST /echo → 回显请求体 */
    private static boolean testPostEcho() throws Exception {
        String body = "hello tcp";
        String request = buildHttpFrame("POST", "/echo", body, "text/plain");
        String response = sendAndReceive(request);
        boolean ok = response.contains("HTTP/1.1 200") && response.contains("hello tcp");
        System.out.println("[testPostEcho] " + (ok ? "PASS" : "FAIL")
                + " | response: " + trimToFirstLine(response));
        return ok;
    }

    /** GET /unknown → 404 */
    private static boolean test404() throws Exception {
        String request = buildHttpFrame("GET", "/unknown", "", null);
        String response = sendAndReceive(request);
        boolean ok = response.contains("HTTP/1.1 404");
        System.out.println("[test404] " + (ok ? "PASS" : "FAIL")
                + " | response: " + trimToFirstLine(response));
        return ok;
    }

    /** 将 HTTP 请求包装为 TCP 长度帧并发送，返回响应帧体（去掉长度头后的原始字节）。 */
    private static String sendAndReceive(String httpReq) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            byte[] httpBytes = httpReq.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // 写出：4 字节长度头 + body
            ByteBuffer outBuf = ByteBuffer.allocate(4 + httpBytes.length);
            outBuf.putInt(httpBytes.length);
            outBuf.put(httpBytes);
            outBuf.flip();
            OutputStream os = socket.getOutputStream();
            os.write(outBuf.array(), outBuf.position(), outBuf.remaining());
            os.flush();

            // 读取：4 字节长度头 + body
            byte[] lenBytes = new byte[4];
            readFully(socket.getInputStream(), lenBytes);
            int bodyLen = ((lenBytes[0] & 0xff) << 24)
                    | ((lenBytes[1] & 0xff) << 16)
                    | ((lenBytes[2] & 0xff) << 8)
                    | (lenBytes[3] & 0xff);
            byte[] respBytes = new byte[bodyLen];
            readFully(socket.getInputStream(), respBytes);
            return new String(respBytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 构造完整 HTTP 请求报文。 */
    private static String buildHttpFrame(String method, String path, String body, String contentType) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: localhost\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
            sb.append("Content-Length: ").append(body.length()).append("\r\n");
        }
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        if (body != null && !body.isEmpty()) {
            sb.append(body);
        }
        return sb.toString();
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r == -1) break;
            total += r;
        }
    }

    private static String trimToFirstLine(String s) {
        int idx = s.indexOf('\n');
        return idx >= 0 ? s.substring(0, idx) : s.trim().substring(0, Math.min(120, s.length()));
    }
}
