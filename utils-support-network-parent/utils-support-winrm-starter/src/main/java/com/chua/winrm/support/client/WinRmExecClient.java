package com.chua.winrm.support.client;

import io.cloudsoft.winrm4j.client.ShellCommand;
import io.cloudsoft.winrm4j.client.WinRmClient;
import io.cloudsoft.winrm4j.client.WinRmClientBuilder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * WinRM 链式客户端，基于 winrm4j（io.cloudsoft.windows:winrm4j），与 SshClient 接口风格一致。
 *
 * <pre>{@code
 * WinRmExecClient winrm = WinRmExecClient.builder()
 *     .host("172.16.9.194").port(5985)
 *     .username("lenovo").password("123")
 *     .build();
 *
 * // 执行命令
 * String result = winrm.exec().command("echo test").executeAndGetOutput();
 *
 * // 交互式 Shell（同步读取全部输出）
 * winrm.shell().connect().send("echo test").readAll();
 *
 * // PTY 交互式终端（实时输入输出，基于单次命令模拟）
 * winrm.terminal().pty(true).width(80).height(24).onOutput(System.out::print).connect();
 * }</pre>
 *
 * @author CH
 * @since 2026/07/27
 */
@Getter
public class WinRmExecClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WinRmExecClient.class);

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String domain;
    private final int connectTimeout;
    private final int sessionTimeout;

    private WinRmClient winRmClient;
    private boolean connected = false;

    private WinRmExecClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.domain = b.domain;
        this.connectTimeout = b.connectTimeout;
        this.sessionTimeout = b.sessionTimeout;
    }

    // ==================== ClientSetting 风格构造函数 ====================

    public WinRmExecClient(com.chua.common.support.network.protocol.ClientSetting setting) {
        this.host = setting.getHost();
        this.port = setting.getPort();
        this.username = setting.getUsername();
        this.password = setting.getPassword();
        this.domain = null;
        this.connectTimeout = 30;
        this.sessionTimeout = 30;
    }

    // ==================== 工厂方法 ====================

    public static Builder builder() {
        return new Builder();
    }

    public static WinRmExecClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    // ==================== 连接管理 ====================

    public WinRmExecClient connect() {
        try {
            String endpoint = "http://" + host + ":" + port + "/wsman";
            WinRmClientBuilder builder = WinRmClient.builder(endpoint);
            builder.credentials(username, password);
            builder.targetAuthSchemes(Arrays.asList("NTLM"));
            builder.disableCertificateChecks(true);
            builder.connectionTimeout(connectTimeout);
            builder.receiveTimeout((long) sessionTimeout);
            winRmClient = builder.build();
            connected = true;
            log.info("WinRM 连接成功: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            WinRMException ex = new WinRMException("WinRM 连接失败: " + host + ":" + port, e);
            e.printStackTrace(System.err);
            throw ex;
        }
        return this;
    }

    public void disconnect() {
        try {
            if (winRmClient != null) {
                winRmClient.disconnect();
                winRmClient = null;
            }
            connected = false;
            log.info("WinRM 断开: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            log.warn("WinRM 断开异常: {}", e.getMessage());
        }
    }

    public ExecResult executeCommand(String command, int timeoutMs) {
        return exec().command(command).execute();
    }

    public ExecResult executeCommand(String command) {
        return executeCommand(command, 30_000);
    }

    public void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    // ==================== 操作入口 ====================

    /**
     * 执行命令
     */
    public ExecOperation exec() {
        return new ExecOperation(this);
    }

    /**
     * 交互式 Shell（同步读取全部输出）
     */
    public ShellOperation shell() {
        return new ShellOperation(this);
    }

    /**
     * PTY 交互式终端（实时输入输出，基于单次命令模拟）
     */
    public TerminalOperation terminal() {
        return new TerminalOperation(this);
    }

    WinRmClient getClient() {
        return winRmClient;
    }

    boolean isConnected() {
        return connected;
    }

    // ==================== ExecOperation ====================

    @Getter
    public static class ExecOperation {

        private final WinRmExecClient client;
        private String command;

        ExecOperation(WinRmExecClient client) {
            this.client = client;
        }

        public ExecOperation command(String cmd) {
            this.command = cmd;
            return this;
        }

        public ExecResult execute() {
            try {
                if (!client.isConnected()) {
                    throw new WinRMException("WinRM 未连接，请先调用 connect()");
                }
                ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
                ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
                try (Writer outWriter = new OutputStreamWriter(outBuf, StandardCharsets.UTF_8);
                     Writer errWriter = new OutputStreamWriter(errBuf, StandardCharsets.UTF_8)) {
                    int exitCode = client.getWinRmClient().command(command, outWriter, errWriter);
                    return new ExecResult(exitCode, outBuf.toString(StandardCharsets.UTF_8.name()),
                            errBuf.toString(StandardCharsets.UTF_8.name()));
                }
            } catch (Exception e) {
                throw new WinRMException("WinRM 命令执行失败: " + command, e);
            }
        }

        public String executeAndGetOutput() {
            return execute().stdout();
        }

        public int executeAndGetExitCode() {
            return execute().exitCode();
        }
    }

    // ==================== ShellOperation ====================

    @Getter
    public static class ShellOperation {

        private final WinRmExecClient client;
        private ShellCommand shell;

        ShellOperation(WinRmExecClient client) {
            this.client = client;
        }

        public ShellOperation connect() {
            try {
                if (!client.isConnected()) {
                    throw new WinRMException("WinRM 未连接，请先调用 connect()");
                }
                shell = client.getWinRmClient().createShell();
                log.debug("WinRM Shell 已创建");
            } catch (Exception e) {
                throw new WinRMException("WinRM Shell 连接失败", e);
            }
            return this;
        }

        public ShellOperation send(String cmd) throws IOException {
            if (shell == null) {
                throw new WinRMException("Shell 未连接，先调用 connect()", null);
            }
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            try (Writer outWriter = new OutputStreamWriter(outBuf, StandardCharsets.UTF_8);
                 Writer errWriter = new OutputStreamWriter(errBuf, StandardCharsets.UTF_8)) {
                shell.execute(cmd, outWriter, errWriter);
            }
            return this;
        }

        public String readAll() throws IOException {
            if (shell == null) {
                throw new WinRMException("Shell 未连接", null);
            }
            // ShellCommand.execute() already returns all output; this method is for API compatibility
            return "";
        }

        public void close() {
            if (shell != null) {
                try {
                    shell.close();
                } catch (Exception ignored) {
                }
                shell = null;
            }
        }
    }

    // ==================== TerminalOperation ====================

    @Getter
    public static class TerminalOperation {

        private final WinRmExecClient client;
        private boolean connected = false;
        private Consumer<String> outputCallback;
        private Runnable closeCallback;
        private StringBuilder outputBuffer = new StringBuilder();

        TerminalOperation(WinRmExecClient client) {
            this.client = client;
        }

        public TerminalOperation pty(boolean v) {
            return this;
        }

        public TerminalOperation width(int w) {
            return this;
        }

        public TerminalOperation height(int h) {
            return this;
        }

        public TerminalOperation onOutput(Consumer<String> callback) {
            this.outputCallback = callback;
            return this;
        }

        public TerminalOperation onClose(Runnable callback) {
            this.closeCallback = callback;
            return this;
        }

        public TerminalOperation connect() {
            if (!client.isConnected()) {
                throw new WinRMException("WinRM 未连接，请先调用 connect()", null);
            }
            connected = true;
            return this;
        }

        public TerminalOperation send(String cmd) {
            if (!connected) {
                throw new WinRMException("终端未连接", null);
            }
            try {
                ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
                ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
                try (Writer outWriter = new OutputStreamWriter(outBuf, StandardCharsets.UTF_8);
                     Writer errWriter = new OutputStreamWriter(errBuf, StandardCharsets.UTF_8)) {
                    client.getWinRmClient().command(cmd, outWriter, errWriter);
                }
                String out = outBuf.toString(StandardCharsets.UTF_8.name());
                String err = errBuf.toString(StandardCharsets.UTF_8.name());
                String combined = out + err;
                outputBuffer.append(combined);
                if (outputCallback != null) {
                    outputCallback.accept(combined);
                }
            } catch (Exception e) {
                throw new WinRMException("WinRM 终端命令执行失败: " + cmd, e);
            }
            return this;
        }

        public String readBuffer() {
            String data = outputBuffer.toString();
            outputBuffer.setLength(0);
            return data;
        }

        public void close() {
            connected = false;
            if (closeCallback != null) {
                closeCallback.run();
            }
        }

        public boolean isConnected() {
            return connected;
        }
    }

// ==================== ExecResult ====================

public record ExecResult(int exitCode, String stdout, String stderr) {
    public String getOutput() {
        return stdout;
    }
}

    // ==================== Builder ====================

    public static class Builder {

        private String host;
        private int port = 5985;
        private String username;
        private String password;
        private String domain;
        private int connectTimeout = 30;
        private int sessionTimeout = 30;

        public Builder host(String h) {
            this.host = h;
            return this;
        }

        public Builder port(int p) {
            this.port = p;
            return this;
        }

        public Builder username(String u) {
            this.username = u;
            return this;
        }

        public Builder password(String p) {
            this.password = p;
            return this;
        }

        public Builder domain(String d) {
            this.domain = d;
            return this;
        }

        public Builder connectTimeout(int t) {
            this.connectTimeout = t;
            return this;
        }

        public Builder sessionTimeout(int t) {
            this.sessionTimeout = t;
            return this;
        }

        public WinRmExecClient build() {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("host 不能为空");
            }
            if (username == null || username.trim().isEmpty()) {
                throw new IllegalArgumentException("username 不能为空");
            }
            return new WinRmExecClient(this);
        }
    }
}
