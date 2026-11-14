// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.ssh.support.server;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSH 服务端不消费 {@link ServerSetting#isEncrypt()}，必须在启动时明确拒绝。
 *
 * <p>SSH 自身的传输层已经协商加密算法，{@code encrypt} 的流加密与之冲突：
 * 打开后此前会静默忽略，等于让配置说谎。现在统一在 bind 之前抛出
 * {@link IllegalStateException}。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
class SshServerEncryptRejectTest {

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
     * 构建开加密并配了密钥的 SSH 服务端。
     *
     * @param port 端口
     * @return 服务端
     */
    private static SshServer encrypted(int port) {
        SshServer server = SshServer.builder()
                .host("127.0.0.1")
                .port(port)
                .password("口令")
                .build();
        ServerSetting setting = server.getSetting();
        setting.setAuto(false);
        setting.setEncrypt(true);
        setting.setEncryptType("aes-gcm");
        setting.setEncryptKey("ssh-口令");
        return server;
    }

    @Test
    @DisplayName("SSH：打开 encrypt 必须在 bind 之前拒绝")
    void rejectEncrypt() throws Exception {
        int port = freePort();
        SshServer server = encrypted(port);
        IllegalStateException error = assertThrows(IllegalStateException.class, server::start,
                "SSH 不消费 encrypt，应在启动时明确拒绝");
        assertTrue(error.getMessage().contains("encrypt"),
                "异常信息应指向 encrypt 开关: " + error.getMessage());
        assertTrue(error.getMessage().contains("ServerSetting"),
                "异常信息应指向具体配置项: " + error.getMessage());
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort(), "拒绝启动后端口应立即可用");
        }
    }
}
