package com.chua.ssh.support.client;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * SSH 链式客户端，基于 Apache MINA SSHD 3.x。
 *
 * <pre>{@code
 * SshClient ssh = SshClient.builder()
 *     .host("192.168.1.100").port(22)
 *     .username("root").password("pass")
 *     .build();
 *
 * // 执行命令
 * String result = ssh.exec().command("ls -la").executeAndGetOutput();
 *
 * // 交互式 Shell
 * ssh.shell().connect().send("ls -la").readAll();
 *
 * // 正向隧道（本地端口转发）: 本地 8080 → 远程 127.0.0.1:80
 * ssh.connect().forward().local(8080, "127.0.0.1", 80).start();
 *
 * // 反向隧道（远程端口转发）: 远程 9090 → 本地 127.0.0.1:3000
 * ssh.connect().forward().remote(9090, "127.0.0.1", 3000).start();
 *
 * // 动态隧道（SOCKS5 代理）
 * ssh.connect().forward().dynamic(1080).start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Slf4j
public class SshClient implements AutoCloseable {

    /**
     * 主机地址
     */
    private final String host;
    /**
     * 端口号
     */
    private final int port;
    /**
     * 登录用户名
     */
    private final String username;
    /**
     * 登录密码
     */
    private final String password;
    /**
     * private Key Path
     */
    private final String privateKeyPath;
    /**
     * 连接超时时间（毫秒）
     */
    private final int connectTimeout;
    /**
     * 会话超时时间
     */
    private final int sessionTimeout;

    /** exec 通道打开超时（秒） */
    private static final long EXEC_OPEN_TIMEOUT_SECONDS = 10L;
    /** exec 通道退出码等待（毫秒） */
    private static final long EXEC_EXIT_WAIT_MILLIS = 30_000L;

    /**
     * ssh Client
     */
    private org.apache.sshd.client.SshClient sshClient;
    /**
     * 会话对象
     */
    private ClientSession session;

    /**
     * 创建 SshClient 实例
     * @param b b
     */
    private SshClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.privateKeyPath = b.privateKeyPath;
        this.connectTimeout = b.connectTimeout;
        this.sessionTimeout = b.sessionTimeout;
    }

    // ==================== 工厂方法 ====================

    /** Builder */
    public static Builder builder() { return new Builder(); }

    /** 创建 */
    public static SshClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    // ==================== 连接管理 ====================

    /** 连接 */
    public SshClient connect() {
        try {
            sshClient = org.apache.sshd.client.SshClient.setUpDefaultClient();
            sshClient.start();

            session = sshClient.connect(username, host, port)
                    .verify(connectTimeout, TimeUnit.SECONDS)
                    .getSession();

            if (password != null && !password.isEmpty()) {
                session.addPasswordIdentity(password);
            }
            if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
                var provider = new org.apache.sshd.common.keyprovider.FileKeyPairProvider(
                        java.nio.file.Path.of(privateKeyPath));
                for (java.security.KeyPair kp : provider.loadKeys(session)) {
                    session.addPublicKeyIdentity(kp);
                }
            }

            session.auth().verify(sessionTimeout, TimeUnit.SECONDS);
            log.info("SSH 连接成功: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            throw new SshClientException("SSH 连接失败: " + host + ":" + port, e);
        }
        return this;
    }

    /** 断开 */
    public void disconnect() {
        try {
            if (session != null) {
                session.close();
            }
            if (sshClient != null) {
                sshClient.stop();
            }
            log.info("SSH 断开: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            log.warn("SSH 断开异常: {}", e.getMessage());
        }
    }

    @Override
    /** 关闭 */
    public void close() { disconnect(); }

    // ==================== 操作入口 ====================

    /**
     * 执行命令
     */
    public ExecOperation exec() { return new ExecOperation(this); }

    /**
     * 交互式 Shell（同步读取全部输出）
     */
    public ShellOperation shell() { return new ShellOperation(this); }

    /**
     * PTY 交互式终端（实时输入输出）
     */
    public TerminalOperation terminal() { return new TerminalOperation(this); }

    /**
     * 隧道操作（正向/反向/动态）
     */
    public ForwardOperation forward() { return new ForwardOperation(this); }

    ClientSession getSession() { return session; }

    // ==================== ExecOperation ====================

    @Getter
    public static class ExecOperation {
        /**
         * 客户端实例
         */
        private final SshClient client;
        /**
         * command
         */
        private String command;

        ExecOperation(SshClient client) { this.client = client; }

        /** Command */
        public ExecOperation command(String cmd) { this.command = cmd; return this; }

        /** 执行 */
        public ExecResult execute() {
            try {
                var channel = client.getSession().createExecChannel(command);
                // MINA SSHD 标准用法: 先绑定输出流再打开通道,
                // exec 通道的 inverted 流在部分版本下不可用, 统一使用显式流收集输出
                var stdoutBuf = new java.io.ByteArrayOutputStream();
                var stderrBuf = new java.io.ByteArrayOutputStream();
                channel.setOut(stdoutBuf);
                channel.setErr(stderrBuf);

                channel.open().verify(EXEC_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                // 等待远端命令退出以取得准确退出码
                channel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED),
                        EXEC_EXIT_WAIT_MILLIS);
                Integer status = channel.getExitStatus();
                int exitCode = status == null ? -1 : status;
                channel.close();
                return new ExecResult(exitCode, stdoutBuf.toString(StandardCharsets.UTF_8),
                        stderrBuf.toString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new SshClientException("SSH 命令执行失败: " + command, e);
            }
        }

        /** 执行And获取Output */
        public String executeAndGetOutput() { return execute().stdout(); }
        /** 执行And获取ExitCode */
        public int executeAndGetExitCode() { return execute().exitCode(); }
    }

    // ==================== ShellOperation ====================

    @Getter
    public static class ShellOperation {
        /**
         * 客户端实例
         */
        private final SshClient client;
        /**
         * channel
         */
        private ChannelShell channel;

        ShellOperation(SshClient client) { this.client = client; }

        /** 连接 */
        public ShellOperation connect() {
            try {
                channel = client.getSession().createShellChannel();
                channel.setupSensibleDefaultPty();
                channel.open().verify(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new SshClientException("Shell 连接失败", e);
            }
            return this;
        }

        /** 发送 */
        public ShellOperation send(String command) throws IOException {
            channel.getInvertedIn().write((command + "\n").getBytes());
            channel.getInvertedIn().flush();
            return this;
        }

        /** 读取All */
        public String readAll() throws IOException {
            StringBuilder sb = new StringBuilder();
            try (InputStream in = channel.getInvertedOut()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    sb.append(new String(buf, 0, len));
                }
            }
            return sb.toString();
        }

        /** 关闭 */
        public void close() {
            try { channel.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== ForwardOperation（正向/反向/动态隧道） ====================

    @Getter
    public static class ForwardOperation {
        /**
         * 客户端实例
         */
        private final SshClient client;
        /**
         * local Port
         */
        private int localPort;
        /**
         * remote Host
         */
        private String remoteHost;
        /**
         * remote Port
         */
        private int remotePort;
        /**
         * dynamic
         */
        private boolean dynamic;
        /**
         * is Remote
         */
        private boolean isRemote;
        /**
         * bind Address
         */
        private String bindAddress = "127.0.0.1";
        /**
         * actual Port
         */
        private int actualPort = -1;

        ForwardOperation(SshClient client) { this.client = client; }

        /**
         * 正向隧道（本地端口转发）
         * <p>将本地端口的流量转发到远程主机端口。</p>
         */
        public ForwardOperation local(int localPort, String remoteHost, int remotePort) {
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.dynamic = false;
            this.isRemote = false;
            return this;
        }

        /**
         * 反向隧道（远程端口转发）
         * <p>将远程端口的流量转发到本地主机端口。适用于将内网服务暴露到外网。</p>
         */
        public ForwardOperation remote(int remotePort, String localHost, int localPort) {
            this.localPort = localPort;
            this.remoteHost = localHost;
            this.remotePort = remotePort;
            this.dynamic = false;
            this.isRemote = true;
            return this;
        }

        /**
         * 动态隧道（SOCKS5 代理）
         * <p>将本地端口作为 SOCKS5 代理，所有通过该端口的连接都经由 SSH 转发。</p>
         */
        public ForwardOperation dynamic(int localPort) {
            this.localPort = localPort;
            this.dynamic = true;
            this.isRemote = false;
            return this;
        }

        /** 绑定Address */
        public ForwardOperation bindAddress(String address) {
            this.bindAddress = address;
            return this;
        }

        /** 开始 */
        public AutoCloseable start() {
            try {
                if (dynamic) {
                    return startDynamic();
                }
                if (isRemote) {
                    return startRemote();
                }
                return startLocal();
            } catch (Exception e) {
                throw new SshClientException("隧道启动失败", e);
            }
        }

        /** 开始Local */
        private AutoCloseable startLocal() throws IOException {
            SshdSocketAddress bindAddr = new SshdSocketAddress(bindAddress, localPort);
            SshdSocketAddress remoteAddr = new SshdSocketAddress(remoteHost, remotePort);
            AutoCloseable tracker = client.getSession().createLocalPortForwardingTracker(bindAddr, remoteAddr);
            this.actualPort = localPort;
            log.info("正向隧道启动: {}:{} → {}:{}", bindAddress, localPort, remoteHost, remotePort);
            return tracker;
        }

        /** 开始Remote */
        private AutoCloseable startRemote() throws IOException {
            SshdSocketAddress remoteAddr = new SshdSocketAddress(bindAddress, remotePort);
            SshdSocketAddress localAddr = new SshdSocketAddress(remoteHost, localPort);
            AutoCloseable tracker = client.getSession().createRemotePortForwardingTracker(remoteAddr, localAddr);
            this.actualPort = localPort;
            log.info("反向隧道启动: 远程{}:{} → 本地{}:{}", bindAddress, remotePort, remoteHost, localPort);
            return tracker;
        }

        /** 开始Dynamic */
        private AutoCloseable startDynamic() throws IOException {
            SshdSocketAddress bindAddr = new SshdSocketAddress(bindAddress, localPort);
            AutoCloseable tracker = client.getSession().createDynamicPortForwardingTracker(bindAddr);
            this.actualPort = localPort;
            log.info("动态隧道(SOCKS5)启动: {}:{}", bindAddress, localPort);
            return tracker;
        }
    }

    // ==================== TunnelDefinition（隧道定义） ====================

    @Getter
    public static class TunnelDefinition {
        public enum Type { LOCAL, REMOTE, DYNAMIC }

        /**
         * 类型
         */
        private final Type type;
        /**
         * local Port
         */
        private final int localPort;
        /**
         * remote Host
         */
        private final String remoteHost;
        /**
         * remote Port
         */
        private final int remotePort;

        /**
         * 创建 TunnelDefinition 实例
         * @param type type
         * @param int int
         * @param String String
         * @param int int
         */
        private TunnelDefinition(Type type, int localPort, String remoteHost, int remotePort) {
            this.type = type;
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
        }

        /**
         * 正向隧道: 本地 localPort → 远程 remoteHost:remotePort
         */
        public static TunnelDefinition local(int localPort, String remoteHost, int remotePort) {
            return new TunnelDefinition(Type.LOCAL, localPort, remoteHost, remotePort);
        }

        /**
         * 反向隧道: 远程 remotePort → 本地 remoteHost:localPort
         */
        public static TunnelDefinition remote(int remotePort, String localHost, int localPort) {
            return new TunnelDefinition(Type.REMOTE, localPort, localHost, remotePort);
        }

        /**
         * 动态隧道: 本地 SOCKS5 代理
         */
        public static TunnelDefinition dynamic(int localPort) {
            return new TunnelDefinition(Type.DYNAMIC, localPort, null, 0);
        }
    }

    // ==================== TerminalOperation（PTY 实时终端） ====================

    @Getter
    public static class TerminalOperation {
        /**
         * 客户端实例
         */
        private final SshClient client;
        /**
         * pty
         */
        private boolean pty = true;
        /**
         * width
         */
        private int width = 80;
        /**
         * height
         */
        private int height = 24;
        /**
         * channel
         */
        private ChannelShell channel;
        /**
         * input Stream
         */
        private InputStream inputStream;
        /**
         * output Stream
         */
        private OutputStream outputStream;
        /**
         * 是否已连接
         */
        private volatile boolean connected = false;
        /**
         * output Callback
         */
        private Consumer<String> outputCallback;
        /**
         * close Callback
         */
        private Runnable closeCallback;
        /**
         * reader Thread
         */
        private Thread readerThread;
        /**
         * output Buffer
         */
        private final StringBuilder outputBuffer = new StringBuilder();
        /**
         * waiting For Prompt
         */
        private volatile boolean waitingForPrompt = false;
        /**
         * expected Prompt
         */
        private String expectedPrompt = "";
        /**
         * prompt Latch
         */
        private CountDownLatch promptLatch = new CountDownLatch(1);

        TerminalOperation(SshClient client) { this.client = client; }

        /** Pty */
        public TerminalOperation pty(boolean v) { this.pty = v; return this; }
        /** Width */
        public TerminalOperation width(int w) { this.width = w; return this; }
        /** Height */
        public TerminalOperation height(int h) { this.height = h; return this; }
        /** OnOutput */
        public TerminalOperation onOutput(Consumer<String> callback) { this.outputCallback = callback; return this; }
        /** On关闭 */
        public TerminalOperation onClose(Runnable callback) { this.closeCallback = callback; return this; }

        /** 连接 */
        public TerminalOperation connect() {
            try {
                PtyChannelConfiguration ptyConfig = new PtyChannelConfiguration();
                ptyConfig.setPtyType("vt100");
                ptyConfig.setPtyColumns(width);
                ptyConfig.setPtyLines(height);

                channel = client.getSession().createShellChannel(ptyConfig, null);
                channel.setUsePty(pty);
                channel.open().verify(30, TimeUnit.SECONDS);

                inputStream = channel.getInvertedOut();
                outputStream = channel.getInvertedIn();
                connected = true;
                startReaderThread();
                log.info("PTY 终端连接成功: {}x{}", width, height);
            } catch (Exception e) {
                throw new SshClientException("PTY 连接失败", e);
            }
            return this;
        }

        /** 开始ReaderThread */
        private void startReaderThread() {
            readerThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (connected) {
                    try {
                        if (inputStream.available() > 0) {
                            int len = inputStream.read(buf);
                            if (len > 0) {
                                String data = new String(buf, 0, len);
                                synchronized (outputBuffer) { outputBuffer.append(data); }
                                if (outputCallback != null) {
                                    outputCallback.accept(data);
                                }
                                if (waitingForPrompt && outputBuffer.toString().contains(expectedPrompt)) {
                                    promptLatch.countDown();
                                }
                            }
                        } else {
                            Thread.sleep(10);
                        }
                    } catch (Exception e) {
                        if (connected) {
                            log.debug("终端读取异常: {}", e.getMessage());
                        }
                    }
                }
                if (closeCallback != null) {
                    closeCallback.run();
                }
            }, "ssh-terminal-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        /** 发送 */
        public TerminalOperation send(String command) throws IOException {
            if (!connected) {
                throw new SshClientException("终端未连接", null);
            }
            outputStream.write((command + "\n").getBytes());
            outputStream.flush();
            return this;
        }

        /** 发送Raw */
        public TerminalOperation sendRaw(String input) throws IOException {
            if (!connected) {
                throw new SshClientException("终端未连接", null);
            }
            outputStream.write(input.getBytes());
            outputStream.flush();
            return this;
        }

        /** 发送Key */
        public TerminalOperation sendKey(String key) throws IOException {
            if (!connected) {
                throw new SshClientException("终端未连接", null);
            }
            switch (key.toLowerCase()) {
                case "ctrl+c" -> outputStream.write(new byte[]{3});
                case "ctrl+d" -> outputStream.write(new byte[]{4});
                case "ctrl+z" -> outputStream.write(new byte[]{26});
                case "enter" -> outputStream.write(new byte[]{13});
                case "tab" -> outputStream.write(new byte[]{9});
                default -> outputStream.write(key.getBytes());
            }
            outputStream.flush();
            return this;
        }

        /** 读取Buffer */
        public String readBuffer() {
            synchronized (outputBuffer) {
                String data = outputBuffer.toString();
                outputBuffer.setLength(0);
                return data;
            }
        }

        /** 读取Until */
        public String readUntil(String expected, long timeoutMs) throws InterruptedException {
            waitingForPrompt = true;
            expectedPrompt = expected;
            promptLatch = new CountDownLatch(1);
            boolean found = promptLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            waitingForPrompt = false;
            String output = readBuffer();
            if (!found) {
                log.warn("等待超时: {}", expected);
            }
            return output;
        }

        /** 关闭 */
        public void close() {
            connected = false;
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (Exception e) {
                log.debug("PTY 关闭异常: {}", e.getMessage());
            }
            if (closeCallback != null) {
                closeCallback.run();
            }
        }

        /** 是否Connected */
        public boolean isConnected() { return connected; }
    }

    // ==================== ExecResult ====================

    /** ExecResult */
    public record ExecResult(int exitCode, String stdout, String stderr) {
        /** 获取Output */
        public String getOutput() {
            return stdout;
        }
    }

    // ==================== Builder ====================

    public static class Builder {
        /**
         * 主机地址
         */
        private String host;
        /**
         * 端口号
         */
        private int port = 22;
        /**
         * 登录用户名
         */
        private String username;
        /**
         * 登录密码
         */
        private String password;
        /**
         * private Key Path
         */
        private String privateKeyPath;
        /**
         * 连接超时时间（毫秒）
         */
        private int connectTimeout = 30;
        /**
         * 会话超时时间
         */
        private int sessionTimeout = 30;

        /** Host */
        public Builder host(String h) { this.host = h; return this; }
        /** Port */
        public Builder port(int p) { this.port = p; return this; }
        /** Username */
        public Builder username(String u) { this.username = u; return this; }
        /** Password */
        public Builder password(String p) { this.password = p; return this; }
        /** PrivateKey */
        public Builder privateKey(String path) { this.privateKeyPath = path; return this; }
        /** 连接Timeout */
        public Builder connectTimeout(int t) { this.connectTimeout = t; return this; }
        /** SessionTimeout */
        public Builder sessionTimeout(int t) { this.sessionTimeout = t; return this; }

        /** 构建 */
        public SshClient build() {
            if (host == null) {
                throw new IllegalArgumentException("host 不能为空");
            }
            if (username == null) {
                throw new IllegalArgumentException("username 不能为空");
            }
            return new SshClient(this);
        }
    }

    public static class SshClientException extends RuntimeException {
        /**
         * 创建 SshClientException 实例
         * @param msg msg
         * @param Throwable Throwable
         */
        public SshClientException(String msg, Throwable cause) { super(msg, cause); }
    }
}
