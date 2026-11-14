// [P3C A20 豁免] 测试代码，协议字面量与断言消息集中在此，属测试夹具常量。
package com.chua.common.support.network.ftp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FTPS 的加密必须真的落在链路上：控制连接与数据连接都要走 TLS。
 *
 * <p>原实现有两个「宣告了但没做到」的口子：</p>
 * <ul>
 *   <li>显式模式（{@code implicitSsl=false}）的 {@code AUTH TLS} 只回 {@code 234}
 *       就结束，控制连接仍是明文 —— 客户端按 RFC 4217 在同一个 socket 上发起握手时，
 *       服务端把 ClientHello 当命令行读，握手必然失败，显式 FTPS 实际不可用。</li>
 *   <li>数据连接（PASV）用的是裸 {@link ServerSocket}，隐式与显式模式下文件内容
 *       都是明文传输 —— 「FTPS 已启用」这句话对数据面是假的。</li>
 * </ul>
 *
 * <p>断言方式：客户端用真实 {@link SSLSocket}（trust-all 自签证书）完成握手，
 * 既断言握手成功与协商出的密码套件非空，也断言只有握手完成后才拿得到文件内容；
 * 反证用例用裸 socket 连 TLS 端口/数据端口，断言拿不到明文 banner 与文件标记。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
class FtpsTlsChannelTest {

    /**
     * 文件内容里的可搜索标记：只有真的读到文件才会出现
     */
    private static final String MARKER = "FTPS-PLAINTEXT-MARKER";

    /**
     * 待传文件内容（二进制传输，字节级比对）
     */
    private static final byte[] PAYLOAD =
            ("line-1 " + MARKER + "\r\nline-2\r\n").getBytes(StandardCharsets.UTF_8);

    /**
     * 断言不出现「未协商」的密码套件
     */
    private static final String NULL_CIPHER = "SSL_NULL_WITH_NULL_NULL";

    @TempDir
    Path home;

    private FtpsServer server;
    private SSLContext clientSsl;

    @BeforeEach
    void setUp() throws Exception {
        Files.write(home.resolve("hello.txt"), PAYLOAD);
        clientSsl = trustAllClientContext();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Test
    @DisplayName("隐式模式：控制连接与 PASV 数据连接都必须完成真实 TLS 握手才能取到文件")
    void implicitModeRequiresTlsOnControlAndDataChannel() throws Exception {
        int port = startServer(true);

        try (SSLSocket control = connectTls(port)) {
            String banner = readLine(control.getInputStream());
            assertTrue(banner.startsWith("220"), "隐式 FTPS 应在 TLS 内回 220，实际: " + escape(banner));
            assertNotEquals(NULL_CIPHER, control.getSession().getCipherSuite(),
                    "控制连接必须协商出真实密码套件");

            send(control, "USER anonymous");
            assertTrue(readLine(control.getInputStream()).startsWith("230"), "匿名登录应回 230");

            send(control, "PASV");
            int dataPort = parsePassivePort(readLine(control.getInputStream()));

            // 数据连接故意不预先握手：SSLSocket 在读第一字节时握手，正好与服务端
            // accept 后的 startHandshake 对齐，避免两边互等
            try (SSLSocket data = connectTlsDeferred(dataPort)) {
                send(control, "RETR hello.txt");
                String opening = readLine(control.getInputStream());
                assertTrue(opening.startsWith("200"), "服务端应回 200 表示开始传数据，实际: " + escape(opening));

                byte[] received = data.getInputStream().readAllBytes();
                assertArrayEquals(PAYLOAD, received,
                        "数据连接必须是 TLS：内容应能完整取回；若这里拿到 0 字节，说明握手被服务端拒绝");
                assertTrue(readLine(control.getInputStream()).startsWith("226"), "传输完成应回 226");
            }
        }
    }

    @Test
    @DisplayName("显式模式：AUTH TLS 必须把控制连接原地升级为 TLS，之后命令与数据都走加密")
    void explicitModeUpgradesSameSocketToTls() throws Exception {
        int port = startServer(false);

        try (Socket plain = new Socket("127.0.0.1", port)) {
            plain.setSoTimeout(5_000);
            String banner = readLine(plain.getInputStream());
            assertTrue(banner.startsWith("220"), "显式 FTPS 先回明文 220，实际: " + escape(banner));
            assertTrue(banner.contains("AUTH TLS"), "banner 应提示使用 AUTH TLS，实际: " + escape(banner));

            send(plain, "AUTH TLS");
            String ready = readLine(plain.getInputStream());
            assertTrue(ready.startsWith("234"), "AUTH TLS 应回 234，实际: " + escape(ready));

            // RFC 4217：在**同一个** TCP 连接上直接发起 TLS，服务端必须整条升级
            SSLSocket control = (SSLSocket) clientSsl.getSocketFactory()
                    .createSocket(plain, "127.0.0.1", port, true);
            control.setSoTimeout(5_000);
            control.startHandshake();
            assertNotEquals(NULL_CIPHER, control.getSession().getCipherSuite(),
                    "AUTH TLS 之后必须能完成真实握手（原先只回 234、连接仍是明文，握手必失败）");

            send(control, "USER anonymous");
            assertTrue(readLine(control.getInputStream()).startsWith("230"), "TLS 内匿名登录应回 230");

            // 真实 FTPS 客户端（curl/lftp）在 AUTH TLS 之后固定发的两条
            send(control, "PBSZ 0");
            assertTrue(readLine(control.getInputStream()).startsWith("200"), "PBSZ 0 应被接受");
            send(control, "PROT P");
            assertTrue(readLine(control.getInputStream()).startsWith("200"), "PROT P 应被接受");

            send(control, "PASV");
            int dataPort = parsePassivePort(readLine(control.getInputStream()));
            try (SSLSocket data = connectTlsDeferred(dataPort)) {
                send(control, "RETR hello.txt");
                assertTrue(readLine(control.getInputStream()).startsWith("200"), "应开始传数据");
                assertArrayEquals(PAYLOAD, data.getInputStream().readAllBytes(),
                        "显式模式下数据连接同样必须 TLS 且内容完整");
                assertTrue(readLine(control.getInputStream()).startsWith("226"), "传输完成应回 226");
            }
        }
    }

    @Test
    @DisplayName("反证：明文数据连接拿不到文件内容（数据面确实被 TLS 保护）")
    void plaintextDataConnectionCannotFetchFile() throws Exception {
        int port = startServer(true);

        try (SSLSocket control = connectTls(port)) {
            assertTrue(readLine(control.getInputStream()).startsWith("220"), "隐式 FTPS 应回 220");
            send(control, "USER anonymous");
            assertTrue(readLine(control.getInputStream()).startsWith("230"), "匿名登录应回 230");
            send(control, "PASV");
            int dataPort = parsePassivePort(readLine(control.getInputStream()));

            try (Socket plainData = new Socket("127.0.0.1", dataPort)) {
                plainData.setSoTimeout(3_000);
                send(control, "RETR hello.txt");
                assertTrue(readLine(control.getInputStream()).startsWith("200"), "应开始传数据");

                String received = new String(readUntilEofOrTimeout(plainData), StandardCharsets.ISO_8859_1);
                assertFalse(received.contains(MARKER),
                        "明文数据连接读到了文件内容，说明 FTPS 的数据面其实没有加密: " + escape(received));
            }
        }
    }

    @Test
    @DisplayName("反证：隐式 TLS 端口不回明文 banner（端口真的在做 TLS，而不是明文伪装）")
    void implicitPortDoesNotSpeakPlaintext() throws Exception {
        int port = startServer(true);

        try (Socket plain = new Socket("127.0.0.1", port)) {
            plain.setSoTimeout(2_500);
            String received = new String(readUntilEofOrTimeout(plain), StandardCharsets.ISO_8859_1);
            assertFalse(received.startsWith("220"),
                    "隐式 TLS 端口不应直接回明文 banner，实际: " + escape(received));
        }
    }

    // ---------- 夹具 ----------

    private int startServer(boolean implicitSsl) throws IOException {
        int port = freePort();
        FtpConfig config = FtpConfig.builder()
                .host("127.0.0.1")
                .controlPort(port)
                .homeDirectory(home.toFile())
                .sslEnabled(true)
                .selfSignedAuto(true)
                .controlTimeout(10)
                // 明文数据连接的反证用例里，服务端 startHandshake 会在该超时后失败
                .dataTimeout(2)
                .build();
        server = new FtpsServer(config, implicitSsl);
        server.start();
        return port;
    }

    private static SSLContext trustAllClientContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 自签证书：本用例只验证链路加密，不验证证书链
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 同上
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        return context;
    }

    /**
     * 连接并立即完成握手（控制连接用）。
     */
    private SSLSocket connectTls(int port) throws IOException {
        SSLSocket socket = (SSLSocket) clientSsl.getSocketFactory().createSocket();
        socket.setUseClientMode(true);
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(5_000);
        socket.startHandshake();
        return socket;
    }

    /**
     * 连接但不预握手（数据连接用）：由第一次读触发，避免与服务端 accept 顺序互等。
     */
    private SSLSocket connectTlsDeferred(int port) throws IOException {
        SSLSocket socket = (SSLSocket) clientSsl.getSocketFactory().createSocket();
        socket.setUseClientMode(true);
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static void send(Socket socket, String command) throws IOException {
        send(socket.getOutputStream(), command);
    }

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return line.toString();
    }

    private static int parsePassivePort(String reply) {
        int open = reply.indexOf('(');
        int close = reply.indexOf(')');
        assertTrue(open >= 0 && close > open, "PASV 应答格式非法: " + escape(reply));
        String[] parts = reply.substring(open + 1, close).split(",");
        assertTrue(parts.length == 6, "PASV 应答应含 6 个数字: " + escape(reply));
        return Integer.parseInt(parts[4].trim()) * 256 + Integer.parseInt(parts[5].trim());
    }

    private static byte[] readUntilEofOrTimeout(Socket socket) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = socket.getInputStream()) {
            byte[] chunk = new byte[512];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
        } catch (SocketTimeoutException e) {
            // 服务端在等 ClientHello，客户端什么都没发：读超时即为预期
        } catch (IOException e) {
            // 服务端握手中途关连接也属预期
        }
        return buffer.toByteArray();
    }

    private static String escape(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
