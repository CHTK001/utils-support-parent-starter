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
 * }</pre>.0.0.1", 3000).start();
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
      * 私募 键 路径
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

    /** 执行 通道打开超时（秒） */
    private static final long EXEC_OPEN_TIMEOUT_SECONDS = 10L;
    /** 执行 通道退出码等待（毫秒） */
    private static final long EXEC_EXIT_WAIT_MILLIS = 30_000L;

    /**
      * ssh 客户端
     */
    private org.apache.sshd.client.SshClient sshClient;
    /**
     * 会话对象
     */
    private ClientSession session;

    /**
      * 创建 ssh客户端 实例
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

    /**
     * 构建器
     *
     * @return 构建器的结果
     */
    public static Builder builder() { return new Builder(); }

    /**
     * 创建
     *
     * @param host 主机
     * @param username 用户名
     * @param password 密码
     * @return 创建的结果
     */
    public static SshClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    // ==================== 连接管理 ====================

    /**
     * 连接
     *
     * @return 连接的结果
     */
    public SshClient connect() {
        try {
            sshClient = org.apache.sshd.client.SshClient.setUpDefaultClient();
 // MINA 默认 远期过滤器 为 reject全部远期过滤器, 会静默拒绝服务端下发的
            // forwarded-tcpip 通道, 导致反向隧道(-R)注册成功但数据面不通; 此处显式放行
            sshClient.setForwardingFilter(org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE);
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
     * @return 执行的结果
     */
    public ExecOperation exec() { return new ExecOperation(this); }

    /**
     * 交互式 Shell（同步读取全部输出）
     * @return shell的结果
     */
    public ShellOperation shell() { return new ShellOperation(this); }

    /**
      * 伪终端 交互式终端（实时输入输出）
     * @return terminal的结果
     */
    public TerminalOperation terminal() { return new TerminalOperation(this); }

    /**
     * 隧道操作（正向/反向/动态）
     * @return 远期的结果
     */
    public ForwardOperation forward() { return new ForwardOperation(this); }

    /**
     * 是否已建立有效连接（会话存在且未关闭）
     *
     * @return true 表示已连接
     */
    public boolean isConnected() {
        ClientSession s = session;
        return s != null && !s.isClosed();
    }

    ClientSession getSession() { return session; }

    // ==================== ExecOperation ====================
    /**
     * 执行operation类。
     *
     * @author CH
     * @since 4.0.0
     */

    @Getter
    public static class ExecOperation {
        /**
         * 客户端实例
         */
        private final SshClient client;
        /**
          * 命令
         */
        private String command;

        ExecOperation(SshClient client) { this.client = client; }

        /**
         * 命令
         *
         * @param cmd CMD
         * @return 命令的结果
         */
        public ExecOperation command(String cmd) { this.command = cmd; return this; }

        /**
         * 执行
         *
         * @return 执行的结果
         */
        public ExecResult execute() {
            try {
                var channel = client.getSession().createExecChannel(command);
                // MINA SSHD 标准用法: 先绑定输出流再打开通道,
 // 执行 通道的 inverted 流在部分版本下不可用, 统一使用显式流收集输出
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

        /**
         * 执行和获取输出
         *
         * @return 执行和获取输出的结果
         */
        public String executeAndGetOutput() { return execute().stdout(); }
        /**
         * 执行和获取exit编码
         *
         * @return 执行和获取exit编码的结果
         */
        public int executeAndGetExitCode() { return execute().exitCode(); }
    }

    // ==================== ShellOperation ====================
    /**
     * ShellOperation类。
     *
     * @author CH
     * @since 4.0.0
     */

    @Getter
    public static class ShellOperation {
        /**
         * 客户端实例
         */
        private final SshClient client;
        /**
          * 通道
         */
        private ChannelShell channel;

        ShellOperation(SshClient client) { this.client = client; }

        /**
         * 连接
         *
         * @return 连接的结果
         */
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

        /**
         * 发送
         *
         * @param command 命令
         * @return 发送的结果
         */
        public ShellOperation send(String command) throws IOException {
            channel.getInvertedIn().write((command + "\n").getBytes());
            channel.getInvertedIn().flush();
            return this;
        }

        /**
         * 读取全部
         *
         * @return 读取全部的结果
         */
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
    /**
     * 远期operation类。
     *
     * @author CH
     * @since 4.0.0
     */

    @Getter
    public static class ForwardOperation {
        /**
         * 客户端实例
         */
        private final SshClient client;
        /**
          * 本地 端口
         */
        private int localPort;
        /**
          * 远程 主机
         */
        private String remoteHost;
        /**
          * 远程 端口
         */
        private int remotePort;
        /**
         * dynamic
         */
        private boolean dynamic;
        /**
          * 是否 远程
         */
        private boolean isRemote;
        /**
          * bind 地址
         */
        private String bindAddress = "127.0.0.1";
        /**
          * actual 端口
         */
        private int actualPort = -1;

        ForwardOperation(SshClient client) { this.client = client; }

        /**
         * 正向隧道（本地端口转发）
         * <p>将本地端口的流量转发到远程主机端口。</p>
         * @param localPort 本地端口
         * @param remoteHost 远程主机
         * @param remotePort 远程端口
         * @return 本地的结果
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
         * @param remotePort 远程端口
         * @param localHost 本地主机
         * @param localPort 本地端口
         * @return 远程的结果
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
         * @param localPort 本地端口
         * @return dynamic的结果
         */
        public ForwardOperation dynamic(int localPort) {
            this.localPort = localPort;
            this.dynamic = true;
            this.isRemote = false;
            return this;
        }

        /**
         * 绑定地址
         *
         * @param address 地址
         * @return bind地址的结果
         */
        public ForwardOperation bindAddress(String address) {
            this.bindAddress = address;
            return this;
        }

        /**
         * 开始
         *
         * @return 启动的结果
         */
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

        /**
         * 开始本地
         *
         * @return 启动本地的结果
         */
        private AutoCloseable startLocal() throws IOException {
            SshdSocketAddress bindAddr = new SshdSocketAddress(bindAddress, localPort);
            SshdSocketAddress remoteAddr = new SshdSocketAddress(remoteHost, remotePort);
            AutoCloseable tracker = client.getSession().createLocalPortForwardingTracker(bindAddr, remoteAddr);
            this.actualPort = localPort;
            log.info("正向隧道启动: {}:{} → {}:{}", bindAddress, localPort, remoteHost, remotePort);
            return tracker;
        }

        /**
         * 开始远程
         *
         * @return 启动远程的结果
         */
        private AutoCloseable startRemote() throws IOException {
            SshdSocketAddress remoteAddr = new SshdSocketAddress(bindAddress, remotePort);
            SshdSocketAddress localAddr = new SshdSocketAddress(remoteHost, localPort);
            AutoCloseable tracker = client.getSession().createRemotePortForwardingTracker(remoteAddr, localAddr);
            this.actualPort = localPort;
            log.info("反向隧道启动: 远程{}:{} → 本地{}:{}", bindAddress, remotePort, remoteHost, localPort);
            return tracker;
        }

        /**
         * 开始Dynamic
         *
         * @return 启动dynamic的结果
         */
        private AutoCloseable startDynamic() throws IOException {
            SshdSocketAddress bindAddr = new SshdSocketAddress(bindAddress, localPort);
            AutoCloseable tracker = client.getSession().createDynamicPortForwardingTracker(bindAddr);
            this.actualPort = localPort;
            log.info("动态隧道(SOCKS5)启动: {}:{}", bindAddress, localPort);
            return tracker;
        }
    }

    // ==================== TunnelDefinition（隧道定义） ====================
    /**
     * TunnelDefinition类。
     *
     * @author CH
     * @since 4.0.0
     */

    @Getter
    public static class TunnelDefinition {
        /**
         * 类型枚举。
         *
         * @author CH
         * @since 4.0.0
         */
        public enum Type { LOCAL, REMOTE, DYNAMIC }

        /**
         * 类型
         */
        private final Type type;
        /**
          * 本地 端口
         */
        private final int localPort;
        /**
          * 远程 主机
         */
        private final String remoteHost;
        /**
          * 远程 端口
         */
        private final int remotePort;

        /**
          * 创建 tunneldefinition 实例
         * @param type 类型
         * @param localPort int
         * @param remoteHost 字符串
         * @param localPort int
         * @param localPort 本地端口
         * @param remoteHost 远程主机
         * @param remotePort 远程端口
         * @return TunnelDefinition的结果
         */
        private TunnelDefinition(Type type, int localPort, String remoteHost, int remotePort) {
            this.type = type;
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
        }

        /**
          * 正向隧道: 本地 本地端口 → 远程 远程主机:远程端口
         * @param localPort 本地端口
         * @param remoteHost 远程主机
         * @param remotePort 远程端口
         * @return 本地的结果
         */
        public static TunnelDefinition local(int localPort, String remoteHost, int remotePort) {
            return new TunnelDefinition(Type.LOCAL, localPort, remoteHost, remotePort);
        }

        /**
          * 反向隧道: 远程 远程端口 → 本地 远程主机:本地端口
         * @param remotePort 远程端口
         * @param localHost 本地主机
         * @param localPort 本地端口
         * @return 远程的结果
         */
        public static TunnelDefinition remote(int remotePort, String localHost, int localPort) {
            return new TunnelDefinition(Type.REMOTE, localPort, localHost, remotePort);
        }

        /**
         * 动态隧道: 本地 SOCKS5 代理
         * @param localPort 本地端口
         * @return dynamic的结果
         */
        public static TunnelDefinition dynamic(int localPort) {
            return new TunnelDefinition(Type.DYNAMIC, localPort, null, 0);
        }
    }

    // ==================== TerminalOperation（PTY 实时终端） ====================
    /**
     * TerminalOperation类。
     *
     * @author CH
     * @since 4.0.0
     */

    @Getter
    public static class TerminalOperation {
        /**
         * 客户端实例
         */
        private final SshClient client;
        /**
          * 伪终端
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
          * 通道
         */
        private ChannelShell channel;
        /**
          * 输入 流
         */
        private InputStream inputStream;
        /**
          * 输出 流
         */
        private OutputStream outputStream;
        /**
         * 是否已连接
         */
        private volatile boolean connected = false;
        /**
          * 输出 Callback
         */
        private Consumer<String> outputCallback;
        /**
          * 关闭 Callback
         */
        private Runnable closeCallback;
        /**
         * reader Thread
         */
        private Thread readerThread;
        /**
          * 输出 缓冲
         */
        private final StringBuilder outputBuffer = new StringBuilder();
        /**
          * waiting For 提示符
         */
        private volatile boolean waitingForPrompt = false;
        /**
          * 期望 提示符
         */
        private String expectedPrompt = "";
        /**
          * 提示符 插销
         */
        private CountDownLatch promptLatch = new CountDownLatch(1);

        TerminalOperation(SshClient client) { this.client = client; }

        /**
         * 伪终端
         *
         * @param v v
         * @return 伪终端的结果
         */
        public TerminalOperation pty(boolean v) { this.pty = v; return this; }
        /**
         * Width
         *
         * @param w w
         * @return width的结果
         */
        public TerminalOperation width(int w) { this.width = w; return this; }
        /**
         * Height
         *
         * @param h h
         * @return height的结果
         */
        public TerminalOperation height(int h) { this.height = h; return this; }
        /**
         * on输出
         *
         * @param callback callback
         * @return on输出的结果
         */
        public TerminalOperation onOutput(Consumer<String> callback) { this.outputCallback = callback; return this; }
        /**
         * On关闭
         *
         * @param callback callback
         * @return on关闭的结果
         */
        public TerminalOperation onClose(Runnable callback) { this.closeCallback = callback; return this; }

        /**
         * 连接
         *
         * @return 连接的结果
         */
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

        /** 开始读取thread */
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

        /**
         * 发送
         *
         * @param command 命令
         * @return 发送的结果
         */
        public TerminalOperation send(String command) throws IOException {
            if (!connected) {
                throw new SshClientException("终端未连接", null);
            }
            outputStream.write((command + "\n").getBytes());
            outputStream.flush();
            return this;
        }

        /**
         * 发送Raw
         *
         * @param input 输入
         * @return 发送raw的结果
         */
        public TerminalOperation sendRaw(String input) throws IOException {
            if (!connected) {
                throw new SshClientException("终端未连接", null);
            }
            outputStream.write(input.getBytes());
            outputStream.flush();
            return this;
        }

        /**
         * 发送键
         *
         * @param key 键
         * @return 发送键的结果
         */
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

        /**
         * 读取缓冲
         *
         * @return 读取缓冲的结果
         */
        public String readBuffer() {
            synchronized (outputBuffer) {
                String data = outputBuffer.toString();
                outputBuffer.setLength(0);
                return data;
            }
        }

        /**
         * 读取Until
         *
         * @param expected 期望
         * @param timeoutMs 超时ms
         * @return 读取until的结果
         */
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

        /**
         * 是否连接
         *
         * @return 是否连接的结果
         */
        public boolean isConnected() { return connected; }
    }

    // ==================== ExecResult ====================

    /**
     * 执行结果
     *
     * @param exitCode exit编码
     * @param stdout stdout
     * @param stderr stderr
     * @return 执行结果的结果
     */
    public record ExecResult(int exitCode, String stdout, String stderr) {
        /**
         * 获取输出
         *
         * @return 获取输出的结果
         */
        public String getOutput() {
            return stdout;
        }
    }

    // ==================== Builder ====================
    /**
     * 构建器类。
     *
     * @author CH
     * @since 4.0.0
     */

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
          * 私募 键 路径
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

        /**
         * 主机
         *
         * @param h h
         * @return 主机的结果
         */
        public Builder host(String h) { this.host = h; return this; }
        /**
         * 端口
         *
         * @param p p
         * @return 端口的结果
         */
        public Builder port(int p) { this.port = p; return this; }
        /**
         * 用户名
         *
         * @param u u
         * @return 用户名的结果
         */
        public Builder username(String u) { this.username = u; return this; }
        /**
         * 密码
         *
         * @param p p
         * @return 密码的结果
         */
        public Builder password(String p) { this.password = p; return this; }
        /**
         * 私募键
         *
         * @param path 路径
         * @return 私募键的结果
         */
        public Builder privateKey(String path) { this.privateKeyPath = path; return this; }
        /**
         * 连接超时
         *
         * @param t t
         * @return 连接超时的结果
         */
        public Builder connectTimeout(int t) { this.connectTimeout = t; return this; }
        /**
         * 会话超时
         *
         * @param t t
         * @return 会话超时的结果
         */
        public Builder sessionTimeout(int t) { this.sessionTimeout = t; return this; }

        /**
         * 构建
         *
         * @return 构建的结果
         * @author CH
         * @since 4.0.0
         */
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
          * 创建 ssh客户端异常 实例
         * @param msg msg
         * @param cause Throwable
         * @param cause cause
         */
        public SshClientException(String msg, Throwable cause) { super(msg, cause); }
    }
}
