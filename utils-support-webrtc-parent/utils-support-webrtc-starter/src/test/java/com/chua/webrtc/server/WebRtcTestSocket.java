package com.chua.webrtc.server;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 测试用的裸 WebSocket 客户端助手（RFC 6455）。
 *
 * <p>只实现测试需要的最小子集：Upgrade 握手、文本/控制帧读写（客户端帧加掩码、
 * 服务端帧不加掩码）。不依赖任何 WebSocket 库，方便断言线上字节。</p>
 *
 * @author CH
 */
final class WebRtcTestSocket implements AutoCloseable {

    /**
     * RFC 6455 固定 GUID。
     */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * 底层连接。
     */
    private final Socket socket;

    /**
     * 建立 TCP 连接。
     *
     * @param port 服务端端口
     * @throws Exception 连接失败
     */
    private WebRtcTestSocket(int port) throws Exception {
        this.socket = new Socket("127.0.0.1", port);
        this.socket.setSoTimeout(5000);
    }

    /**
     * 连接并设置读超时。
     *
     * @param port        端口
     * @param soTimeoutMs 读超时（毫秒）
     * @return 客户端
     * @throws Exception 连接失败
     */
    static WebRtcTestSocket connect(int port, int soTimeoutMs) throws Exception {
        WebRtcTestSocket client = new WebRtcTestSocket(port);
        client.socket.setSoTimeout(soTimeoutMs);
        return client;
    }

    /**
     * 发送 Upgrade 握手并返回响应头文本。
     *
     * @param path  路径
     * @param token 令牌（可为 null）
     * @param key   Sec-WebSocket-Key
     * @return 响应头
     * @throws Exception 读写失败
     */
    String handshake(String path, String token, String key) throws Exception {
        String target = path + (token != null ? "?token=" + token : "");
        String req = "GET " + target + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
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
     * 生成随机 Sec-WebSocket-Key。
     *
     * @return base64 字符串
     */
    static String newKey() {
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
    static String accept(String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(
                digest.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * 取响应首行。
     *
     * @param head 响应头
     * @return 首行
     */
    static String firstLine(String head) {
        int idx = head.indexOf("\r\n");
        return idx > 0 ? head.substring(0, idx) : head;
    }

    /**
     * 直接写原始字节（用于非法握手等场景）。
     *
     * @param text 文本（ISO-8859-1）
     * @throws Exception 写出失败
     */
    void writeRaw(String text) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * 读一个字节；连接关闭返回 -1。
     *
     * @return 字节
     * @throws Exception 读取失败
     */
    int readByte() throws Exception {
        return socket.getInputStream().read();
    }

    /**
     * 写一个文本帧。
     *
     * @param message 文本
     * @throws Exception 写出失败
     */
    void writeText(String message) throws Exception {
        writeFrame(0x1, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按 RFC 6455 写一帧（FIN=1、带 4 字节掩码）。
     *
     * @param opcode  操作码
     * @param payload 载荷
     * @throws Exception 写出失败
     */
    void writeFrame(int opcode, byte[] payload) throws Exception {
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
     * @return 文本；连接关闭返回 null
     * @throws Exception 读取失败
     */
    String readText() throws Exception {
        Frame frame = readFrame();
        if (frame == null || frame.payload == null) {
            return null;
        }
        return new String(frame.payload, StandardCharsets.UTF_8);
    }

    /**
     * 读一帧（服务端帧不加掩码）。
     *
     * @return 帧；流结束返回 null
     * @throws Exception 读取失败
     */
    Frame readFrame() throws Exception {
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

    @Override
    public void close() throws Exception {
        socket.close();
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
     * 解析后的帧。
     */
    static final class Frame {
        /**
         * 操作码。
         */
        final int opcode;

        /**
         * 载荷。
         */
        final byte[] payload;

        private Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }
}
