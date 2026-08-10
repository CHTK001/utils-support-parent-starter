package com.chua.gateway.server.bridge;

import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;

/**
 * RDP / VNC / SSH 协议桥接器（经 guacd 子进程）。
 *
 * <p>浏览器侧使用 {@code @guacamole/client}（HTML5 client）。
 * 连接流程：浏览器 → WS frame ↔ 桥接器 ↔ guacd TCP :4822 ↔ guacd ↔ RDP/VNC/SSH 服务器。</p>
 *
 * <p>本类负责：
 *   <ol>
 *     <li>{@link #connect()} 建立到 guacd :4822 的 TCP socket</li>
 *     <li>{@link #writeSelectInstruction()} 发 Guacamole {@code select} 指令（协议 + host/port/user/pass）
 *         —— 必须由服务端发，否则浏览器侧无法开始会话</li>
 *     <li>后续 {@link #writeToRemote}/{@link #readFromRemote()} 透传客户端 ↔ guacd 帧</li>
 *   </ol>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GuacamoleBridge implements RemoteBridge {

    /**
     * 默认 guacd 端口（与 application.properties 中 gateway.guacd.port 默认值一致）
     */
    public static final int DEFAULT_GUACD_PORT = 4822;

    /**
     * guacd 读取超时（毫秒）
     */
    private static final int GUACD_READ_TIMEOUT_MS = 1000;

    /**
     * guacd "ready" 等待超时（毫秒）
     */
    private static final long GUACD_READY_TIMEOUT_MS = 5000L;

    /**
     * 底层连接
     */
    private final Connection connection;

    /**
     * guacd host（默认 127.0.0.1，本地子进程）
     */
    private final String guacdHost;

    /**
     * guacd port
     */
    private final int guacdPort;

    /**
     * 到 guacd 的 TCP socket
     */
    private volatile Socket socket;

    public GuacamoleBridge(Connection connection, String guacdHost, int guacdPort) {
        this.connection = connection;
        this.guacdHost = guacdHost;
        this.guacdPort = guacdPort;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    @Override
    public void connect() throws Exception {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        log.info("[gateway-server] Guacamole 连接: guacd={}:{} target={}:{}",
                guacdHost, guacdPort, connection.host(), connection.port());
        socket = new Socket(guacdHost, guacdPort);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(GUACD_READ_TIMEOUT_MS);
        log.info("[gateway-server] Guacamole socket 建立: target={}:{}", connection.host(), connection.port());
    }

    @Override
    public void disconnect() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception e) {
                log.warn("[gateway-server] Guacamole socket 关闭失败: {}", e.getMessage());
            }
        }
        socket = null;
    }

    @Override
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * 返回到 guacd 的 TCP socket（供 WS handler 透传）。
     *
     * @return Socket 实例，未连接返回 {@code null}
     */
    public Socket socket() {
        return socket;
    }

    /**
     * 向 guacd 发 Guacamole {@code select} 指令（必须在 {@link #connect()} 后立刻调用）。
     *
     * <p>指令格式：每个 element 为 {@code length.value,}，最末用 {@code ;}。
     * 本方法根据 {@link Connection#protocol()} 选择 rdp/vnc/ssh，并把 host/port/user/pass 一并发送。</p>
     *
     * <p>guacd 收到 select 后会回 {@code args.<connectId>.<protocol>.<width>.<height>.<dpi>...;}
     * 表示握手成功，可以进入数据模式。</p>
     *
     * @throws IOException 写入失败
     */
    public void writeSelectInstruction() throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        Connection c = connection;
        String protocol = c.protocol() == null ? "rdp" : c.protocol().toLowerCase();
        // guacd 只接受 rdp / vnc / ssh
        if (!"rdp".equals(protocol) && !"vnc".equals(protocol) && !"ssh".equals(protocol)) {
            log.warn("[gateway-server] Guacamole 未知协议: {}, 强制 vnc", protocol);
            protocol = "vnc";
        }
        String host = c.host() == null ? "" : c.host();
        String port = String.valueOf(c.port() <= 0 ? (protocol.equals("rdp") ? 3389 : protocol.equals("vnc") ? 5900 : 22) : c.port());
        String user = c.user() == null ? "" : c.user();
        String pass = c.password() == null ? "" : c.password();

        StringBuilder sb = new StringBuilder();
        appendElement(sb, "select");
        appendElement(sb, protocol);
        appendElement(sb, "host");
        appendElement(sb, host);
        appendElement(sb, "port");
        appendElement(sb, port);
        if (!user.isEmpty()) {
            appendElement(sb, "username");
            appendElement(sb, user);
        }
        if (!pass.isEmpty()) {
            appendElement(sb, "password");
            appendElement(sb, pass);
        }
        // 结束符（最后一个 element 后用 ;）
        sb.setCharAt(sb.length() - 1, ';');

        String inst = sb.toString();
        OutputStream out = socket.getOutputStream();
        out.write(inst.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.flush();
        log.info("[gateway-server] Guacamole select 指令已发: {}", inst);
    }

    /**
     * 把 {@code length.value,} 追加到 sb。
     */
    private static void appendElement(StringBuilder sb, String value) {
        sb.append(value.length()).append('.').append(value).append(',');
    }

    /**
     * 从 guacd 读取完整一行指令（以 {@code ;} 结尾）。
     *
     * @param timeoutMs 超时毫秒
     * @return 指令字符串（不含 {@code ;}），超时返回 null
     * @throws IOException 读取失败
     */
    public String readGuacdInstruction(long timeoutMs) throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        InputStream in = socket.getInputStream();
        socket.setSoTimeout((int) timeoutMs);
        StringBuilder sb = new StringBuilder();
        int prev = -1, cur;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int b;
            try {
                b = in.read();
            } catch (java.net.SocketTimeoutException ex) {
                break;
            }
            if (b == -1) {
                break;
            }
            if (b == ';') {
                break;
            }
            sb.append((char) b);
        }
        socket.setSoTimeout(GUACD_READ_TIMEOUT_MS);
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public void writeToRemote(byte[] bytes) throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        OutputStream out = socket.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    @Override
    public byte[] readFromRemote() throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        InputStream in = socket.getInputStream();
        byte[] buf = new byte[65536];
        int n = in.read(buf);
        if (n <= 0) {
            throw new IOException("guacd 服务器关闭连接");
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
}
