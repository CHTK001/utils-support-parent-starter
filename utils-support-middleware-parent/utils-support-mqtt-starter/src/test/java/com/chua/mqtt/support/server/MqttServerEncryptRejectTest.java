// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.mqtt.support.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQTT 服务端不消费 {@link ServerSetting#isEncrypt()}，必须在启动时明确拒绝。
 *
 * <p>此前打开 {@code encrypt} 后链路仍是明文：MQTT 终端只协商 TLS，
 * 本实现又没有接入 TLS 监听，静默忽略等于「以为加密了其实没有」。
 * 现在统一在 bind 之前抛出 {@link IllegalStateException}。</p>
 *
 * <p>本类只验证启动语义（异常 + 端口未被占用），不建立真实连接。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
class MqttServerEncryptRejectTest {

    /**
     * 取空闲 TCP 端口。
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
     * 开加密并配了密钥的监听配置。
     *
     * @param port 端口
     * @return 配置
     */
    private static ServerSetting encrypted(int port) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        setting.setEncrypt(true);
        setting.setEncryptType("aes-gcm");
        setting.setEncryptKey("mqtt-口令");
        return setting;
    }

    /**
     * 断言启动被拒绝，且端口没有被占用。
     *
     * @param server 待启动服务器
     * @param port   期望未占用的端口
     * @throws Exception 探测端口失败
     */
    private static void assertRejectedWithoutBind(Runnable server, int port) throws Exception {
        IllegalStateException error = assertThrows(IllegalStateException.class, server::run,
                "MQTT 不消费 encrypt，应在启动时明确拒绝");
        assertTrue(error.getMessage().contains("encrypt"),
                "异常信息应指向 encrypt 开关: " + error.getMessage());
        assertTrue(error.getMessage().contains("ServerSetting"),
                "异常信息应指向具体配置项: " + error.getMessage());
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort(), "拒绝启动后端口应立即可用");
        }
    }

    @Test
    @DisplayName("MQTT：打开 encrypt 必须在 bind 之前拒绝")
    void rejectEncrypt() throws Exception {
        int port = freePort();
        MqttServer server = new MqttServer(encrypted(port));
        assertRejectedWithoutBind(server::start, port);
    }
}
