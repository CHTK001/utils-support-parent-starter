// [P3C A20 豁免] A20/A30/A31: 测试断言与构造参数含魔法值场景，豁免。
package com.chua.common.support.network.tcp;

import com.chua.common.support.lang.algorithm.crypto.KeyLogCodec;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkTcpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 成对 Client/Server 与 {@link com.chua.common.support.lang.algorithm.crypto.StreamCodec} 的集成。
 *
 * <p>仓库里能真正成对使用的客户端/服务端只有一对：{@link JdkTcpClient} 的 4 字节长度帧
 * 对应 {@link JdkTcpServer} 的帧式处理器（{@code TcpServerHandler}）。本用例验证这一对
 * 在 {@code encrypt=true} 时是否真的加密，而不是只验服务端流式路径。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class TcpClientStreamCodecTest {

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
     * 起一个帧式回显服务端：收到一帧，回 {@code echo:<原文>}。
     *
     * @param port 端口
     * @param type 加密类型
     * @param key  加密密钥
     * @return 服务端实例（未启动）
     */
    private static JdkTcpServer frameServer(int port, String type, String key) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(port);
        setting.setEncrypt(true);
        setting.setEncryptType(type);
        setting.setEncryptKey(key);
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(request -> ("echo:" + new String(request, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        return server;
    }

    /**
     * 在已建立的连接上做一次长度帧往返（明文，用于反证服务端没在加密）。
     *
     * @param socket  已连接的套接字
     * @param request 请求体
     * @return 响应体
     * @throws IOException 读写失败
     */
    private static String plainFrame(Socket socket, String request) throws IOException {
        return frame(new DataInputStream(socket.getInputStream()),
                new DataOutputStream(socket.getOutputStream()), request);
    }

    /**
     * 一次长度帧往返。
     *
     * @param in      输入流
     * @param out     输出流
     * @param request 请求体
     * @return 响应体
     * @throws IOException 读写失败
     */
    private static String frame(DataInputStream in, DataOutputStream out, String request) throws IOException {
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
        int length = in.readInt();
        byte[] body = new byte[length];
        in.readFully(body);
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * 连接加密服务端。
     *
     * @param port 端口
     * @param type 加密类型
     * @param key  密钥
     * @return 已开启加密的客户端
     */
    private static JdkTcpClient encryptedClient(int port, String type, String key) {
        return new JdkTcpClient(1, 5000, 5000).encrypt(type, key);
    }

    /**
     * aes-gcm：预共享口令，两端都配同一口令即可通信。
     */
    @Test
    @DisplayName("帧式 + aes-gcm：加密客户端收到回显")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aesGcmFrameEndToEnd() throws Exception {
        int port = freePort();
        JdkTcpServer server = frameServer(port, "aes-gcm", "共享口令");
        server.start();
        try (JdkTcpClient client = encryptedClient(port, "aes-gcm", "共享口令")) {
            String response = new String(
                    client.call("127.0.0.1", port, "密信-月英".getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
            assertEquals("echo:密信-月英", response);
        } finally {
            server.stop();
        }
    }

    /**
     * keylog：客户端每次调用各握手一次，服务端持长期私钥。
     */
    @Test
    @DisplayName("帧式 + keylog：握手后收到回显")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void keyLogFrameEndToEnd() throws Exception {
        String[] pair = KeyLogCodec.generateKeyPair();
        String pin = KeyLogCodec.fingerprint(pair[0]);
        int port = freePort();
        JdkTcpServer server = frameServer(port, "keylog", "server:" + pair[1]);
        server.start();
        try (JdkTcpClient client = encryptedClient(port, "keylog", "client:" + pair[0] + "#" + pin)) {
            String response = new String(
                    client.call("127.0.0.1", port, "隆中对".getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
            assertEquals("echo:隆中对", response);
        } finally {
            server.stop();
        }
    }

    /**
     * 反证：开了加密的服务端，明文客户端绝不能拿到明文回显。
     *
     * <p>若这里能读到 {@code echo:...}，说明 {@code encrypt=true} 只是被记了一条
     * 警告日志而实际仍在明文收发——安全开关形同虚设。</p>
     */
    @Test
    @DisplayName("帧式 + 加密：明文客户端拿不到回显")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void plaintextClientCannotTalkToEncryptingServer() throws Exception {
        int port = freePort();
        JdkTcpServer server = frameServer(port, "aes-gcm", "共享口令");
        server.start();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            assertThrows(IOException.class, () -> plainFrame(socket, "明文试探"));
        } finally {
            server.stop();
        }
    }

    /**
     * 同一条加密连接上连发多帧：服务端读循环必须继续收帧，不能只回第一帧就停摆。
     */
    @Test
    @DisplayName("帧式 + 加密：同连接多帧连续回显")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void multipleFramesOnOneConnection() throws Exception {
        int port = freePort();
        JdkTcpServer server = frameServer(port, "aes-gcm", "共享口令");
        server.start();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            com.chua.common.support.lang.algorithm.crypto.StreamCodec codec =
                    com.chua.common.support.lang.algorithm.crypto.StreamCodec
                            .buildStream("aes-gcm", "共享口令");
            com.chua.common.support.lang.algorithm.crypto.StreamCodec.Streams streams =
                    codec.open(socket.getInputStream(), socket.getOutputStream(), true);
            DataInputStream in = new DataInputStream(streams.in());
            DataOutputStream out = new DataOutputStream(streams.out());
            assertEquals("echo:一", frame(in, out, "一"));
            assertEquals("echo:二", frame(in, out, "二"));
            assertEquals("echo:三", frame(in, out, "三"));
        } finally {
            server.stop();
        }
    }
}
