// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.vertx.support.server;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.vertx.support.server.websocket.VertxWebSocketServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vert.x 服务器对 {@link ServerSetting#isEncrypt()} 的口径：不消费就启动即拒绝。
 *
 * <p>{@link VertxTcpServer} 已支持 {@code StreamCodec} 流加密；HTTP / WebSocket 走
 * {@code SslConfig}，代理是字节透传。后三者此前会把 {@code encrypt} 开关整个忽略，
 * 打开后链路仍是明文。现在统一在创建 Vert.x 实例之前失败，消除静默降级。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class VertxServerEncryptRejectTest {

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
     * 开加密且配了密钥的监听配置。
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
        setting.setEncryptKey("vertx-口令");
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
                "该实现不消费 encrypt，应在启动时明确拒绝");
        assertTrue(error.getMessage().contains("encrypt"),
                "异常信息应指向 encrypt 开关: " + error.getMessage());
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort(), "拒绝启动后端口应立即可用");
        }
    }

    /**
     * Vert.x HTTP：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxHttpServer：开启传输加密启动即失败")
    void vertxHttpRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new VertxHttpServer(encrypted(port))::start, port);
    }

    /**
     * Vert.x WebSocket：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxWebSocketServer：开启传输加密启动即失败")
    void vertxWebSocketRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new VertxWebSocketServer(encrypted(port))::start, port);
    }

    /**
     * Vert.x TCP 代理：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxTcpProxyServer：开启传输加密启动即失败")
    void vertxTcpProxyRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new VertxTcpProxyServer(encrypted(port))::start, port);
    }

    /**
     * Vert.x HTTP 代理：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("VertxHttpProxyServer：开启传输加密启动即失败")
    void vertxHttpProxyRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new VertxHttpProxyServer(encrypted(port))::start, port);
    }
}
