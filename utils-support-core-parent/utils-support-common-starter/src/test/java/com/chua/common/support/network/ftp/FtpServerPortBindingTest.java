package com.chua.common.support.network.ftp;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTP / FTPS —— 必须绑定调用方配置的端口，而不是自身的默认端口。
 *
 * <p>服务管理以 SPI 方式装配服务器时，实例化按「实参运行时类型」精确匹配构造器，
 * 传入的是 {@link ServerSetting}。两个类原先都没有接受它的构造器，匹配失败后回退无参
 * 构造，于是 FTP 绑定 {@code FtpConfig.defaults()} 的 21 端口 —— 与节点配置的端口完全
 * 无关：节点状态报 RUNNING、日志也报启动成功，实际配置端口上没有任何监听（实测 FTP
 * banner 只在 21 上出现）。21 还是特权端口，非 root 进程根本起不来。</p>
 *
 * <p>断言方式：先自行挑一个空闲端口传进去，启动后检查<b>该端口</b>是否已被占用。
 * 不读 {@code getSetting().getPort()}——两个实现的 doStart 对「回写实际绑定端口」的处理
 * 并不一致（{@code FtpServer} 回写、{@code FtpsServer} 不回写），依赖它会把测试绑死在一个
 * 与本用例无关的实现细节上。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class FtpServerPortBindingTest {

    /**
     * 取一个当前空闲的端口：先绑定再释放。
     *
     * @return 空闲端口
     * @throws IOException 套接字操作失败
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /**
     * 指定端口当前是否已被占用。
     *
     * @param port 端口
     * @return 已被占用返回 true
     */
    private static boolean isBound(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static ServerSetting setting(int port) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        return setting;
    }

    @Test
    @DisplayName("FtpServer 必须提供 (ServerSetting) 构造器，否则 SPI 装配会回退到 21 端口")
    void ftpServerAcceptsServerSetting() {
        assertDoesNotThrow(() -> new FtpServer(setting(0)),
                "服务管理传入的是 ServerSetting，缺少该构造器会导致配置端口被忽略");
    }

    @Test
    @DisplayName("FtpsServer 必须提供 (ServerSetting) 构造器，否则建不出节点")
    void ftpsServerAcceptsServerSetting() {
        assertDoesNotThrow(() -> new FtpsServer(setting(0)),
                "缺少该构造器时 SPI 实例化失败，只表现为「Server SPI 未注册: ftps」");
    }

    @Test
    @DisplayName("FTP 必须监听配置端口")
    void ftpBindsConfiguredPort() throws Exception {
        int port = freePort();
        FtpServer server = new FtpServer(setting(port));
        try {
            server.start();

            assertTrue(isBound(port), "配置端口 " + port + " 应已被监听");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("FTPS 必须监听配置端口，而不是默认的 990")
    void ftpsBindsConfiguredPort() throws Exception {
        int port = freePort();
        FtpsServer server = new FtpsServer(setting(port));
        try {
            server.start();

            assertTrue(isBound(port), "配置端口 " + port + " 应已被监听");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("配置一个明确被占用的端口时必须启动失败，而不是静默改绑别处")
    void occupiedPortFailsLoudly() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(true);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            int taken = occupied.getLocalPort();

            FtpServer server = new FtpServer(setting(taken));
            assertThrows(RuntimeException.class, server::start,
                    "端口被占用时必须抛错，让调用方把节点标成 ERROR，而不是报成功却监听在别处");
        }
    }

    @Test
    @DisplayName("无参构造仍应保持既有默认路径（新增 ServerSetting 构造器没有改动它）")
    void noArgConstructorStillWorks() {
        FtpServer ftp = new FtpServer();
        assertNotNull(ftp.getSetting(), "无参构造应产出可用的 setting");
        assertEquals(21, ftp.getSetting().getPort(), "无参构造应仍是 FtpConfig 的默认控制端口 21");

        FtpsServer ftps = new FtpsServer();
        assertNotNull(ftps.getSetting(), "无参构造应产出可用的 setting");
    }
}
