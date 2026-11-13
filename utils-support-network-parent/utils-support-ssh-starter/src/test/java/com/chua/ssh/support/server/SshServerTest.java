// [P3C A20 豁免] 测试断言与协议常量含魔法值场景，豁免。
package com.chua.ssh.support.server;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SshServer} 的真实链路回归（此前零用例）。
 *
 * <p>服务端用 Apache MINA SSHD 起交互式 Shell：每个命令行交给
 * {@code ServerHandler} 处理，响应体写回客户端。本测试用 <b>MINA 官方客户端</b>
 * 完成握手、密码认证、开 shell 通道、发命令、读响应，验证整条链路。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class SshServerTest {

    /**
     * 认证密码。
     */
    private static final String PASSWORD = "secret";

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
     * 启动带命令的服务器。
     *
     * @param port 端口
     * @return 服务器
     */
    private static SshServer server(int port) {
        SshServer server = SshServer.builder()
                .host("127.0.0.1")
                .port(port)
                .password(PASSWORD)
                .build();
        server.registerCommand("hello", (request, response) -> {
            response.setBody("hi-from-ssh");
            response.end();
        });
        return server;
    }

    /**
     * 建连并完成密码认证。
     *
     * @param client   客户端
     * @param port     端口
     * @param password 密码
     * @return 会话
     * @throws Exception 连接/认证失败
     */
    private static ClientSession connect(SshClient client, int port, String password) throws Exception {
        ClientSession session = client.connect("tester", "127.0.0.1", port)
                .verify(15, TimeUnit.SECONDS)
                .getSession();
        session.addPasswordIdentity(password);
        session.auth().verify(15, TimeUnit.SECONDS);
        return session;
    }

    /**
     * 等待缓冲里出现指定文本。
     *
     * @param buffer  输出缓冲
     * @param expect  期望文本
     * @param timeout 超时毫秒
     * @return 是否出现
     * @throws InterruptedException 等待被中断
     */
    private static boolean awaitContains(StringBuffer buffer, String expect, long timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            if (buffer.indexOf(expect) >= 0) {
                return true;
            }
            Thread.sleep(50L);
        }
        return buffer.indexOf(expect) >= 0;
    }

    /**
     * 启动 shell 输出收集线程。
     *
     * @param in     服务端 stdout
     * @param buffer 收集缓冲
     * @return 读取线程
     */
    private static Thread collect(InputStream in, StringBuffer buffer) {
        return Thread.ofVirtual().start(() -> {
            byte[] chunk = new byte[512];
            try {
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // 通道关闭属正常收尾
            }
        });
    }

    /**
     * 握手 + 密码认证 + shell 命令派发 + 响应回传。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("SshServer：认证后 shell 命令派发并回传")
    void shellCommandDispatches() throws Exception {
        int port = freePort();
        SshServer server = server(port);
        server.start();
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try {
            ClientSession session = connect(client, port, PASSWORD);
            try (ClientChannel channel = session.createShellChannel()) {
                channel.open().verify(15, TimeUnit.SECONDS);
                StringBuffer output = new StringBuffer();
                Thread reader = collect(channel.getInvertedOut(), output);

                OutputStream stdin = channel.getInvertedIn();
                stdin.write("hello\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                assertTrue(awaitContains(output, "hi-from-ssh", 15_000L),
                        "命令响应未回传，实际输出: " + output);

                stdin.write("exit\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                reader.interrupt();
            }
        } finally {
            client.stop();
            server.stop();
        }
    }

    /**
     * help 必须列出内置命令说明。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("SshServer：help 输出内置命令")
    void helpListsCommands() throws Exception {
        int port = freePort();
        SshServer server = server(port);
        server.start();
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try {
            ClientSession session = connect(client, port, PASSWORD);
            try (ClientChannel channel = session.createShellChannel()) {
                channel.open().verify(15, TimeUnit.SECONDS);
                StringBuffer output = new StringBuffer();
                Thread reader = collect(channel.getInvertedOut(), output);

                OutputStream stdin = channel.getInvertedIn();
                stdin.write("help\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                assertTrue(awaitContains(output, "Available commands", 15_000L),
                        "help 未返回命令列表，实际输出: " + output);

                stdin.write("exit\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                reader.interrupt();
            }
        } finally {
            client.stop();
            server.stop();
        }
    }

    /**
     * 错误密码必须被拒绝。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("SshServer：错误密码被拒绝")
    void rejectsWrongPassword() throws Exception {
        int port = freePort();
        SshServer server = server(port);
        server.start();
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try {
            assertThrows(Exception.class, () -> connect(client, port, "wrong-password"),
                    "错误密码不应通过认证");
        } finally {
            client.stop();
            server.stop();
        }
    }

    /**
     * 未启动时连接必须快速失败，不能挂起。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(90)
    @DisplayName("SshServer：端口关闭时连接快速失败")
    void failsFastWhenServerStopped() throws Exception {
        int port = freePort();
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try {
            assertThrows(Exception.class, () -> client.connect("tester", "127.0.0.1", port)
                            .verify(5, TimeUnit.SECONDS),
                    "服务端未启动时连接应失败");
        } finally {
            client.stop();
        }
    }
}
