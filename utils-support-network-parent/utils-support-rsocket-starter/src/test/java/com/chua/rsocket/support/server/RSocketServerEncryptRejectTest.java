// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.rsocket.support.server;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.rsocket.support.sync.RSocketSyncServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RSocket 服务端不消费 {@link ServerSetting#isEncrypt()}，必须在启动时明确拒绝。
 *
 * <p>RSocket 的加密由传输层 TLS 负责，本实现没有接入 TLS；打开 {@code encrypt}
 * 只会静默退化成明文。现在统一在 bind 之前抛出 {@link IllegalStateException}，
 * 同步门面 {@code RSocketSyncServer} 一起拒绝。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
class RSocketServerEncryptRejectTest {

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
        setting.setEncryptKey("rsocket-口令");
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
                "RSocket 不消费 encrypt，应在启动时明确拒绝");
        assertTrue(error.getMessage().contains("encrypt"),
                "异常信息应指向 encrypt 开关: " + error.getMessage());
        assertTrue(error.getMessage().contains("ServerSetting"),
                "异常信息应指向具体配置项: " + error.getMessage());
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort(), "拒绝启动后端口应立即可用");
        }
    }

    @Test
    @DisplayName("RSocket：打开 encrypt 必须在 bind 之前拒绝")
    void rejectEncrypt() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new RSocketServer(encrypted(port))::start, port);
    }

    @Test
    @DisplayName("RSocket 同步服务：打开 encrypt 必须拒绝（转发到 RSocketServer）")
    void rejectEncryptForSync() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new RSocketSyncServer(encrypted(port))::start, port);
    }
}
