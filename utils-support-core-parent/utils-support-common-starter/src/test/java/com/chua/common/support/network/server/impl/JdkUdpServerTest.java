// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkUdpServer} 的真实链路回归。
 *
 * <p>UDP 与 TCP 的失败模式完全不同（无连接、可能丢包、单包 64KB 上限），
 * 之前零覆盖。这里验：通配 handler 能收到包并回包、4KB 包不截断。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class JdkUdpServerTest {

    /**
     * 大包用例的负载大小（字节）。
     */
    private static final int BIG_PAYLOAD = 4096;

    /**
     * 单次往返超时（毫秒）。
     */
    private static final int TIMEOUT_MS = 5000;

    /**
     * 监听配置。
     *
     * @return 配置
     */
    private static ServerSetting setting() {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 发一个包并收一个回包。
     *
     * @param port 服务端端口
     * @param data 负载
     * @return 回包负载
     * @throws Exception 套接字失败
     */
    private static byte[] roundTrip(int port, byte[] data) throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(TIMEOUT_MS);
            InetAddress address = InetAddress.getByName("127.0.0.1");
            client.send(new DatagramPacket(data, data.length, address, port));

            byte[] buffer = new byte[65535];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            client.receive(response);
            byte[] payload = new byte[response.getLength()];
            System.arraycopy(response.getData(), response.getOffset(), payload, 0, response.getLength());
            return payload;
        }
    }

    /**
     * 通配 handler 回包，内容与请求可对应。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkUdpServer：通配 handler 收到包并回包")
    void echoesThroughWildcardHandler() throws Exception {
        JdkUdpServer server = new JdkUdpServer(setting());
        server.registerHandler("*", (data, sender) ->
                ("PONG:" + new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        server.start();
        try {
            int port = server.getPort();
            assertTrue(port > 0, "服务器未绑定端口");
            byte[] response = roundTrip(port, "ping".getBytes(StandardCharsets.UTF_8));
            assertEquals("PONG:ping", new String(response, StandardCharsets.UTF_8),
                    "UDP 回包内容不符");
        } finally {
            server.stop();
        }
    }

    /**
     * 4KB 包必须完整往返（UDP 容易被 readBuffer 截断）。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkUdpServer：4KB 包完整往返")
    void handlesLargePayload() throws Exception {
        JdkUdpServer server = new JdkUdpServer(setting());
        server.registerHandler("*", (data, sender) -> data);
        server.start();
        try {
            byte[] payload = new byte[BIG_PAYLOAD];
            java.util.Arrays.fill(payload, (byte) 'u');
            byte[] response = roundTrip(server.getPort(), payload);
            assertEquals(BIG_PAYLOAD, response.length, "回包长度被截断");
            assertTrue(java.util.Arrays.equals(payload, response), "回包内容被改写");
        } finally {
            server.stop();
        }
    }
}
