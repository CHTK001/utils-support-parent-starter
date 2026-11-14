// [P3C A20 豁免] A20/A30/A31: 测试断言与协议常量含魔法值场景，豁免。
package com.chua.common.support.network.sip;

import com.chua.common.support.utils.ThreadUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SIP 隧道数据面端到端用例：provider 暴露本地回声服务，visitor 映射本地端口，
 * 两侧客户端都开 {@code encrypt(true)}，字节经 SIP 隧道往返且内容一致。
 *
 * <p>此前只有 {@code TokenDerivedAesGcmInteropTest} 验证线格式（token 派生 + AES-GCM
 * 手工包流 ↔ {@code JdkTcpServer}），SIP 自身的信令 + 数据面完整链路没有用例。
 * 本用例补这一层：服务端 {@link SipServer} 开 {@code encrypt}、两个 {@link SipClient}
 * 也开 {@code encrypt}，真实 TCP 穿过隧道取回声。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
class SipTunnelEncryptE2ETest {

    /**
     * 共享令牌（服务端 encryptKey 与客户端派生源）。
     */
    private static final String TOKEN = "sip-e2e-token-月英";

    /**
     * 隧道服务名。
     */
    private static final String SERVICE = "echo";

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
     * 起一个本地回声服务端（隧道 provider 的目标）。
     *
     * <p>端口由 {@link ServerSocket#getLocalPort()} 取，避免先探测端口再绑定造成漂移。</p>
     *
     * @return 已监听的本地回声服务端
     * @throws IOException 绑定失败
     */
    private static ServerSocket startEchoServer() throws IOException {
        ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        ThreadUtils.startVirtualThread("sip-e2e-echo", () -> {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    ThreadUtils.startVirtualThread("sip-e2e-echo-conn", () -> {
                        try (Socket conn = socket) {
                            InputStream in = conn.getInputStream();
                            OutputStream out = conn.getOutputStream();
                            byte[] buffer = new byte[256];
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                out.write(buffer, 0, n);
                                out.flush();
                            }
                        } catch (IOException ignored) {
                            // 连接被关闭属于正常收尾
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
        return listener;
    }

    /**
     * 读满指定字节（超时由 socket soTimeout 兜底）。
     *
     * @param in     输入流
     * @param length 期望长度
     * @return 实读字节
     * @throws IOException 读取失败
     */
    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(data, offset, length - offset);
            if (n < 0) {
                break;
            }
            offset += n;
        }
        return offset == length ? data : java.util.Arrays.copyOf(data, offset);
    }

    /**
     * 加密隧道端到端：visitor 本地端口 → SIP 服务端 → provider → 本地回声服务。
     *
     * @throws Exception 启动或连接失败
     */
    @Test
    @Timeout(120)
    @DisplayName("SIP：两端 encrypt=true 时隧道数据端到端往返")
    void encryptedTunnelRoundTrip() throws Exception {
        int signalPort = freePort();
        SipConfig config = SipConfig.builder()
                .host("127.0.0.1")
                .port(signalPort)
                .token(TOKEN)
                .encrypt(true)
                .build();
        SipServer server = new SipServer(config);
        SipTunnelService service = null;
        SipTunnelPort tunnelPort = null;
        try (ServerSocket echoServer = startEchoServer()) {
            server.start();
            String url = "tcp://127.0.0.1:" + signalPort;

            SipClient provider = SipClient.tcp(url).token(TOKEN).encrypt(true);
            service = provider.service(SERVICE).to("127.0.0.1", echoServer.getLocalPort());

            int visitorPort = freePort();
            SipClient visitor = SipClient.tcp(url).token(TOKEN).encrypt(true);
            tunnelPort = visitor.tunnel(SERVICE).listen(visitorPort);

            byte[] payload = "sip-加密隧道-月英".getBytes(StandardCharsets.UTF_8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", visitorPort), 15000);
                socket.setSoTimeout(20000);
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
                byte[] echoed = readFully(socket.getInputStream(), payload.length);
                assertArrayEquals(payload, echoed, "隧道回声内容不符");
            }
        } finally {
            if (tunnelPort != null) {
                tunnelPort.stop();
            }
            if (service != null) {
                service.stop();
            }
            server.stop();
        }
    }

    /**
     * 对照用例：两端都不加密时同一链路仍可用，证明用例本身没被加密配置带偏。
     *
     * @throws Exception 启动或连接失败
     */
    @Test
    @Timeout(120)
    @DisplayName("SIP：两端 encrypt=false 时隧道数据同样往返（对照）")
    void plainTunnelRoundTrip() throws Exception {
        int signalPort = freePort();
        SipConfig config = SipConfig.builder()
                .host("127.0.0.1")
                .port(signalPort)
                .token(TOKEN)
                .encrypt(false)
                .build();
        SipServer server = new SipServer(config);
        SipTunnelService service = null;
        SipTunnelPort tunnelPort = null;
        try (ServerSocket echoServer = startEchoServer()) {
            server.start();
            String url = "tcp://127.0.0.1:" + signalPort;

            SipClient provider = SipClient.tcp(url).token(TOKEN).encrypt(false);
            service = provider.service(SERVICE).to("127.0.0.1", echoServer.getLocalPort());

            int visitorPort = freePort();
            SipClient visitor = SipClient.tcp(url).token(TOKEN).encrypt(false);
            tunnelPort = visitor.tunnel(SERVICE).listen(visitorPort);

            byte[] payload = "sip-明文隧道-月英".getBytes(StandardCharsets.UTF_8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", visitorPort), 15000);
                socket.setSoTimeout(20000);
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
                byte[] echoed = readFully(socket.getInputStream(), payload.length);
                assertArrayEquals(payload, echoed, "明文隧道回声内容不符");
            }
        } finally {
            if (tunnelPort != null) {
                tunnelPort.stop();
            }
            if (service != null) {
                service.stop();
            }
            server.stop();
        }
    }

    /**
     * 反证：服务端开加密、visitor 不开加密时，连信令认证都过不去，不会静默降级成明文。
     *
     * <p>若该用例反过来「连上了」，说明服务端 {@code encrypt=true} 只是摆设。</p>
     *
     * @throws Exception 启动失败
     */
    @Test
    @Timeout(120)
    @DisplayName("SIP：加密开关不一致时数据无法穿透（反证）")
    void mismatchedEncryptCannotTransfer() throws Exception {
        int signalPort = freePort();
        SipConfig config = SipConfig.builder()
                .host("127.0.0.1")
                .port(signalPort)
                .token(TOKEN)
                .encrypt(true)
                .build();
        SipServer server = new SipServer(config);
        SipTunnelService service = null;
        SipTunnelPort tunnelPort = null;
        try (ServerSocket echoServer = startEchoServer()) {
            server.start();
            String url = "tcp://127.0.0.1:" + signalPort;

            SipClient provider = SipClient.tcp(url).token(TOKEN).encrypt(true);
            service = provider.service(SERVICE).to("127.0.0.1", echoServer.getLocalPort());

            int visitorPort = freePort();
            // visitor 解析不出加密：连信令层都对不上，服务端解密失败直接拒绝，不会进入数据面
            SipClient visitor = SipClient.tcp(url).token(TOKEN);
            assertThrows(RuntimeException.class,
                    () -> visitor.tunnel(SERVICE).listen(visitorPort),
                    "加密开关不一致却完成了认证，说明 encrypt 未真正生效");
        } finally {
            if (tunnelPort != null) {
                tunnelPort.stop();
            }
            if (service != null) {
                service.stop();
            }
            server.stop();
        }
    }
}
