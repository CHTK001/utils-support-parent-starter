// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.vertx.support.server;

import com.chua.common.support.lang.algorithm.crypto.KeyLogCodec;
import com.chua.common.support.lang.algorithm.crypto.StreamCodec;
import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VertxTcpServer} 传输加密真实链路：回显 / Handler / 帧三条路径。
 *
 * <p>服务端开 {@code encrypt=true} 且配了 {@code encryptKey}，就必须真的走
 * {@link StreamCodec}；此前该实现完全不读 {@code ServerSetting#isEncrypt()}，
 * 开关打开仍返回明文，本用例即是回归。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class VertxTcpServerEncryptTest {

    /**
     * 明文断言用的标记。
     */
    private static final String SECRET = "vertx-secret-月英";

    /**
     * 取空闲端口。
     *
     * @return 端口
     * @throws Exception 套接字失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /**
     * 监听配置。
     *
     * @param port 端口
     * @param type 加密类型
     * @param key  密钥参数
     * @return 配置
     */
    private static ServerSetting encryptSetting(int port, String type, String key) {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        setting.setEncrypt(true);
        setting.setEncryptType(type);
        setting.setEncryptKey(key);
        return setting;
    }

    /**
     * 真实 socket + 发起方握手。
     *
     * @param port 端口
     * @param type 加密类型
     * @param key  客户端密钥参数
     * @return 连接与编解码流
     * @throws Exception 连接失败
     */
    private static Client connect(int port, String type, String key) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        StreamCodec.Streams streams = StreamCodec.buildStream(type, key)
                .open(socket.getInputStream(), socket.getOutputStream(), true);
        return new Client(socket, streams);
    }

    /**
     * 读满指定字节。
     *
     * @param in     输入流
     * @param length 需要读取的字节数
     * @return 实读字节
     * @throws IOException 读取失败
     */
    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read == length ? buffer : java.util.Arrays.copyOf(buffer, read);
    }

    /**
     * 写一帧：4 字节大端长度头 + 主体。
     *
     * @param out  输出流
     * @param body 主体
     * @throws IOException 写入失败
     */
    private static void writeFrame(OutputStream out, byte[] body) throws IOException {
        byte[] len = new byte[4];
        len[0] = (byte) (body.length >>> 24);
        len[1] = (byte) (body.length >>> 16);
        len[2] = (byte) (body.length >>> 8);
        len[3] = (byte) body.length;
        out.write(len);
        out.write(body);
        out.flush();
    }

    /**
     * 默认回显：加密开启后必须是密文，明文客户端读不到原文。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpServer：加密开启后默认回显不再是明文")
    void echoIsEncrypted() throws Exception {
        int port = freePort();
        VertxTcpServer server = new VertxTcpServer(encryptSetting(port, "aes-gcm", "月英-共享口令"));
        server.start();
        try {
            try (Socket plain = new Socket("127.0.0.1", port)) {
                plain.setSoTimeout(3000);
                plain.getOutputStream().write(SECRET.getBytes(StandardCharsets.UTF_8));
                plain.getOutputStream().flush();
                int n = -1;
                ByteArrayOutputStream collected = new ByteArrayOutputStream();
                try {
                    byte[] buffer = new byte[256];
                    n = plain.getInputStream().read(buffer);
                    if (n > 0) {
                        collected.write(buffer, 0, n);
                    }
                } catch (IOException expected) {
                    // 密文解析失败直接断开也算正确
                }
                String leaked = collected.toString(StandardCharsets.UTF_8);
                assertTrue(n <= 0 || !leaked.contains(SECRET),
                        "加密已开启，明文客户端仍读到明文回显: " + leaked);
            }

            try (Client client = connect(port, "aes-gcm", "月英-共享口令")) {
                client.out().write(SECRET.getBytes(StandardCharsets.UTF_8));
                client.out().flush();
                byte[] echoed = readFully(client.in(), SECRET.getBytes(StandardCharsets.UTF_8).length);
                assertEquals(SECRET, new String(echoed, StandardCharsets.UTF_8), "解密回显内容不符");
            }
        } finally {
            server.stop();
        }
    }

    /**
     * Handler 路径：处理器拿到的必须是解密后的原文。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpServer：Handler 路径流式加密")
    void handlerPathIsEncrypted() throws Exception {
        String[] pair = KeyLogCodec.generateKeyPair();
        String pin = KeyLogCodec.fingerprint(pair[0]);
        int port = freePort();
        VertxTcpServer server = new VertxTcpServer(encryptSetting(port, "keylog", "server:" + pair[1]));
        server.registerHandler("*", (in, out) -> {
            byte[] buffer = new byte[256];
            int n = in.read(buffer);
            if (n > 0) {
                out.write("echo:".getBytes(StandardCharsets.UTF_8));
                out.write(buffer, 0, n);
                out.flush();
            }
        });
        server.start();
        try (Client client = connect(port, "keylog", "client:" + pair[0] + "#" + pin)) {
            client.out().write(SECRET.getBytes(StandardCharsets.UTF_8));
            client.out().flush();
            byte[] expected = ("echo:" + SECRET).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expected, readFully(client.in(), expected.length), "Handler 解密/加密链路不符");
        } finally {
            server.stop();
        }
    }

    /**
     * 帧路径：长度头 + 主体整体加密，帧格式不变。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpServer：帧路径请求响应整体加密")
    void framePathIsEncrypted() throws Exception {
        int port = freePort();
        VertxTcpServer server = new VertxTcpServer(encryptSetting(port, "aes-gcm", "帧密钥"));
        server.setHandler(frame -> ("frame:" + new String(frame, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        server.start();
        try (Client client = connect(port, "aes-gcm", "帧密钥")) {
            writeFrame(client.out(), SECRET.getBytes(StandardCharsets.UTF_8));
            byte[] len = readFully(client.in(), 4);
            assertEquals(4, len.length, "响应长度头缺失");
            int bodyLen = ((len[0] & 0xff) << 24) | ((len[1] & 0xff) << 16)
                    | ((len[2] & 0xff) << 8) | (len[3] & 0xff);
            byte[] body = readFully(client.in(), bodyLen);
            assertEquals("frame:" + SECRET, new String(body, StandardCharsets.UTF_8), "帧响应不符");
        } finally {
            server.stop();
        }
    }

    /**
     * 未知加密类型必须在启动时失败，不能静默退回明文。
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpServer：未知加密类型启动即失败")
    void unknownCipherFailsAtStart() throws Exception {
        int port = freePort();
        VertxTcpServer server = new VertxTcpServer(encryptSetting(port, "no-such-cipher", "k"));
        assertThrows(RuntimeException.class, server::start);
    }

    /**
     * 客户端连接与编解码流。
     */
    private record Client(Socket socket, StreamCodec.Streams streams) implements AutoCloseable {

        /**
         * 加密输出流。
         *
         * @return 输出流
         */
        private OutputStream out() {
            return streams.out();
        }

        /**
         * 解密输入流。
         *
         * @return 输入流
         */
        private InputStream in() {
            return streams.in();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
