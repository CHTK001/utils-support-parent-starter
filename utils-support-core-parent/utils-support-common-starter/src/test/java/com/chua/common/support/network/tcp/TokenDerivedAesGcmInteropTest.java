// [P3C A20 豁免] A20/A30/A31: 测试断言与构造参数含魔法值场景，豁免。
package com.chua.common.support.network.tcp;

import com.chua.common.support.network.crypto.AesGcmUtils;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.impl.JdkTcpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * token 派生密钥的 {@link AesGcmUtils} 直连 {@code aes-gcm} 加密服务端的线格式互通。
 *
 * <p>SIP 数据平面是<b>另一条加密路径</b>，不走 {@code StreamCodec} SPI：</p>
 * <ul>
 *   <li>服务端：{@link com.chua.common.support.network.sip.SipServer} 把
 *       {@code config.getToken()} 塞进 {@code ServerSetting.encryptKey}，再交给
 *       {@link JdkTcpServer}，于是实际用的是 SPI 里的 {@code aes-gcm}
 *       （{@code AesGcmStreamCodec} → {@link AesGcmUtils} 帧）；</li>
 *   <li>客户端：{@link com.chua.common.support.network.sip.SipClient} /
 *       {@link com.chua.common.support.network.sip.SipTunnelStream} 直接
 *       {@code AesGcmUtils.deriveKey(token)} 后包流，完全绕开 SPI。</li>
 * </ul>
 *
 * <p>两侧必须落在同一条线上格式上，否则 SIP 能握手、数据面全是空转。本用例用真实套接字
 * 把「token 派生 + AesGcmUtils 手工包流」这一侧接到真实 {@link JdkTcpServer} 上验证。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class TokenDerivedAesGcmInteropTest {

    /**
     * SIP 侧的共享令牌：服务端当 {@code encryptKey}，客户端当派生源。
     */
    private static final String SHARED_TOKEN = "sip-shared-token-月英";

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
     * 起一个帧式回显服务端（与 SIP 服务端同口径：encrypt=true + token 当 key）。
     *
     * @param port 端口
     * @param key  密钥（SIP 侧即 token）
     * @return 服务端实例（未启动）
     */
    private static JdkTcpServer frameServer(int port, String key) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(port);
        setting.setEncrypt(true);
        setting.setEncryptType("aes-gcm");
        setting.setEncryptKey(key);
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(request -> ("echo:" + new String(request, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        return server;
    }

    /**
     * 按 SIP 客户端的方式连上去（随后用 {@link #wrap} 手工加解密，不带任何 SPI）。
     *
     * @param port 端口
     * @return 已连接的套接字
     * @throws IOException 连接失败
     */
    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(15000);
        return socket;
    }

    /**
     * 按 SIP 侧写法包好的双向流。
     *
     * @param in  解密输入流
     * @param out 加密输出流
     */
    private record Streams(InputStream in, OutputStream out) {
    }

    /**
     * 把套接字按 SIP 侧写法包成解密读 / 加密写。
     *
     * @param socket 套接字
     * @param token  共享令牌
     * @return 解密输入流 / 加密输出流
     * @throws IOException 取流失败
     */
    private static Streams wrap(Socket socket, String token) throws IOException {
        SecretKeySpec key = AesGcmUtils.deriveKey(token);
        return new Streams(AesGcmUtils.decrypting(socket.getInputStream(), key),
                AesGcmUtils.encrypting(socket.getOutputStream(), key));
    }

    /**
     * 一次长度帧往返。
     *
     * @param in      解密流
     * @param out     加密流
     * @param request 请求体
     * @return 响应体
     * @throws IOException 读写失败
     */
    private static String frame(InputStream in, OutputStream out, String request) throws IOException {
        DataInputStream dataIn = new DataInputStream(in);
        DataOutputStream dataOut = new DataOutputStream(out);
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        dataOut.writeInt(payload.length);
        dataOut.write(payload);
        dataOut.flush();
        int length = dataIn.readInt();
        byte[] body = new byte[length];
        dataIn.readFully(body);
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * token 派生密钥的客户端（SIP 侧写法）与 {@code aes-gcm} 服务端必须互通，且同连接可多帧。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("token 派生 + 手工包流：与 aes-gcm 服务端互通，同连接多帧")
    void tokenDerivedClientInteropsWithAesGcmServer() throws Exception {
        int port = freePort();
        JdkTcpServer server = frameServer(port, SHARED_TOKEN);
        server.start();
        try (Socket socket = connect(port)) {
            Streams streams = wrap(socket, SHARED_TOKEN);
            assertEquals("echo:隆中对", frame(streams.in(), streams.out(), "隆中对"));
            assertEquals("echo:三分天下", frame(streams.in(), streams.out(), "三分天下"));
        } finally {
            server.stop();
        }
    }

    /**
     * 反证：令牌不同的客户端拿不到任何明文回显。
     *
     * <p>两侧密钥不一致时，服务端解密首帧就会 AEAD 校验失败并断链，客户端只能读到
     * EOF/异常；若这里能读到 {@code echo:...}，说明服务端根本没在解密（加密是空开关）。</p>
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("token 不一致：拿不到明文回显")
    void wrongTokenGetsNoPlaintextEcho() throws Exception {
        int port = freePort();
        JdkTcpServer server = frameServer(port, SHARED_TOKEN);
        server.start();
        try (Socket socket = connect(port)) {
            Streams streams = wrap(socket, SHARED_TOKEN + "-wrong");
            assertThrows(IOException.class, () -> frame(streams.in(), streams.out(), "偷听"),
                    "密钥不一致时不得读到明文回显");
        } finally {
            server.stop();
        }
    }
}
