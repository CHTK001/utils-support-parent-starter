// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TcpServerRequest} / {@code TcpServerResponse} 的 NIO 解析层验收。
 *
 * <p>被替换成 NIO 增量解析器后：
 * <ul>
 *   <li>{@code TcpServerRequest} 在一次 {@code feed} 后应委托给 {@code NioServerRequest}；</li>
 *   <li>{@code TcpServerResponse} 的 {@code getReadyBytes()} 必须回传 {@code Connection: close}
 *        并包含状态行、头、空行与响应体；</li>
 *   <li>TCP 帧模式（4 字节长度头 + 体）下，{@code JdkTcpServer} 必须透传完整 HTTP 响应帧。</li>
 * </ul>
 * <p>全部使用真实套接字与真实线程，不依赖 mock。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class TcpServerNioIntegrationTest {

    /**
     * 全局使用的服务器，压测结束统一销毁
     */
    private static ServerSocket serverSocket;

    /**
     * 给 {@link JdkTcpServer} 使用的随机空闲端口，避免竞争
     */
    private static int port;

    /**
     * 测试开始: 占一个空闲端口
     */
    @BeforeAll
    static void beforeAll() throws Exception {
        serverSocket = new ServerSocket(0);
        port = serverSocket.getLocalPort();
        serverSocket.close();
    }

    @AfterAll
    static void afterAll() throws Exception {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    /**
     * 反射读取 {@code TcpServerRequest.nioPath} 字段。
     *
     * @param request 请求
     * @return true 表示 NIO 解析路径
     */
    private static boolean nioPath(TcpServerRequest request) throws Exception {
        Field f = TcpServerRequest.class.getDeclaredField("nioPath");
        f.setAccessible(true);
        return (boolean) f.get(request);
    }

    /**
     * 写一条 TCP 长度帧（4 字节气管长度头 + 体）
     *
     * @param out 输出流
     * @param body 帧体
     */
    private static void writeFrame(OutputStream out, byte[] body) throws IOException {
        byte[] bytes = new byte[body.length + 4];
        bytes[0] = (byte) (body.length >> 24);
        bytes[1] = (byte) (body.length >> 16);
        bytes[2] = (byte) (body.length >> 8);
        bytes[3] = (byte) body.length;
        System.arraycopy(body, 0, bytes, 4, body.length);
        out.write(bytes);
        out.flush();
    }

    /**
     * 读一条 TCP 长度帧
     *
     * @param in 输入流
     * @return 帧体字节
     */
    private static byte[] readFrame(InputStream in) throws IOException {
        byte[] head = new byte[4];
        if (in.read(head) != 4) {
            throw new IOException("read failed");
        }
        int len = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16)
                | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        byte[] body = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(body, off, len - off);
            if (r < 0) {
                throw new IOException("stream closed before frame end: " + len);
            }
            off += r;
        }
        return body;
    }

    /**
     * NIO 单帧解析：完整的 HTTP 请求帧应当被 {@code NioServerRequest} 一次 feed 出来。
     */
    @Test
    @DisplayName("单帧完整 HTTP 请求 -> NIO 解析")
    void singleFrameNioRequest() throws Exception {
        String frame = "GET /hello?k=v HTTP/1.1\r\nHost: foo\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello";
        TcpServerRequest request = new TcpServerRequest(frame.getBytes(StandardCharsets.UTF_8),
                new InetSocketAddress("127.0.0.1", 9999), StandardCharsets.UTF_8, 1024 * 1024);
        assertTrue(nioPath(request), "HTTP 单帧应由 NioServerRequest 解析");

        assertEquals(HttpMethod.GET, request.getMethod());
        assertEquals("/hello", request.getPath());
        assertEquals("v", request.getParam("k"));
        assertEquals("foo", request.getHeader("Host"));
        assertEquals("text/plain", request.getHeader("Content-Type"));
        assertEquals("hello", request.getBodyString());
        assertEquals("5", request.getHeader("content-length"));
    }

    /**
     * 混合大小写 Header 在 NIO 路径下仍能按原样读取。
     */
    @Test
    @DisplayName("Header 大小写与获取方式")
    void headerCaseSensitivity() throws Exception {
        TcpServerRequest request = new TcpServerRequest("GET / HTTP/1.1\r\nX-Request-Id: A1B2\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8), null, StandardCharsets.UTF_8, 1024 * 1024);
        assertTrue(nioPath(request));
        assertEquals("A1B2", request.getHeader("x-request-id"));
        assertEquals("A1B2", request.getHeader("X-Request-Id"));
        // NIO 路径下 header key 全部小写
        assertEquals("A1B2", request.getHeaders().get("x-request-id"));
    }

    /**
     * 非 HTTP 帧（缺少空行）：退回旧版逐行解析，降级为 {@code POST /}。
     */
    @Test
    @DisplayName("非 HTTP 文本帧 -> 旧版 POST / 降级")
    void nonHttpFrame() throws Exception {
        String frame = "hello world";
        TcpServerRequest request = new TcpServerRequest(frame.getBytes(StandardCharsets.UTF_8),
                null, StandardCharsets.UTF_8, 1024 * 1024);
        assertFalse(nioPath(request), "非 HTTP 帧应退回旧版解析");
        assertEquals(HttpMethod.POST, request.getMethod());
        assertEquals("/", request.getPath());
        assertEquals(frame, request.getBodyString());
    }

    /**
     * 未完成的帧（半包）：退回旧版逐行解析。
     * <p>因缺少 \r\n\r\n 界定符，旧版 parse 将整个帧视为非 HTTP 帧：
     * 降级为 POST /、空 Header、整帧作为 body。</p>
     */
    @Test
    @DisplayName("半包帧 -> 旧版逐行解析")
    void halfPacketFrame() throws Exception {
        String frame = "GET / HTTP/1.1\r\nHost: foo\r\n";
        TcpServerRequest request = new TcpServerRequest(frame.getBytes(StandardCharsets.UTF_8),
                null, StandardCharsets.UTF_8, 1024 * 1024);
        assertFalse(nioPath(request), "不完整帧应退回旧版解析");
        assertEquals(HttpMethod.POST, request.getMethod());
        assertEquals("/", request.getPath());
        assertEquals(frame, request.getBodyString());
        assertTrue(request.getHeaders().toMap().isEmpty(), "半包帧无完整 Header 段: " + request.getHeaders());
    }

    /**
     * 请求体超长时退回旧版逐行解析。
     */
    @Test
    @DisplayName("超大请求体 -> 旧版逐行解析")
    void oversizedRequest() throws Exception {
        StringBuilder sb = new StringBuilder("POST / HTTP/1.1\r\nContent-Length: 2000000\r\n\r\n");
        for (int i = 0; i < 1000000; i++) {
            sb.append('x');
        }
        // 只取前 2MB 的体模拟超长请求
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[Math.min(body.length, 1024 * 1024)];
        System.arraycopy(body, 0, frame, 0, frame.length);
        TcpServerRequest request = new TcpServerRequest(frame, null, StandardCharsets.UTF_8, 1024 * 1024);
        assertFalse(nioPath(request), "超长请求应退回旧版解析");
        assertEquals(HttpMethod.POST, request.getMethod());
    }

    /**
     * 非法请求行：退回旧版逐行解析（不抛异常）。
     */
    @Test
    @DisplayName("非法请求行 -> 旧版逐行解析")
    void illegalRequestLine() throws Exception {
        TcpServerRequest request = new TcpServerRequest("GARBAGE\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                null, StandardCharsets.UTF_8, 1024 * 1024);
        assertEquals(HttpMethod.POST, request.getMethod());
        assertEquals("/", request.getPath());
    }

    /**
     * 不带 Content-Length 且无体的纯头请求：NIO 应一次完成。
     */
    @Test
    @DisplayName("无体请求 -> NIO 解析")
    void headerOnlyNioRequest() throws Exception {
        TcpServerRequest request = new TcpServerRequest("GET / HTTP/1.1\r\nHost: foo\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8), null, StandardCharsets.UTF_8, 1024 * 1024);
        assertTrue(nioPath(request));
        assertEquals(HttpMethod.GET, request.getMethod());
        assertEquals(0, request.getBody().length);
    }

    /**
     * NIO 路径的 {@code getHeaders()} 所有 key 必须小写。
     */
    @Test
    @DisplayName("getHeaders 返回全小写 key")
    void getHeadersKeysAreLowercase() throws Exception {
        TcpServerRequest request = new TcpServerRequest("GET / HTTP/1.1\r\nContent-Type: text/plain\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8), null, StandardCharsets.UTF_8, 1024 * 1024);
        assertTrue(nioPath(request));
        for (String key : request.getHeaders().toMap().keySet()) {
            assertEquals(key, key.toLowerCase(), "header key 应全小写: " + key);
        }
        assertEquals("text/plain", request.getHeaders().get("content-type"));
    }

    /**
     * 无体 HTTPS 响应：状态行 + Content-Length: 0 + Connection: close。
     */
    @Test
    @DisplayName("空体响应完整报文")
    void emptyBodyResponse() {
        TcpServerResponse response = new TcpServerResponse();
        response.setStatus(200).setContentType("application/json").setBody(new byte[0]);
        response.end();
        byte[] bytes = response.getReadyBytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), "状态行: " + text);
        assertTrue(text.contains("Connection: close\r\n"), "TCP 帧必须返回 close");
        assertTrue(text.contains("Content-Length: 0\r\n"), "Content-Length 缺失: " + text);
        assertTrue(!text.contains("keep-alive"), "不应出现 keep-alive: " + text);
    }

    /**
     * 有体 HTTP 响应：header + body 必须按顺序进入 ready，报文完整。
     */
    @Test
    @DisplayName("有体响应 header 与 body 按序放入 ready")
    void bodyResponse() {
        TcpServerResponse response = new TcpServerResponse();
        response.setStatus(201).setContentType("text/plain").setBody("tcp body");
        response.end();
        byte[] bytes = response.getReadyBytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("HTTP/1.1 201 Created\r\n"));
        assertTrue(text.contains("Content-Length: 8\r\n"));
        assertTrue(text.contains("Connection: close\r\n"));
        // body 必须紧跟在空行之后
        int bodyIdx = text.indexOf("tcp body");
        assertTrue(bodyIdx > 0, "body 未出现在报文中: " + text);
        assertTrue(text.substring(bodyIdx + "tcp body".length()).isEmpty(), "body 后有多余内容: " + text);
    }

    /**
     * 纯文本：回退旧版 parse 仍能写出完整帧（确保不抛异常）
     */
    @Test
    @DisplayName("旧版响应仍可拼接帧体")
    void rawWrite() {
        TcpServerResponse response = new TcpServerResponse();
        response.writeRaw("raw frame content".getBytes(StandardCharsets.UTF_8));
        response.end();
        assertFalse(response.getReadyBytes().length == 0);
        assertTrue(new String(response.getReadyBytes(), StandardCharsets.UTF_8).contains("raw frame content"));
    }

    /**
     * 注册路由后，JdkTcpServer 通过 TCP 长度帧收发 HTTP 报文：
     * 客户端发送 4 字节长度头 + HTTP 请求帧，服务端回写 4 字节长度头 + HTTP 响应帧。
     */
    @Test
    @DisplayName("JdkTcpServer TCP 帧模式端到端")
    void jdkTcpServerFrameMode() throws Exception {
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp")
                .host("127.0.0.1")
                .port(port)
                .build();
        JdkTcpServer server = new JdkTcpServer(setting);
        AtomicReference<HttpMethod> methodRef = new AtomicReference<>();
        AtomicReference<String> pathRef = new AtomicReference<>();
        AtomicReference<String> bodyRef = new AtomicReference<>();
        ServerHandler handler = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) throws Exception {
                methodRef.set(request.getMethod());
                pathRef.set(request.getPath());
                bodyRef.set(request.getBodyString());
                response.setStatus(200).setContentType("text/plain")
                        .setBody("matched: " + request.getPath());
                response.end();
            }
        };
        // setHandler 启用帧式（NIO 拼帧）协议，registerMapping 触发 URL 路由模式
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) throws Exception {
                return new byte[0];
            }
        });
        server.registerMapping("/echo", HttpMethod.GET, handler);
        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                InputStream in = socket.getInputStream();
                // 发送 HTTP 请求帧
                byte[] reqBody = "GET /echo?k=1 HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                writeFrame(out, reqBody);
                // 读回长度帧
                byte[] resp = readFrame(in);
                assertNotNull(resp);
                String text = new String(resp, StandardCharsets.UTF_8);
                assertTrue(text.startsWith("HTTP/1.1 200 OK"), "响应状态行: " + text);
                assertTrue(text.contains("Connection: close"), "TCP 帧模式必须 return close: " + text);
                assertTrue(text.contains("Content-Length: 14\r\n"), "Content-Length: " + text);
                assertTrue(text.contains("matched: /echo"), "路由未生效: " + text);
                assertFalse(text.contains("keep-alive"), "不应出现 keep-alive: " + text);
            }
            assertEquals(HttpMethod.GET, methodRef.get());
            assertEquals("/echo", pathRef.get());
            assertEquals("", bodyRef.get());
        } finally {
            server.stop();
        }
    }

    /**
     * 同一个路由下，非 HTTP 帧进入旧版逐行解析：整个帧作为 body 回写。
     */
    @Test
    @DisplayName("JdkTcpServer 非 HTTP 帧回路")
    void jdkTcpServerRawFrame() throws Exception {
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp")
                .host("127.0.0.1")
                .port(port)
                .build();
        JdkTcpServer server = new JdkTcpServer(setting);
        AtomicReference<String> bodyRef = new AtomicReference<>();
        ServerHandler handler = new ServerHandler() {
            @Override
            public void handle(ServerRequest request, ServerResponse response) throws Exception {
                bodyRef.set(request.getBodyString());
                response.setStatus(200).setContentType("text/plain")
                        .setBody("matched: " + request.getPath());
                response.end();
            }
        };
        server.setHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) throws Exception {
                return new byte[0];
            }
        });
        // 非 HTTP 帧降级为 `POST /`，注册该路径让它走进 handler
        server.registerMapping("/", HttpMethod.POST, handler);
        byte[] body = "raw payload".getBytes(StandardCharsets.UTF_8);
        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5000);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                InputStream in = socket.getInputStream();
                writeFrame(out, body);
                byte[] resp = readFrame(in);
                String text = new String(resp, StandardCharsets.UTF_8);
                assertTrue(text.startsWith("HTTP/1.1 200 OK"), "raw resp: " + text);
                assertTrue(text.contains("Content-Length: 10\r\n"), "Content-Length: " + text);
                assertTrue(text.contains("Connection: close"), "TCP 帧模式必须带 close: " + text);
                assertTrue(text.contains("matched: /"), "路由未生效: " + text);
                // 非 HTTP 帧：整个帧作为 body 传给路由，由路由 deciding 回写
                assertEquals("raw payload", bodyRef.get());
            }
        } finally {
            server.stop();
        }
    }

    /**
     * 比较续期：设置一个可复用的占位方法（供后续扩展参考）。
     */
    private static String version() {
        return "test-version";
    }
}
