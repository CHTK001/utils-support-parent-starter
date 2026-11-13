package com.chua.webrtc.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebRtcServer} 信令通道真实链路用例。
 *
 * <p>客户端是裸 {@link Socket}：手工发 WebSocket Upgrade 握手、按 RFC 6455 加掩码组帧，
 * 校验 101 响应 / {@code Sec-WebSocket-Accept} / token 鉴权 / 信令帧往返 / ping-pong。
 * 不依赖任何 mock，也不触碰 WebRTC 原生媒体协商（OFFER 分支需要 webrtc-java 原生库，
 * 单独在矩阵里标注为未验）。</p>
 *
 * @author CH
 */
@Timeout(30)
class WebRtcSignalingTest {

    /**
     * RFC 6455 固定 GUID。
     */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static WebRtcServer server;
    private static int port;

    @BeforeAll
    static void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setProtocol("ws");
        server = new WebRtcServer(setting);
        server.start();
        assertTrue(server.isRunning(), "信令服务应已启动");
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("握手：101 Switching Protocols 且 Sec-WebSocket-Accept 正确")
    void handshakeReturnsValidAccept() throws Exception {
        try (Socket socket = connect(null)) {
            String key = newKey();
            String resp = handshake(socket, "/webrtc", null, key);
            assertTrue(resp.startsWith("HTTP/1.1 101"), "应升级成功: " + firstLine(resp));
            String expected = accept(key);
            assertTrue(resp.contains("Sec-WebSocket-Accept: " + expected),
                    "Sec-WebSocket-Accept 必须等于 Base64(SHA1(key+GUID))，实际响应:\n" + resp);
        }
    }

    @Test
    @DisplayName("鉴权：token 不匹配回 403，匹配才升级")
    void tokenIsEnforced() throws Exception {
        server.setSignalingToken("s3cret");
        try {
            try (Socket socket = connect(null)) {
                String resp = handshake(socket, "/webrtc", "wrong", newKey());
                assertTrue(resp.startsWith("HTTP/1.1 403"), "错误 token 必须被拒: " + firstLine(resp));
            }
            try (Socket socket = connect(null)) {
                String resp = handshake(socket, "/webrtc", "s3cret", newKey());
                assertTrue(resp.startsWith("HTTP/1.1 101"), "正确 token 应升级: " + firstLine(resp));
            }
        } finally {
            server.setSignalingToken(null);
        }
    }

    @Test
    @DisplayName("信令：answer 帧得到 ok 响应帧")
    void answerGetsOkResponse() throws Exception {
        try (Socket socket = connect(null)) {
            handshake(socket, "/webrtc", null, newKey());
            writeText(socket, "{\"peerId\":\"p1\",\"type\":\"answer\",\"payload\":\"{}\"}");
            String resp = readText(socket);
            assertNotNull(resp, "应收到信令响应帧");
            assertTrue(resp.contains("\"ok\""), "answer 应回 ok，实际: " + resp);
        }
    }

    @Test
    @DisplayName("信令：同一连接可连续处理多帧（ping/pong 后仍能收信令响应）")
    void multipleFramesOnOneConnection() throws Exception {
        try (Socket socket = connect(null)) {
            handshake(socket, "/webrtc", null, newKey());
            // 控制帧：ping → pong（opcode 0xA）
            writeFrame(socket, 0x9, "hi".getBytes(StandardCharsets.UTF_8));
            Frame pong = readFrame(socket);
            assertEquals(0xA, pong.opcode, "ping 应回 pong");
            assertEquals("hi", new String(pong.payload, StandardCharsets.UTF_8));

            for (int i = 1; i <= 3; i++) {
                writeText(socket, "{\"peerId\":\"p1\",\"type\":\"answer\",\"payload\":\"{}\"}");
                String resp = readText(socket);
                assertNotNull(resp, "第 " + i + " 帧未收到响应");
                assertTrue(resp.contains("\"ok\""), "第 " + i + " 帧响应异常: " + resp);
            }
        }
    }

    @Test
    @DisplayName("非法握手：非 Upgrade 请求直接断开")
    void nonUpgradeRequestIsClosed() throws Exception {
        try (Socket socket = connect(null)) {
            OutputStream out = socket.getOutputStream();
            out.write("GET /webrtc HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            assertEquals(-1, in.read(), "非 Upgrade 请求应被服务端关闭");
        }
    }

    /**
     * 建立 TCP 连接并设置读超时。
     *
     * @param ignored 预留参数
     * @return 已连接的 socket
     * @throws Exception 连接失败
     */
    private static Socket connect(Object ignored) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        return socket;
    }

    /**
     * 发送 WebSocket Upgrade 握手并返回响应头文本。
     *
     * @param socket 连接
     * @param path   路径
     * @param token  令牌（可为 null）
     * @param key    Sec-WebSocket-Key
     * @return 响应头
     * @throws Exception 读写失败
     */
    private static String handshake(Socket socket, String path, String token, String key) throws Exception {
        String target = path + (token != null ? "?token=" + token : "");
        String req = "GET " + target + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(req.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return readHttpHead(socket.getInputStream());
    }

    /**
     * 读取 HTTP 响应头（以空行结束）。
     *
     * @param in 输入流
     * @return 响应头文本
     * @throws Exception 读取失败
     */
    private static String readHttpHead(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            head.write(b);
            byte[] bytes = head.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n'
                    && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') {
                break;
            }
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    /**
     * 取响应首行。
     *
     * @param head 响应头
     * @return 首行
     */
    private static String firstLine(String head) {
        int idx = head.indexOf("\r\n");
        return idx > 0 ? head.substring(0, idx) : head;
    }

    /**
     * 生成随机 Sec-WebSocket-Key。
     *
     * @return base64 字符串
     */
    private static String newKey() {
        byte[] raw = new byte[16];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 7 + 13);
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    /**
     * 计算期望的 Sec-WebSocket-Accept。
     *
     * @param key 客户端 key
     * @return 服务端应返回的值
     * @throws Exception SHA-1 不可用
     */
    private static String accept(String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(
                digest.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * 写一个文本帧（客户端必须加掩码）。
     *
     * @param socket  连接
     * @param message 文本
     * @throws Exception 写出失败
     */
    private static void writeText(Socket socket, String message) throws Exception {
        writeFrame(socket, 0x1, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按 RFC 6455 写一帧（FIN=1、带 4 字节掩码）。
     *
     * @param socket  连接
     * @param opcode  操作码
     * @param payload 载荷
     * @throws Exception 写出失败
     */
    private static void writeFrame(Socket socket, int opcode, byte[] payload) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        int len = payload.length;
        if (len <= 125) {
            frame.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((len >>> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        frame.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        OutputStream out = socket.getOutputStream();
        out.write(frame.toByteArray());
        out.flush();
    }

    /**
     * 读一个文本帧内容。
     *
     * @param socket 连接
     * @return 文本；连接关闭返回 null
     * @throws Exception 读取失败
     */
    private static String readText(Socket socket) throws Exception {
        Frame frame = readFrame(socket);
        if (frame == null || frame.payload == null) {
            return null;
        }
        return new String(frame.payload, StandardCharsets.UTF_8);
    }

    /**
     * 读一帧（服务端帧不加掩码）。
     *
     * @param socket 连接
     * @return 帧；流结束返回 null
     * @throws Exception 读取失败
     */
    private static Frame readFrame(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        int head = in.read();
        if (head < 0) {
            return null;
        }
        int opcode = head & 0x0F;
        int len = in.read() & 0x7F;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) {
                l = (l << 8) | in.read();
            }
            len = (int) l;
        }
        byte[] payload = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(payload, read, len - read);
            if (n < 0) {
                throw new IllegalStateException("帧载荷读取中断");
            }
            read += n;
        }
        return new Frame(opcode, payload);
    }

    /**
     * 解析后的帧。
     */
    private static final class Frame {
        private final int opcode;
        private final byte[] payload;

        private Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }
}
