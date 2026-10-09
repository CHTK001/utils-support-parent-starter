package com.chua.common.support.network.ftp;

import com.chua.common.support.network.PortBindRetry;
import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p><b>重启时的端口竞争回归</b>：服务管理在 {@code ApplicationReadyEvent} 里自动拉起
 * 启用节点，而本应用优雅关闭本身要 30~40s。重启时旧 JVM 还占着端口，新进程 bind 就会
 * 拿到 {@code BindException: Address already in use}，节点就此永久停在 ERROR——
 * 实际故障记录正是 FTPS 在 {@code doStart} 第 176 行 bind 处间歇失败。
 * 这里用真实套接字复现该竞态：先由一个「旧进程」占住端口，再让 server 在后台线程里
 * start，中途释放占用，断言它最终能绑定成功。修复点在 {@link PortBindRetry}
 * （由 {@code AbstractServer.start()} 统一套上，FTP / FTPS / 其余 45 个 doStart 都受益），
 * 因此这些用例放在这里只为验证 FTPS 这条真实链路确实走到了该策略。</p>
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
            // 这里要的是「首次 bind 失败就抛」，而不是「等满预算再抛」：
            // 端口被明确长期占用属于配置错误，应当立刻暴露，而不是白等 30s。
            // 重试能力由下面的 portRace* 用例单独覆盖，两者互不替代。
            server.setBindRetryPolicy(PortBindRetry.Policy.noRetry());
            assertThrows(RuntimeException.class, server::start,
                    "端口被占用时必须抛错，让调用方把节点标成 ERROR，而不是报成功却监听在别处");
        }
    }

    @Test
    @DisplayName("FTP 应答必须以 CRLF 结束（RFC 959）；LF-only 会让严格客户端读到超时")
    void repliesUseCrlfTermination() throws Exception {
        int port = freePort();
        FtpServer server = new FtpServer(setting(port));
        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3_000);
                InputStream in = socket.getInputStream();

                String banner = readReplyLine(in);
                assertTrue(banner.endsWith("\r\n"),
                        "banner 必须以 CRLF 结束，实际: " + escapeLine(banner));

                OutputStream out = socket.getOutputStream();
                out.write("USER anonymous\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String login = readReplyLine(in);
                assertTrue(login.startsWith("230"), "匿名登录应回 230，实际: " + escapeLine(login));
                assertTrue(login.endsWith("\r\n"),
                        "登录应答必须以 CRLF 结束，实际: " + escapeLine(login));
            }
        } finally {
            server.stop();
        }
    }

    /**
     * 逐字节读取一行应答原文（读到 {@code \n} 止，不规整行结束符）。
     *
     * <p>故意不裁剪 {@code \r}：本用例要断言的正是「行尾必须带 {@code \r}」，
     * 一旦读取时顺手剥掉，LF-only 的缺陷就会测不出来。</p>
     *
     * @param in 输入流
     * @return 原始行文本
     * @throws IOException 读取失败
     */
    private static String readReplyLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            if (b == '\n') {
                break;
            }
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    /**
     * 行文本转可视化（CR/LF 显式可见，失败消息能直接看出拿到了什么）。
     *
     * @param line 原始行
     * @return 可视化文本
     */
    private static String escapeLine(String line) {
        return line.replace("\r", "\\r").replace("\n", "\\n");
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

    // ---------- 重启时的端口竞争回归 ----------

    /**
     * 竞态用例用的重试策略：次数与预算都给足，单次退避压到 100ms，
     * 让用例稳定在 1 秒级结束，而不是真的睡满默认的 30s 预算。
     *
     * @return 快速重试策略
     */
    private static PortBindRetry.Policy quickRetryPolicy() {
        return new PortBindRetry.Policy(30, 100L, 200L, 20_000L);
    }

    @Test
    @DisplayName("端口在重试期间被释放时，FTPS 必须最终绑定成功而不是停在 ERROR")
    void ftpsRecoversWhenPortReleasedMidRetry() throws Exception {
        // 占用方用普通 ServerSocket 而不是另一个 FTP server：真正的阻塞者是
        // 「另一个还在活跃监听的进程」，用普通套接字就能精确复现，且不依赖被测类自身，
        // 免得把「谁该让谁」搞混
        ServerSocket occupied = new ServerSocket();
        occupied.setReuseAddress(true);
        occupied.bind(new InetSocketAddress("127.0.0.1", 0));
        int held = occupied.getLocalPort();

        FtpsServer target = new FtpsServer(setting(held));
        target.setBindRetryPolicy(quickRetryPolicy());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                target.start();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "ftps-port-race");

        try {
            starter.start();
            // 必须先睡够，让第一次 bind 真的撞上占用；否则本用例会退化成
            // 「端口本来就没被占」的空跑，也就无法证明修复起了作用
            Thread.sleep(300L);
            occupied.close();
            starter.join(30_000L);

            assertFalse(starter.isAlive(), "FTPS 启动线程不应仍在等待端口释放");
            assertNull(failure.get(), "端口已释放，FTPS 不该再因端口冲突失败：" + failure.get());
            assertTrue(target.isRunning(), "重试成功后必须处于运行态");
            assertTrue(isBound(held), "端口 " + held + " 应由 FTPS 实际监听");
        } finally {
            occupied.close();
            if (target.isRunning()) {
                target.stop();
            }
        }
    }

    @Test
    @DisplayName("FTP 同样具备该恢复能力（修复落在 AbstractServer，不是 FTP 专用补丁）")
    void ftpRecoversWhenPortReleasedMidRetry() throws Exception {
        ServerSocket occupied = new ServerSocket();
        occupied.setReuseAddress(true);
        occupied.bind(new InetSocketAddress("127.0.0.1", 0));
        int held = occupied.getLocalPort();

        FtpServer target = new FtpServer(setting(held));
        target.setBindRetryPolicy(new PortBindRetry.Policy(30, 100L, 200L, 20_000L));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                target.start();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "ftp-port-race");

        try {
            starter.start();
            Thread.sleep(300L);
            occupied.close();
            starter.join(30_000L);

            assertFalse(starter.isAlive(), "FTP 启动线程不应一直挂着");
            assertNull(failure.get(), "端口已释放，FTP 不该再因端口冲突失败：" + failure.get());
            assertTrue(target.isRunning(), "重试成功后必须处于运行态");
            assertTrue(isBound(held), "端口 " + held + " 应由 FTP 实际监听");
        } finally {
            occupied.close();
            if (target.isRunning()) {
                target.stop();
            }
        }
    }

    @Test
    @DisplayName("端口被长期占用时在预算内失败，且异常要写明端点与尝试次数")
    void permanentOccupationFailsWithinBudgetWithDiagnosticMessage() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(true);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            int taken = occupied.getLocalPort();

            FtpsServer server = new FtpsServer(setting(taken));
            server.setBindRetryPolicy(new PortBindRetry.Policy(2, 10L, 10L, 5_000L));

            long began = System.nanoTime();
            IllegalStateException thrown = assertThrows(IllegalStateException.class, server::start);
            long elapsedMs = (System.nanoTime() - began) / 1_000_000L;

            assertTrue(PortBindRetry.isBindConflict(thrown),
                    "对外抛的异常必须仍能追溯到 BindException，运维才判断得出是端口问题");
            assertTrue(thrown.getMessage().contains(String.valueOf(taken)),
                    "异常信息应含端口号 " + taken + "，实际=" + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("2"), "异常信息应含重试次数，实际=" + thrown.getMessage());
            assertTrue(elapsedMs < 10_000L,
                    "必须按预算及时失败，实测耗了 " + elapsedMs + "ms；不设上限会让启动无限期挂住");
        }
    }

    @Test
    @DisplayName("默认策略可读可改，传 null 恢复默认")
    void bindRetryPolicyIsReadableAndWritable() {
        FtpServer server = new FtpServer(setting(0));
        assertNotNull(server.getBindRetryPolicy(), "应默认启用重试，否则重启竞态会复现");
        assertTrue(server.getBindRetryPolicy().enabled(), "默认策略必须启用重试");

        server.setBindRetryPolicy(PortBindRetry.Policy.noRetry());
        assertFalse(server.getBindRetryPolicy().enabled(), "应能切成不重试");

        server.setBindRetryPolicy(null);
        assertTrue(server.getBindRetryPolicy().enabled(), "传 null 应恢复默认策略");
    }
}
