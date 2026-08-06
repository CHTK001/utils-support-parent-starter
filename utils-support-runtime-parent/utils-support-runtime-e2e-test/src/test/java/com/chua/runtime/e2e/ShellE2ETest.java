package com.chua.runtime.e2e;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.shell.TelnetServer;
import com.chua.runtime.shell.command.CommandRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Shell 端到端测试 — 验证 Telnet Shell 启动 + 命令注册 + tab 补全。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShellE2ETest {

    /**
     * Telnet 端口（避免与系统冲突）
     */
    private static final int TEST_PORT = 14567;

    /**
     * Shell 服务器
     */
    private TelnetServer server;

    /**
     * APM 启动器
     */
    private ApmBootstrap apm;

    @Before
    public void setup() throws IOException {
        apm = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
        apm.start();
        server = new TelnetServer(apm);
        server.start(TEST_PORT);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (apm != null) {
            apm.stop();
        }
    }

    /**
     * 测试 1: 命令注册表大小（应包含 help + 4 个默认 + 1 个 apm）。
     */
    @Test
    public void testCommandRegistrySize() {
        CommandRegistry registry = server.getRegistry();
        assertTrue("命令数应 >= 5, 实际=" + registry.size(), registry.size() >= 5);
    }

    /**
     * 测试 2: apm 命令已注册且可查找。
     */
    @Test
    public void testApmCommandRegistered() {
        CommandRegistry registry = server.getRegistry();
        assertNotNull("apm 命令应存在", registry.find("apm"));
    }

    /**
     * 测试 3: 顶层 tab 补全（"ap" -> "apm"）。
     */
    @Test
    public void testTopLevelCompletion() {
        CommandRegistry registry = server.getRegistry();
        List<String> completions = registry.complete("ap");
        assertTrue("应至少返回 1 个匹配项", completions.size() >= 1);
        assertTrue("应包含 apm", completions.contains("apm"));
    }

    /**
     * 测试 4: 别名补全（"hel" -> "help"）。
     */
    @Test
    public void testAliasCompletion() {
        CommandRegistry registry = server.getRegistry();
        List<String> completions = registry.complete("hel");
        assertTrue("应至少返回 1 个匹配项", completions.size() >= 1);
        assertTrue("应包含 help", completions.contains("help"));
    }

    /**
     * 测试 5: 空补全返回所有命令 + 别名。
     */
    @Test
    public void testEmptyCompletion() {
        CommandRegistry registry = server.getRegistry();
        List<String> completions = registry.complete("");
        // 包含所有主命令 + 别名
        assertTrue("空补全应至少返回 5 个（主命令）", completions.size() >= 5);
        assertTrue("应包含 help", completions.contains("help"));
        assertTrue("应包含 apm", completions.contains("apm"));
    }

    /**
     * 测试 6: 真实 Telnet 连接 — 发送 "help\n" 验证 banner 和命令输出。
     */
    @Test
    public void testRealTelnetConnection() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT);
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            // 等待 banner
            Thread.sleep(500);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int avail = in.available();
            while (avail > 0) {
                int n = in.read(buf, 0, Math.min(avail, buf.length));
                if (n < 0) break;
                sb.append(new String(buf, 0, n));
                avail = in.available();
            }
            String banner = sb.toString();
            assertTrue("Banner 应包含 'Chua Runtime Shell'", banner.contains("Chua Runtime Shell"));

            // 发送 help 命令
            out.write("help\n".getBytes());
            out.flush();
            Thread.sleep(500);

            sb.setLength(0);
            avail = in.available();
            while (avail > 0) {
                int n = in.read(buf, 0, Math.min(avail, buf.length));
                if (n < 0) break;
                sb.append(new String(buf, 0, n));
                avail = in.available();
            }
            String helpOut = sb.toString();
            assertTrue("help 输出应包含 '可用命令'", helpOut.contains("可用命令"));
            assertTrue("help 输出应包含 apm", helpOut.contains("apm"));

            // 退出
            out.write("exit\n".getBytes());
            out.flush();
        }
    }

    /**
     * 测试 7: 真实 Telnet — apm 命令输出。
     */
    @Test
    public void testRealApmCommand() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT);
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            // 等待 banner
            Thread.sleep(500);
            byte[] buf = new byte[4096];
            int avail;
            do {
                avail = in.available();
                if (avail > 0) in.read(buf, 0, Math.min(avail, buf.length));
            } while (avail > 0);

            // 发送 apm 命令
            out.write("apm\n".getBytes());
            out.flush();
            Thread.sleep(500);

            StringBuilder sb = new StringBuilder();
            avail = in.available();
            while (avail > 0) {
                int n = in.read(buf, 0, Math.min(avail, buf.length));
                if (n < 0) break;
                sb.append(new String(buf, 0, n));
                avail = in.available();
            }
            String apmOut = sb.toString();
            assertTrue("apm 输出应包含 'APM 处理器总览'", apmOut.contains("APM 处理器总览"));
            assertTrue("apm 输出应包含 log-handler", apmOut.contains("log-handler"));
            assertTrue("apm 输出应包含 trace-handler", apmOut.contains("trace-handler"));

            // 退出
            out.write("exit\n".getBytes());
            out.flush();
        }
    }

    /**
     * 测试 8: TelnetServer 启动后端口被占用。
     */
    @Test(expected = IOException.class)
    public void testPortAlreadyInUse() throws IOException {
        TelnetServer dup = new TelnetServer();
        try {
            dup.start(TEST_PORT);
            fail("应抛出 IOException（端口已被占用）");
        } finally {
            dup.stop();
        }
    }
}
