// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server;

import com.chua.common.support.network.ftp.FtpServer;
import com.chua.common.support.network.ipc.IpcServer;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.impl.JdkHttpServer;
import com.chua.common.support.network.server.impl.JdkTelnetServer;
import com.chua.common.support.network.server.impl.JdkUdpServer;
import com.chua.common.support.network.server.nio.NioHttpServer;
import com.chua.common.support.network.server.websocket.JdkWebSocketServer;
import com.chua.common.support.network.sync.impl.HttpSyncServer;
import com.chua.common.support.network.sync.impl.TcpSyncServer;
import com.chua.common.support.network.sync.impl.UdpSyncServer;
import com.chua.common.support.network.sync.impl.WebSocketSyncServer;
import com.chua.common.support.scatter.node.UdpScatterNodeServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不消费 {@link ServerSetting#isEncrypt()} 的服务端实现，必须在启动时明确拒绝。
 *
 * <p>这些实现此前会静默忽略加密开关：使用者打开 {@code encrypt} 后，链路仍是明文。
 * 现在统一在 bind 之前抛出 {@link IllegalStateException}，把「以为加密了、其实没有」
 * 变成启动期错误；需要加密时按异常提示改用带 TLS / 加密能力的实现。</p>
 *
 * <p>本类只验证启动语义（异常 + 端口未被占用），不需要真实建连。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class ServerEncryptRejectTest {

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
        setting.setEncryptKey("服务端-口令");
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
        assertTrue(error.getMessage().contains("ServerSetting"),
                "异常信息应指向具体配置项: " + error.getMessage());
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort(), "拒绝启动后端口应立即可用");
        }
    }

    /**
     * JDK HTTP：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkHttpServer：开启传输加密启动即失败")
    void jdkHttpRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new JdkHttpServer(encrypted(port))::start, port);
    }

    /**
     * AIO HTTP：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("AioHttpServer：开启传输加密启动即失败")
    void aioHttpRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new AioHttpServer(encrypted(port))::start, port);
    }

    /**
     * NIO HTTP：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("NioHttpServer：开启传输加密启动即失败")
    void nioHttpRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new NioHttpServer(encrypted(port))::start, port);
    }

    /**
     * Telnet：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkTelnetServer：开启传输加密启动即失败")
    void telnetRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new JdkTelnetServer(encrypted(port))::start, port);
    }

    /**
     * UDP：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkUdpServer：开启传输加密启动即失败")
    void udpRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new JdkUdpServer(encrypted(port))::start, port);
    }

    /**
     * JDK WebSocket：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkWebSocketServer：开启传输加密启动即失败")
    void jdkWebSocketRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new JdkWebSocketServer(encrypted(port))::start, port);
    }

    /**
     * HTTP 同步：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("HttpSyncServer：开启传输加密启动即失败")
    void httpSyncRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new HttpSyncServer(encrypted(port))::start, port);
    }

    /**
     * TCP 同步：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("TcpSyncServer：开启传输加密启动即失败")
    void tcpSyncRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new TcpSyncServer(encrypted(port))::start, port);
    }

    /**
     * UDP 同步：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("UdpSyncServer：开启传输加密启动即失败")
    void udpSyncRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new UdpSyncServer(encrypted(port))::start, port);
    }

    /**
     * WebSocket 同步：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("WebSocketSyncServer：开启传输加密启动即失败")
    void webSocketSyncRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new WebSocketSyncServer(encrypted(port))::start, port);
    }

    /**
     * scatter UDP 节点：开加密启动即失败。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("UdpScatterNodeServer：开启传输加密启动即失败")
    void udpScatterRejects() throws Exception {
        int port = freePort();
        assertRejectedWithoutBind(new UdpScatterNodeServer(encrypted(port), frame -> new byte[0])::start, port);
    }

    /**
     * 明文 FTP：开加密启动即失败，提示改用 FTPS。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("FtpServer：开启传输加密启动即失败（提示改用 FtpsServer）")
    void ftpRejects() throws Exception {
        int port = freePort();
        ServerSetting setting = encrypted(port);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                new FtpServer(setting)::start, "明文 FTP 应在启动时拒绝 encrypt");
        assertTrue(error.getMessage().contains("FtpsServer"),
                "异常信息应指向 FtpsServer: " + error.getMessage());
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort(), "拒绝启动后端口应立即可用");
        }
    }

    /**
     * 进程内 IPC 路由：没有网络链路，开加密同样明确拒绝。
     */
    @Test
    @Timeout(60)
    @DisplayName("IpcServer：没有网络链路，开启传输加密启动即失败")
    void ipcRejects() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                new IpcServer(encrypted(0))::start, "IPC 无传输层，应明确拒绝 encrypt");
        assertTrue(error.getMessage().contains("encrypt"),
                "异常信息应指向 encrypt 开关: " + error.getMessage());
    }
}
