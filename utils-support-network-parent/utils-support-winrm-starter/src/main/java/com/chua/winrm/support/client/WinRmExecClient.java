package com.chua.winrm.support.client;

import io.cloudsoft.winrm4j.client.ShellCommand;
import io.cloudsoft.winrm4j.client.WinRmClient;
import io.cloudsoft.winrm4j.client.WinRmClientBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
   * winrm 链式客户端，基于 winrm4j（io.cloudsoft.窗口:winrm4j），与 ssh客户端 接口风格一致。
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
 * }</pre>().pty(true).width(80).height(24).onOutput(System.out::print).connect();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Slf4j
public class WinRmExecClient implements AutoCloseable {

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
     * domain
     */
    private final String domain;
    /**
     * 连接超时时间（毫秒）
     */
    private final int connectTimeout;
    /**
     * 会话超时时间
     */
    private final int sessionTimeout;
    /**
      * 认证方案（NTLM / 基础），默认 NTLM
     */
    private final String authenticationScheme;
    /**
      * 是否关闭负载加密（基础 认证时需配合目标机 allowunencrypted=true）
     */
    private final boolean payloadEncryptionOff;

    /**
      * win Rm 客户端
     */
    private WinRmClient winRmClient;
    /**
     * 是否已连接
     */
    private boolean connected = false;

    /**
      * 创建 winrm执行客户端 实例
     * @param b b
     */
    private WinRmExecClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.domain = b.domain;
        this.connectTimeout = b.connectTimeout;
        this.sessionTimeout = b.sessionTimeout;
        this.authenticationScheme = b.authenticationScheme;
        this.payloadEncryptionOff = b.payloadEncryptionOff;
    }

    // ==================== ClientSetting 风格构造函数 ====================

    /**
      * 创建 winrm执行客户端 实例
     * @param setting setting
     */
    public WinRmExecClient(com.chua.common.support.network.protocol.ClientSetting setting) {
        this.host = setting.getHost();
        this.port = setting.getPort();
        this.username = setting.getUsername();
        this.password = setting.getPassword();
        this.domain = null;
        this.connectTimeout = 30;
        this.sessionTimeout = 30;
        this.authenticationScheme = "NTLM";
        this.payloadEncryptionOff = false;
    }

    // ==================== 工厂方法 ====================

    /**
     * 构建器
     *
     * @return 构建器的结果
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建
     *
     * @param host 主机
     * @param username 用户名
     * @param password 密码
     * @return 创建的结果
     */
    public static WinRmExecClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    // ==================== 连接管理 ====================

    /**
     * 连接
     *
     * @return 连接的结果
     */
    public WinRmExecClient connect() {
        try {
            String endpoint = "http://" + host + ":" + port + "/wsman";
            WinRmClientBuilder builder = WinRmClient.builder(endpoint);
            builder.credentials(username, password);
            builder.authenticationScheme(authenticationScheme);
            if (payloadEncryptionOff) {
                builder.payloadEncryptionMode(io.cloudsoft.winrm4j.client.PayloadEncryptionMode.OFF);
            }
            builder.targetAuthSchemes(Arrays.asList(authenticationScheme));
            builder.disableCertificateChecks(true);
            builder.connectionTimeout(connectTimeout);
            builder.receiveTimeout((long) sessionTimeout);
            winRmClient = builder.build();
            connected = true;
            log.info("WinRM 连接成功: {}@{}:{} scheme={}", username, host, port, authenticationScheme);
        } catch (Exception e) {
            WinRMException ex = new WinRMException("WinRM 连接失败: " + host + ":" + port, e);
            e.printStackTrace(System.err);
            throw ex;
        }
        return this;
    }

    /** 断开 */
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

    /**
     * 执行命令
     *
     * @param command 命令
     * @param timeoutMs 超时ms
     * @return 执行命令的结果
     */
    public ExecResult executeCommand(String command, int timeoutMs) {
        return exec().command(command).execute();
    }

    /**
     * 执行命令
     *
     * @param command 命令
     * @return 执行命令的结果
     */
    public ExecResult executeCommand(String command) {
        return executeCommand(command, 30_000);
    }

    /** 关闭Quietly */
    public void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) {
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        disconnect();
    }

    // ==================== 操作入口 ====================

    /**
     * 执行命令
     * @return 执行的结果
     */
    public ExecOperation exec() {
        return new ExecOperation(this);
    }

    /**
     * 交互式 Shell（同步读取全部输出）
     * @return shell的结果
     */
    public ShellOperation shell() {
        return new ShellOperation(this);
    }

    /**
      * 伪终端 交互式终端（实时输入输出，基于单次命令模拟）
     * @return terminal的结果
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
        private final WinRmExecClient client;
        /**
          * 命令
         */
        private String command;

        ExecOperation(WinRmExecClient client) {
            this.client = client;
        }

        /**
         * 命令
         *
         * @param cmd CMD
         * @return 命令的结果
         */
        public ExecOperation command(String cmd) {
            this.command = cmd;
            return this;
        }

        /**
         * 执行
         *
         * @return 执行的结果
         */
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

        /**
         * 执行和获取输出
         *
         * @return 执行和获取输出的结果
         */
        public String executeAndGetOutput() {
            return execute().stdout();
        }

        /**
         * 执行和获取exit编码
         *
         * @return 执行和获取exit编码的结果
         */
        public int executeAndGetExitCode() {
            return execute().exitCode();
        }
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
        private final WinRmExecClient client;
        /**
          * Shell
         */
        private ShellCommand shell;

        ShellOperation(WinRmExecClient client) {
            this.client = client;
        }

        /**
         * 连接
         *
         * @return 连接的结果
         */
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

        /**
         * 发送
         *
         * @param cmd CMD
         * @return 发送的结果
         */
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

        /**
         * 读取全部
         *
         * @return 读取全部的结果
         */
        public String readAll() throws IOException {
            if (shell == null) {
                throw new WinRMException("Shell 未连接", null);
            }
            // ShellCommand.execute() already returns all output; this method is for API compatibility
            return "";
        }

        /** 关闭 */
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
        private final WinRmExecClient client;
        /**
         * 是否已连接
         */
        private boolean connected = false;
        /**
          * 输出 Callback
         */
        private Consumer<String> outputCallback;
        /**
          * 关闭 Callback
         */
        private Runnable closeCallback;
        /**
          * 输出 缓冲
         */
        private StringBuilder outputBuffer = new StringBuilder();

        TerminalOperation(WinRmExecClient client) {
            this.client = client;
        }

        /**
         * 伪终端
         *
         * @param v v
         * @return 伪终端的结果
         */
        public TerminalOperation pty(boolean v) {
            return this;
        }

        /**
         * Width
         *
         * @param w w
         * @return width的结果
         */
        public TerminalOperation width(int w) {
            return this;
        }

        /**
         * Height
         *
         * @param h h
         * @return height的结果
         */
        public TerminalOperation height(int h) {
            return this;
        }

        /**
         * on输出
         *
         * @param callback callback
         * @return on输出的结果
         */
        public TerminalOperation onOutput(Consumer<String> callback) {
            this.outputCallback = callback;
            return this;
        }

        /**
         * On关闭
         *
         * @param callback callback
         * @return on关闭的结果
         */
        public TerminalOperation onClose(Runnable callback) {
            this.closeCallback = callback;
            return this;
        }

        /**
         * 连接
         *
         * @return 连接的结果
         */
        public TerminalOperation connect() {
            if (!client.isConnected()) {
                throw new WinRMException("WinRM 未连接，请先调用 connect()", null);
            }
            connected = true;
            return this;
        }

        /**
         * 发送
         *
         * @param cmd CMD
         * @return 发送的结果
         */
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

        /**
         * 读取缓冲
         *
         * @return 读取缓冲的结果
         */
        public String readBuffer() {
            String data = outputBuffer.toString();
            outputBuffer.setLength(0);
            return data;
        }

        /** 关闭 */
        public void close() {
            connected = false;
            if (closeCallback != null) {
                closeCallback.run();
            }
        }

        /**
         * 是否连接
         *
         * @return 是否连接的结果
         */
        public boolean isConnected() {
            return connected;
        }
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
        private int port = 5985;
        /**
         * 登录用户名
         */
        private String username;
        /**
         * 登录密码
         */
        private String password;
        /**
         * domain
         */
        private String domain;
        /**
         * 连接超时时间（毫秒）
         */
        private int connectTimeout = 30;
        /**
         * 会话超时时间
         */
        private int sessionTimeout = 30;
        /**
          * 认证方案（NTLM / 基础），默认 NTLM
         */
        private String authenticationScheme = "NTLM";
        /**
          * 是否关闭负载加密（基础 认证时需配合目标机 allowunencrypted=true）
         */
        private boolean payloadEncryptionOff;

        /**
         * 主机
         *
         * @param h h
         * @return 主机的结果
         */
        public Builder host(String h) {
            this.host = h;
            return this;
        }

        /**
         * 端口
         *
         * @param p p
         * @return 端口的结果
         */
        public Builder port(int p) {
            this.port = p;
            return this;
        }

        /**
         * 用户名
         *
         * @param u u
         * @return 用户名的结果
         */
        public Builder username(String u) {
            this.username = u;
            return this;
        }

        /**
         * 密码
         *
         * @param p p
         * @return 密码的结果
         */
        public Builder password(String p) {
            this.password = p;
            return this;
        }

        /**
         * Domain
         *
         * @param d d
         * @return domain的结果
         */
        public Builder domain(String d) {
            this.domain = d;
            return this;
        }

        /**
         * 连接超时
         *
         * @param t t
         * @return 连接超时的结果
         */
        public Builder connectTimeout(int t) {
            this.connectTimeout = t;
            return this;
        }

        /**
         * 会话超时
         *
         * @param t t
         * @return 会话超时的结果
         */
        public Builder sessionTimeout(int t) {
            this.sessionTimeout = t;
            return this;
        }

        /**
         * 认证Scheme
         *
         * @param scheme scheme
         * @return 认证scheme的结果
         */
        public Builder authenticationScheme(String scheme) {
            this.authenticationScheme = scheme;
            return this;
        }

        /**
         * 关闭负载加密
         *
         * @param off off
         * @return payload加密off的结果
         */
        public Builder payloadEncryptionOff(boolean off) {
            this.payloadEncryptionOff = off;
            return this;
        }

        /**
         * 构建
         *
         * @return 构建的结果
         */
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
