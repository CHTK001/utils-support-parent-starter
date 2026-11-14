// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.tcp;

import com.chua.common.support.lang.algorithm.crypto.KeyLogCodec;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.vertx.support.server.VertxTcpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VertxTcpClient} 传输加密真实链路。
 *
 * <p>该客户端此前没有 {@code encrypt} 入口（与 {@code JdkTcpClient} 不对称），
 * 服务端 {@link VertxTcpServer} 支持 {@code StreamCodec} 而客户端只能自己手工包流。
 * 现在补上与 {@code JdkTcpClient#encrypt(String, String)} 同口径的入口：
 * {@code aes-gcm} 预共享口令、{@code keylog} 每连接握手，帧格式仍为
 * 「4 字节大端长度头 + 主体」。</p>
 *
 * <p>用例对端是真实 {@link VertxTcpServer}，不是 mock；反证用例用错口令断言拿不到明文回显。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
class VertxTcpClientEncryptTest {

    /**
     * 断言用的标记。
     */
    private static final String SECRET = "vertx-client-月英";

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
     * 开加密的服务端配置。
     *
     * @param port 端口
     * @param type 加密类型
     * @param key  密钥参数
     * @return 配置
     */
    private static ServerSetting serverSetting(int port, String type, String key) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        setting.setEncrypt(true);
        setting.setEncryptType(type);
        setting.setEncryptKey(key);
        return setting;
    }

    /**
     * 起一个帧模式回显服务端（响应前缀 {@code echo:}）。
     *
     * @param port 端口
     * @param type 加密类型
     * @param key  服务端密钥参数
     * @return 已启动服务端
     */
    private static VertxTcpServer startEchoServer(int port, String type, String key) {
        VertxTcpServer server = new VertxTcpServer(serverSetting(port, type, key));
        server.setHandler(frame -> ("echo:" + new String(frame, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        server.start();
        return server;
    }

    /**
     * aes-gcm：预共享口令下请求响应整体加解密。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpClient：aes-gcm 加密调用与真实服务端互通")
    void aesGcmRoundTrip() throws Exception {
        int port = freePort();
        String key = "客户端-共享口令";
        VertxTcpServer server = startEchoServer(port, "aes-gcm", key);
        try (VertxTcpClient client = new VertxTcpClient()) {
            client.encrypt("aes-gcm", key);
            byte[] response = client.call("127.0.0.1", port, SECRET.getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(("echo:" + SECRET).getBytes(StandardCharsets.UTF_8), response,
                    "aes-gcm 响应内容不符");
        } finally {
            server.stop();
        }
    }

    /**
     * keylog：每连接握手一次，客户端持服务端公钥。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpClient：keylog 握手后加解密")
    void keyLogRoundTrip() throws Exception {
        String[] pair = KeyLogCodec.generateKeyPair();
        String pin = KeyLogCodec.fingerprint(pair[0]);
        int port = freePort();
        VertxTcpServer server = startEchoServer(port, "keylog", "server:" + pair[1]);
        try (VertxTcpClient client = new VertxTcpClient()) {
            client.encrypt("keylog", "client:" + pair[0] + "#" + pin);
            byte[] response = client.call("127.0.0.1", port, SECRET.getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(("echo:" + SECRET).getBytes(StandardCharsets.UTF_8), response,
                    "keylog 握手后响应内容不符");
        } finally {
            server.stop();
        }
    }

    /**
     * 同一实例连续两次调用：每次都是新连接，编解码器需可复用（keylog 每次重新握手）。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpClient：同一实例连续调用复用编解码器")
    void reuseCodecAcrossCalls() throws Exception {
        String[] pair = KeyLogCodec.generateKeyPair();
        String pin = KeyLogCodec.fingerprint(pair[0]);
        int port = freePort();
        VertxTcpServer server = startEchoServer(port, "keylog", "server:" + pair[1]);
        try (VertxTcpClient client = new VertxTcpClient()) {
            client.encrypt("keylog", "client:" + pair[0] + "#" + pin);
            for (int i = 1; i <= 2; i++) {
                String payload = SECRET + "-" + i;
                byte[] response = client.call("127.0.0.1", port, payload.getBytes(StandardCharsets.UTF_8));
                assertArrayEquals(("echo:" + payload).getBytes(StandardCharsets.UTF_8), response,
                        "第 " + i + " 次调用响应不符");
            }
        } finally {
            server.stop();
        }
    }

    /**
     * 反证：口令不匹配时服务端解密失败断链，客户端拿不到明文回显。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpClient：口令不匹配拿不到明文回显")
    void wrongKeyGetsNoPlainEcho() throws Exception {
        int port = freePort();
        VertxTcpServer server = startEchoServer(port, "aes-gcm", "服务端口令");
        try (VertxTcpClient client = new VertxTcpClient()) {
            client.encrypt("aes-gcm", "错误口令");
            byte[] leaked = null;
            try {
                leaked = client.call("127.0.0.1", port, SECRET.getBytes(StandardCharsets.UTF_8));
            } catch (Exception expected) {
                // 服务端认证失败直接断链属于正确行为
            }
            if (leaked != null) {
                String text = new String(leaked, StandardCharsets.UTF_8);
                assertTrue(!text.contains(SECRET), "口令不匹配却拿到明文回显: " + text);
            }
        } finally {
            server.stop();
        }
    }

    /**
     * 未知加密类型在配置时就失败，不能静默退回明文。
     */
    @Test
    @DisplayName("VertxTcpClient：未知加密类型配置即失败")
    void unknownCipherFailsFast() {
        try (VertxTcpClient client = new VertxTcpClient()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> client.encrypt("no-such-cipher", "k"), "未知加密类型应直接拒绝");
            assertTrue(error.getMessage().contains("no-such-cipher"),
                    "异常信息应指出不支持的类型: " + error.getMessage());
        }
    }
}
