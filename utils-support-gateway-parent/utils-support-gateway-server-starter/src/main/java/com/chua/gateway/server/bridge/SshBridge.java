package com.chua.gateway.server.bridge;

import com.chua.gateway.server.store.Connection;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * SSH 协议桥接器（主进程内，JSCH）。
 *
 * <p>浏览器侧使用 xterm.js WebSocket 客户端发送伪终端输入。
 * 协议栈：WebSocket ↔ ChannelShell (SSH) ↔ SSH Server (:22)。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SshBridge implements RemoteBridge {

    /**
     * SSH 默认端口
     */
    private static final int DEFAULT_SSH_PORT = 22;

    /**
     * SSH JSch 会话超时（毫秒）
     */
    private static final int SESSION_TIMEOUT_MS = 3_000;

    /**
     * 底层连接
     */
    private final Connection connection;

    /**
     * JSch session
     */
    private volatile Session session;

    /**
     * shell 通道
     */
    private volatile ChannelShell channel;

    public SshBridge(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    @Override
    public void connect() throws Exception {
        if (session != null && session.isConnected()) {
            return;
        }
        int port = connection.port() > 0 ? connection.port() : DEFAULT_SSH_PORT;
        String user = connection.user() == null ? "" : connection.user();
        String pass = connection.password() == null ? "" : connection.password();
        log.info("[gateway-server] SSH 连接: user={} target={}:{}", user, connection.host(), port);
        JSch jsch = new JSch();
        // If password looks like a PEM private key, use it for key auth
        if (pass.startsWith("-----BEGIN") && pass.contains("PRIVATE KEY-----")) {
            // Normalize line endings (JSON often sends \n as literal "\\n")
            String normalized = pass.replace("\\r", "").replace("\\n", "\n");
            byte[] privateKey = normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            log.info("[gateway-server] SSH 使用私钥认证, normalized size={} starts={}",
                privateKey.length, new String(privateKey, 0, Math.min(40, privateKey.length)));
            // Use byte[] overload of addIdentity (more lenient than file path)
            jsch.addIdentity("inline", privateKey, null, null);
        }
        session = jsch.getSession(user, connection.host(), port);
        if (!pass.startsWith("-----BEGIN")) {
            session.setPassword(pass);
        }
        session.setConfig("StrictHostKeyChecking", "no");
        try {
            session.connect(SESSION_TIMEOUT_MS);
        } catch (Exception e) {
            log.error("[gateway-server] SSH connect 失败: host={}:{} user={} err={} stack={}",
                connection.host(), port, user, e.getMessage(),
                java.util.Arrays.toString(e.getStackTrace()).replace(',', '\n'));
            throw e;
        }
        channel = (ChannelShell) session.openChannel("shell");
        channel.setPtyType("xterm");
        channel.setPtySize(120, 30, 480, 640);
        channel.connect(SESSION_TIMEOUT_MS);
        log.info("[gateway-server] SSH 连接 + shell 通道建立");
    }

    @Override
    public void disconnect() {
        if (channel != null && !channel.isClosed()) {
            channel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        channel = null;
        session = null;
    }

    @Override
    public boolean isConnected() {
        return session != null && session.isConnected() && channel != null && !channel.isClosed();
    }

    /**
     * 返回 SSH shell 通道（供 WS handler 读写）。
     *
     * @return ChannelShell 实例，未连接返回 {@code null}
     */
    public ChannelShell channel() {
        return channel;
    }

    @Override
    public void writeToRemote(byte[] bytes) throws IOException {
        Objects.requireNonNull(channel, "SSH shell 通道未开启");
        OutputStream out = channel.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    @Override
    public byte[] readFromRemote() throws IOException {
        Objects.requireNonNull(channel, "SSH shell 通道未开启");
        InputStream in = channel.getInputStream();
        byte[] buf = new byte[65536];
        int n = in.read(buf);
        if (n <= 0) {
            throw new IOException("SSH 服务器关闭连接");
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
}
