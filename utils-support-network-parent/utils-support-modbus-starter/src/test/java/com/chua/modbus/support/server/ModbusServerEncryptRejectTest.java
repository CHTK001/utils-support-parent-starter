// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.modbus.support.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modbus 从站不消费 {@link ServerSetting#isEncrypt()}，必须在启动时明确拒绝。
 *
 * <p>Modbus/TCP 报文里没有密钥协商字段，本实现也没有接入 Modbus Security(TLS)；
 * 打开 {@code encrypt} 只会静默退化成明文。现在统一在 bind 之前抛出
 * {@link IllegalStateException}。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
class ModbusServerEncryptRejectTest {

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
        setting.setEncryptKey("modbus-口令");
        return setting;
    }

    /**
     * 断言启动被拒绝，且消息指向 encrypt 开关。
     *
     * @param server 待启动服务器
     * @return 异常
     */
    private static IllegalStateException assertRejected(Runnable server) {
        IllegalStateException error = assertThrows(IllegalStateException.class, server::run,
                "Modbus 不消费 encrypt，应在启动时明确拒绝");
        assertTrue(error.getMessage().contains("encrypt"),
                "异常信息应指向 encrypt 开关: " + error.getMessage());
        assertTrue(error.getMessage().contains("ServerSetting"),
                "异常信息应指向具体配置项: " + error.getMessage());
        return error;
    }

    @Test
    @DisplayName("Modbus TCP：打开 encrypt 必须在 bind 之前拒绝")
    void rejectEncryptForTcp() throws Exception {
        int port = freePort();
        assertRejected(new ModbusTcpServer(encrypted(port))::start);
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort(), "拒绝启动后端口应立即可用");
        }
    }

    @Test
    @DisplayName("Modbus UDP：打开 encrypt 必须在 bind 之前拒绝")
    void rejectEncryptForUdp() throws Exception {
        int port = freePort();
        assertRejected(new ModbusUdpServer(encrypted(port))::start);
        assertDoesNotThrow(() -> {
            try (DatagramSocket probe = new DatagramSocket(port, InetAddress.getByName("127.0.0.1"))) {
                assertTrue(probe.isBound(), "拒绝启动后 UDP 端口应立即可用");
            }
        }, "拒绝启动后 UDP 端口应立即可用");
    }
}
