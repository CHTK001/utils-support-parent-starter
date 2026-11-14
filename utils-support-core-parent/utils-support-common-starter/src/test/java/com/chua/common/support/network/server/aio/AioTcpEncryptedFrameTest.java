// [P3C A20 豁免] A20/A30/A31: 测试构造的帧字节与断言消息含魔法值场景，豁免。
package com.chua.common.support.network.server.aio;

import com.chua.common.support.lang.algorithm.crypto.KeyLogCodec;
import com.chua.common.support.lang.algorithm.crypto.StreamCodec;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AioTcpServer} 的传输加密（{@link StreamCodec}）集成。
 *
 * <p>服务端只配 {@code encryptType} + {@code encryptKey}，加解密全部由 SPI
 * 按名字解析；帧格式仍是「4 字节大端长度头 + 消息体」，加密只是把它整体套进
 * 编解码器的帧流里，处理器与客户端都不感知。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class AioTcpEncryptedFrameTest {

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
     * 起一个加密的帧式回显服务端：收到一帧，回 {@code echo:<原文>}。
     *
     * @param port 端口
     * @param type 加密类型
     * @param key  加密密钥
     * @return 已启动的服务
     */
    private static AioTcpServer startEncryptedEchoServer(int port, String type, String key) {
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp").host("127.0.0.1").port(port).auto(false)
                .encrypt(true).encryptType(type).encryptKey(key)
                .build();
        AioTcpServer server = new AioTcpServer(setting);
        server.setFrameHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                byte[] prefix = "echo:".getBytes(StandardCharsets.UTF_8);
                byte[] out = new byte[prefix.length + request.length];
                System.arraycopy(prefix, 0, out, 0, prefix.length);
                System.arraycopy(request, 0, out, prefix.length, request.length);
                return out;
            }
        });
        server.start();
        return server;
    }

    /**
     * 在已开启加密的流上做一次长度帧往返。
     *
     * @param in      解密输入流
     * @param out     加密输出流
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
     * @return 加解密后的流
     * @throws IOException 套接字或握手失败
     */
    private static StreamCodec.Streams connect(int port, String type, String key) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(15000);
        StreamCodec codec = StreamCodec.buildStream(type, key);
        return codec.open(socket.getInputStream(), socket.getOutputStream(), true);
    }

    /**
     * aes-gcm：预共享口令，两端配置同一口令即可通信。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("AIO 帧式 + aes-gcm：加密客户端收到回显")
    void aesGcmFrameEndToEnd() throws Exception {
        int port = freePort();
        AioTcpServer server = startEncryptedEchoServer(port, "aes-gcm", "共享口令");
        try {
            StreamCodec.Streams streams = connect(port, "aes-gcm", "共享口令");
            String response = frame(new DataInputStream(streams.in()),
                    new DataOutputStream(streams.out()), "密信-月英");
            assertEquals("echo:密信-月英", response);
        } finally {
            server.stop();
        }
    }

    /**
     * keylog：服务端持长期私钥，客户端持公钥 + 指纹，握手后真实收发。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("AIO 帧式 + keylog：握手后收到回显")
    void keyLogFrameEndToEnd() throws Exception {
        String[] pair = KeyLogCodec.generateKeyPair();
        String pin = KeyLogCodec.fingerprint(pair[0]);
        int port = freePort();
        AioTcpServer server = startEncryptedEchoServer(port, "keylog", "server:" + pair[1]);
        try {
            StreamCodec.Streams streams = connect(port, "keylog", "client:" + pair[0] + "#" + pin);
            String response = frame(new DataInputStream(streams.in()),
                    new DataOutputStream(streams.out()), "隆中对");
            assertEquals("echo:隆中对", response);
        } finally {
            server.stop();
        }
    }

    /**
     * 同一条加密连接上连发多帧：读循环必须继续收帧，不能只回第一帧就停摆。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("AIO 帧式 + 加密：同连接多帧连续回显")
    void multipleFramesOnOneConnection() throws Exception {
        int port = freePort();
        AioTcpServer server = startEncryptedEchoServer(port, "aes-gcm", "共享口令");
        try {
            StreamCodec.Streams streams = connect(port, "aes-gcm", "共享口令");
            DataInputStream in = new DataInputStream(streams.in());
            DataOutputStream out = new DataOutputStream(streams.out());
            assertEquals("echo:一", frame(in, out, "一"));
            assertEquals("echo:二", frame(in, out, "二"));
            assertEquals("echo:三", frame(in, out, "三"));
        } finally {
            server.stop();
        }
    }

    /**
     * 反证：开了加密的服务端，明文客户端绝不能拿到明文回显。
     *
     * <p>若这里能读到 {@code echo:...}，说明 {@code encrypt=true} 只是被记了一条
     * 警告日志而实际仍在明文收发——安全开关形同虚设。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("AIO 帧式 + 加密：明文客户端拿不到回显")
    void plaintextClientCannotTalkToEncryptingServer() throws Exception {
        int port = freePort();
        AioTcpServer server = startEncryptedEchoServer(port, "aes-gcm", "共享口令");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] payload = "明文试探".getBytes(StandardCharsets.UTF_8);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            assertThrows(IOException.class, in::readInt,
                    "加密服务端不得对明文帧回明文响应");
        } catch (SocketTimeoutException timeout) {
            // 对端既不响应也不关闭：同样是「没拿到明文回显」
        } finally {
            server.stop();
        }
    }

    /**
     * 未知加密类型在启动时直接失败，不静默退回明文。
     */
    @Test
    @DisplayName("AIO 未知加密类型启动即失败")
    void unknownTypeFailsAtStart() {
        int port;
        try {
            port = freePort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> startEncryptedEchoServer(port, "no-such-cipher", "k"));
        // 启动模板统一把 doStart 的异常包成 RuntimeException，真因必须在 cause 里可见
        assertTrue(String.valueOf(error.getCause()).contains("传输加密初始化失败"),
                "未知加密类型的失败原因必须是传输加密初始化失败，实际: " + error.getCause());
    }
}
