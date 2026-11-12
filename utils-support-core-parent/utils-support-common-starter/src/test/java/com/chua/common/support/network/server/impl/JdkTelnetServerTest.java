// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkTelnetServer} 的真实链路回归。
 *
 * <p>Telnet 是<b>带协议协商</b>的交互式服务（IAC 序列 + 命令行 + 命令分发），
 * 之前零覆盖。这里验最小闭环：连接后发一条命令，能收到该命令注册的输出。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class JdkTelnetServerTest {

    /**
     * 监听配置。
     *
     * @return 配置
     */
    private static ServerSetting setting() {
        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(0);
        setting.setAuto(false);
        return setting;
    }

    /**
     * 读取直到出现期望文本或超时。
     *
     * <p>Telnet 协商会在文本流里夹杂 IAC 字节，这里按 ISO-8859-1 累积，
     * 只做子串判断，不做协议解码。</p>
     *
     * @param in       输入流
     * @param expected 期望出现的文本
     * @param timeoutMs 超时（毫秒）
     * @return 累积到的全部文本
     * @throws Exception 读取失败
     */
    private static String readUntil(InputStream in, String expected, long timeoutMs) throws Exception {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[1024];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                builder.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
                if (builder.indexOf(expected) >= 0) {
                    return builder.toString();
                }
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
        }
        return builder.toString();
    }

    /**
     * 连接后执行一条注册命令，必须收到该命令的输出。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkTelnetServer：命令分发并回显结果")
    void dispatchesRegisteredCommand() throws Exception {
        JdkTelnetServer server = new JdkTelnetServer(setting());
        // 注意：help/whoami/time/echo/quit 是内置命令，doStart() 里才注册，
        // 用这些名字注册的用户命令会被内置实现覆盖。这里取一个非内置名。
        server.registerCommand("probe", (session, args) ->
                session.println("OK:" + String.join(",", args)));
        server.start();
        try {
            int port = server.getPort();
            assertTrue(port > 0, "服务器未绑定端口");
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(5000);
                // 先把协商阶段的字节读掉（IAC 序列），用短超时避免空等 5 秒
                client.setSoTimeout(300);
                readUntil(client.getInputStream(), "\u0000-never-", 300L);
                client.setSoTimeout(5000);

                OutputStream out = client.getOutputStream();
                out.write("probe hello\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String text = readUntil(client.getInputStream(), "OK:hello", 5000L);
                assertTrue(text.contains("OK:hello"),
                        "未收到命令输出，实际收到：" + text.replaceAll("[\\p{Cntrl}]", "."));
            }
        } finally {
            server.stop();
        }
    }

    /**
     * 未知命令不能把连接搞死：仍应能继续执行已注册命令。
     *
     * @throws Exception 套接字失败
     */
    @Test
    @Timeout(60)
    @DisplayName("JdkTelnetServer：未知命令后连接仍可用")
    void survivesUnknownCommand() throws Exception {
        JdkTelnetServer server = new JdkTelnetServer(setting());
        server.registerCommand("ping", (session, args) -> session.println("PONG"));
        server.start();
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            client.setSoTimeout(5000);
            client.setSoTimeout(300);
            readUntil(client.getInputStream(), "\u0000-never-", 300L);
            client.setSoTimeout(5000);

            OutputStream out = client.getOutputStream();
            out.write("nosuchcommand\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            out.write("ping\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String text = readUntil(client.getInputStream(), "PONG", 5000L);
            assertTrue(text.contains("PONG"),
                    "未知命令后连接不可用，实际收到：" + text.replaceAll("[\\p{Cntrl}]", "."));
        } finally {
            server.stop();
        }
    }
}
