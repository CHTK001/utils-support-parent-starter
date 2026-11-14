// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.tcp;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ProtocolSniffingTcpServer} 对传输加密开关的口径：启动即拒绝，不静默明文。
 *
 * <p>嗅探必须先读明文头部前缀才能分流，加密后前缀不可读；与其“开关打开仍走明文”，
 * 不如在 bind 之前明确失败。同时回归明文链路仍能正常 accept + 分流 + 回写，
 * 避免“拒绝加密”被实现成“整个服务器都起不来”。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class ProtocolSniffingTcpServerEncryptTest {

    /**
     * 协议前缀。
     */
    private static final byte[] PREFIX = "AUTH|".getBytes(StandardCharsets.UTF_8);

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
     * 监听配置。
     *
     * @param port    端口
     * @param encrypt 是否开加密
     * @return 配置
     */
    private static ServerSetting setting(int port, boolean encrypt) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);
        setting.setEncrypt(encrypt);
        if (encrypt) {
            setting.setEncryptType("aes-gcm");
            setting.setEncryptKey("嗅探-口令");
        }
        return setting;
    }

    /**
     * 回显处理器：匹配 {@code AUTH|} 前缀，回写 {@code ok:} + 原报文。
     *
     * <p>处理器拿到的 {@link java.io.PushbackInputStream} 里已经回推了嗅探读到的头部；
     * 对这类流做带缓冲的读会继续向 socket 要数据直到填满，所以测试客户端必须半关闭
     * 写方向（表示请求已发完），处理器才能立刻拿到 EOF 后的完整报文。</p>
     */
    private static final ProtocolSniffHandler AUTH_ECHO = new ProtocolSniffHandler() {

        @Override
        public ProtocolType protocolType() {
            return ProtocolType.TCP;
        }

        @Override
        public boolean matches(byte[] head) {
            return startsWith(head, PREFIX);
        }

        @Override
        public boolean isPrefix(byte[] head) {
            int n = Math.min(head.length, PREFIX.length);
            for (int i = 0; i < n; i++) {
                if (head[i] != PREFIX[i]) {
                    return false;
                }
            }
            return head.length < PREFIX.length;
        }

        @Override
        public void handle(InputStream in, OutputStream out) throws Exception {
            byte[] buffer = new byte[256];
            int n = in.read(buffer);
            if (n <= 0) {
                return;
            }
            byte[] prefix = "ok:".getBytes(StandardCharsets.UTF_8);
            byte[] response = new byte[prefix.length + n];
            System.arraycopy(prefix, 0, response, 0, prefix.length);
            System.arraycopy(buffer, 0, response, prefix.length, n);
            out.write(response);
            out.flush();
        }

        private static boolean startsWith(byte[] head, byte[] prefix) {
            if (head.length < prefix.length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (head[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    };

    /**
     * 开加密必须在 bind 之前失败，不能起监听。
     *
     * @throws Exception 取端口失败
     */
    @Test
    @Timeout(60)
    @DisplayName("ProtocolSniffingTcpServer：开启传输加密启动即失败")
    void encryptRejectedAtStart() throws Exception {
        int port = freePort();
        ProtocolSniffingTcpServer server = new ProtocolSniffingTcpServer(setting(port, true))
                .register(AUTH_ECHO);
        assertThrows(IllegalStateException.class, server::start,
                "嗅探服务器不支持加密，应在启动时明确拒绝");
        // 拒绝后端口应立即可用（没有起监听）
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, probe.getLocalPort());
        }
    }

    /**
     * 未开加密时明文链路仍能 accept、按前缀分流并回写。
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(60)
    @DisplayName("ProtocolSniffingTcpServer：明文链路仍按前缀分流")
    void plaintextStillSniffs() throws Exception {
        int port = freePort();
        ProtocolSniffingTcpServer server = new ProtocolSniffingTcpServer(setting(port, false))
                .register(AUTH_ECHO);
        server.start();
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(10000);
            String payload = "AUTH|alice";
            client.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            // 请求已发完：半关闭写方向，处理器读到 EOF 即拿到完整报文
            client.shutdownOutput();

            byte[] expected = ("ok:" + payload).getBytes(StandardCharsets.UTF_8);
            byte[] buffer = new byte[expected.length];
            int read = 0;
            while (read < expected.length) {
                int n = client.getInputStream().read(buffer, read, expected.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            assertEquals(new String(expected, StandardCharsets.UTF_8),
                    new String(buffer, 0, read, StandardCharsets.UTF_8), "明文分流回显不符");
        } finally {
            server.stop();
        }
    }
}
