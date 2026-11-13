// [P3C A20 豁免] A20/A30/A31: 测试断言与构造参数含魔法值场景，豁免。
package com.chua.common.support.network.server.impl;

import com.chua.common.support.lang.algorithm.crypto.KeyLogCodec;
import com.chua.common.support.lang.algorithm.crypto.StreamCodec;
import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link JdkTcpServer} 传输加密 SPI 集成：真实套接字 + 可切换算法。
 *
 * <p>服务端只配 {@code encryptType} + {@code encryptKey}，加解密全部由
 * {@link StreamCodec}（Codec 二级接口）按名字解析，不写死算法。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class JdkTcpServerStreamCodecTest {

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
     * 起一个回显服务端。
     *
     * @param port 端口
     * @param type 加密类型
     * @param key  加密密钥
     * @return 服务端实例
     */
    private static JdkTcpServer echoServer(int port, String type, String key) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(port);
        setting.setEncrypt(true);
        setting.setEncryptType(type);
        setting.setEncryptKey(key);
        JdkTcpServer server = new JdkTcpServer(setting);
        server.handler((in, out) -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
            String line;
            while ((line = reader.readLine()) != null) {
                writer.println("echo:" + line);
            }
        });
        return server;
    }

    /**
     * 真实 socket 客户端：发起方负责握手。
     */
    private static StreamCodec.Streams connect(int port, String type, String key) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        StreamCodec codec = StreamCodec.buildStream(type, key);
        return codec.open(socket.getInputStream(), socket.getOutputStream(), true);
    }

    /**
     * keylog：服务端持私钥、客户端持公钥+指纹，握手后真实收发。
     */
    @Test
    @DisplayName("keylog 传输加密端到端回显")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void keyLogEndToEnd() throws Exception {
        String[] pair = KeyLogCodec.generateKeyPair();
        String pin = KeyLogCodec.fingerprint(pair[0]);
        int port = freePort();
        JdkTcpServer server = echoServer(port, "keylog", "server:" + pair[1]);
        server.start();
        try {
            StreamCodec.Streams streams = connect(port, "keylog", "client:" + pair[0] + "#" + pin);
            PrintWriter writer = new PrintWriter(streams.out(), true, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(new InputStreamReader(streams.in(), StandardCharsets.UTF_8));
            writer.println("密信-月英");
            assertEquals("echo:密信-月英", reader.readLine());
        } finally {
            server.stop();
        }
    }

    /**
     * aes-gcm：同一口令，兼容既有预共享口令用法。
     */
    @Test
    @DisplayName("aes-gcm 传输加密端到端回显")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aesGcmEndToEnd() throws Exception {
        int port = freePort();
        JdkTcpServer server = echoServer(port, "aes-gcm", "共享口令");
        server.start();
        try {
            StreamCodec.Streams streams = connect(port, "aes-gcm", "共享口令");
            PrintWriter writer = new PrintWriter(streams.out(), true, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(new InputStreamReader(streams.in(), StandardCharsets.UTF_8));
            writer.println("hello");
            assertEquals("echo:hello", reader.readLine());
        } finally {
            server.stop();
        }
    }

    /**
     * 未知加密类型在启动时直接失败，不静默退回明文。
     */
    @Test
    @DisplayName("未知加密类型启动即失败")
    void unknownTypeFailsAtStart() {
        int port;
        try {
            port = freePort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThrows(IllegalStateException.class, () -> echoServer(port, "no-such-cipher", "k").start());
    }
}
