// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代理族对 {@link ServerSetting#isEncrypt()} 的口径：启动即拒绝，不静默明文。
 *
 * <p>代理是字节透传：它既不持有端到端密钥，也不该替端点解密（解密后仍是本机明文）。
 * 此前打开 {@code encrypt} 会被完全忽略，使用者以为链路已加密、实际两端都是明文。
 * 现在统一在 bind 之前失败，把静默降级变成启动期错误。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class ProxyServerEncryptRejectTest {

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
        setting.setEncryptKey("代理-口令");
        return setting;
    }

    /**
     * 断言「拒绝启动」且端口没有被占用。
     *
     * @param server 待启动服务器
     * @param port   期望未占用的端口
     * @throws Exception 探测端口失败
     */
    private static void assertRejectedWithoutBind(Runnable server, int port) throws Exception {
        IllegalStateException error = assertThrows(IllegalStateException.class, server::run,
                "代理不支持流加密，应在启动时明确拒绝");
        assertTrue(error.getMessage().contains("encrypt"), "异常信息应指向 encrypt 开关: " + error.getMessage());
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort(), "拒绝启动后端口应立即可用");
        }
    }

    /**
     * JDK 阻塞式 TCP 代理：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("TcpProxyServer：开启传输加密启动即失败")
    void tcpProxyRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new TcpProxyServer(encrypted(port))::start, port);
    }

    /**
     * JDK 阻塞式 HTTP 代理（继承 TCP 代理的启动路径）：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkHttpProxyServer：开启传输加密启动即失败")
    void jdkHttpProxyRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new JdkHttpProxyServer(encrypted(port))::start, port);
    }

    /**
     * 通用 HTTP 代理：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("HttpProxyServer：开启传输加密启动即失败")
    void httpProxyRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new HttpProxyServer(encrypted(port))::start, port);
    }

    /**
     * SOCKS5 代理：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("Socks5ProxyServer：开启传输加密启动即失败")
    void socks5ProxyRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new Socks5ProxyServer(encrypted(port))::start, port);
    }

    /**
     * AIO TCP 代理：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("AioTcpProxyServer：开启传输加密启动即失败")
    void aioTcpProxyRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new AioTcpProxyServer(encrypted(port))::start, port);
    }

    /**
     * AIO HTTP 代理：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("AioHttpProxyServer：开启传输加密启动即失败")
    void aioHttpProxyRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new AioHttpProxyServer(encrypted(port))::start, port);
    }
}
