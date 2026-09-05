package com.chua.winrm.support.client;

import io.cloudsoft.winrm4j.client.ShellCommand;
import io.cloudsoft.winrm4j.client.WinRmClient;
import io.cloudsoft.winrm4j.client.WinRmClientBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
     * 认证方案（NTLM / Basic），默认 NTLM
     */
    private final String authenticationScheme;
    /**
     * 是否关闭负载加密（Basic 认证时需配合目标机 AllowUnencrypted=true）
     */
    private final boolean payloadEncryptionOff;

    /**
     * win Rm Client
     */
    private WinRmClient winRmClient;
    /**
     * 是否已连接
     */
    private boolean connected = false;

    /**
     * 创建 WinRmExecClient 实例
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
     * 创建 WinRmExecClient 实例
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

    /** Builder */
    public static Builder builder() {
        return new Builder();
    }

    /** 创建 */
    public static WinRmExecClient create(String host, String username, String password) {
        return builder().host(host).username(username).password(password).build();
    }

    // ==================== 连接管理 ====================

    /** 连接 */
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

    /** 执行Command */
    public ExecResult executeCommand(String command, int timeoutMs) {
        return exec().command(command).execute();
    }

    /** 执行Command */
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

    // ==================== Linux → Windows 命令翻译 ====================

    /**
     * Linux → Windows PowerShell 命令映射表（按最长前缀优先匹配）。
     *
     * <ul>
     *   <li>ifconfig → ipconfig /all</li>
     *   <li>ping -c N → ping -n N</li>
     *   <li>ls / ls -la → dir /a</li>
     *   <li>cat file → Get-Content file</li>
     *   <li>grep pattern file → Select-String pattern file</li>
     *   <li>head -n N / tail -n N → 切片</li>
     *   <li>mkdir -p → New-Item -Force</li>
     *   <li>rm -rf → Remove-Item -Force -Recurse</li>
     *   <li>cp / mv → Copy-Item / Move-Item</li>
     *   <li>ps aux → Get-Process</li>
     *   <li>df -h → Get-Volume</li>
     *   <li>env / set → [Environment]::GetEnvironmentVariables()</li>
     *   <li>find ... -name → Get-ChildItem -Filter</li>
     * </ul>
     */
    private static final java.util.LinkedHashMap<Pattern, String> LINUX_TO_WIN = new java.util.LinkedHashMap<>();

    static {
        // 网络
        addL2w("^ifconfig\\s*(.*)", "ipconfig $1");
        addL2w("^ping\\s+-c\\s+(\\d+)\\s+(.*)", "ping -n $1 $2");
        addL2w("^ping\\s+(.*)", "ping -n 4 $1");
        addL2w("^netstat\\s+(-[a-zA-Z]+)?\\s*(.*)", "netstat $2");
        addL2w("^ss\\s+(.*)", "netstat -an $1");
        addL2w("^ip\\s+addr\\s*(.*)", "Get-NetIPAddress $1 | Format-List");
        addL2w("^ip\\s+route\\s*(.*)", "Get-NetRoute");
        addL2w("^curl\\s+'([^']+)'\\s*(.*)", "Invoke-RestMethod -Uri '$1' $2");
        addL2w("^wget\\s+'([^']+)'\\s*(.*)", "Invoke-WebRequest -Uri '$1' -OutFile 'download' $2");

        // 进程/系统
        addL2w("^ps\\s+aux(?!.*powershell)",
                "Get-Process | Select-Object Id,ProcessName,CPU,WorkingSet64,StartTime | Format-Table -AutoSize");
        addL2w("^ps\\s+(.*)", "Get-Process $1 | Format-Table -AutoSize");
        addL2w("^uptime\\s*(.*)", "(Get-Date).ToString('yyyy-MM-dd HH:mm:ss')");
        addL2w("^uname\\s*(-a|-r|-s|-n)?\\s*(.*)",
                "[System.Environment]::MachineName + ' ' + [System.Environment]::OSVersion.ToString()");
        addL2w("^whoami\\s*(.*)", "whoami $1");
        addL2w("^id\\s*(.*)", "whoami /user; whoami /groups");

        // 文件/目录
        addL2w("^ls\\s+-la\\s*(.*)", "dir /a $1");
        addL2w("^ls\\s+-lh\\s*(.*)", "dir /a $1");
        addL2w("^ls\\s+-a\\s*(.*)", "dir /a $1");
        addL2w("^ls\\s+(-[a-zA-Z]+\\s+)?(.*)", "dir $2");
        addL2w("^ls\\s*(.*)", "dir $1");
        addL2w("^cat\\s+(.*)", "Get-Content $1 -Encoding UTF8");
        addL2w("^less\\s+(.*)", "Get-Content $1 -Encoding UTF8 | More");
        addL2w("^head\\s+-n\\s+(\\d+)\\s+(.*)", "Get-Content $2 -Encoding UTF8 | Select-Object -First $1");
        addL2w("^head\\s+(\\d+)\\s+(.*)", "Get-Content $2 -Encoding UTF8 | Select-Object -First $1");
        addL2w("^tail\\s+-n\\s+(\\d+)\\s+(.*)", "Get-Content $1 -Encoding UTF8 | Select-Object -Last $2");
        addL2w("^tail\\s+(\\d+)\\s+(.*)", "Get-Content $1 -Encoding UTF8 | Select-Object -Last $2");
        addL2w("^tail\\s+-f\\s+(.*)", "Get-Content $1 -Encoding UTF8 -Wait");

        // 文件操作
        addL2w("^mkdir\\s+-p\\s+(.*)", "New-Item -ItemType Directory -Path '$1' -Force");
        addL2w("^mkdir\\s+(.*)", "New-Item -ItemType Directory -Path '$1'");
        addL2w("^rm\\s+-rf\\s+(.*)", "Remove-Item -Path '$1' -Force -Recurse -ErrorAction SilentlyContinue");
        addL2w("^rm\\s+-r\\s+(.*)", "Remove-Item -Path '$1' -Recurse -Force");
        addL2w("^rm\\s+(.*)", "Remove-Item -Path '$1' -Force");
        addL2w("^rmdir\\s+(.*)", "Remove-Item -Path '$1' -Force");
        addL2w("^del\\s+(.*)", "Remove-Item -Path '$1' -Force");
        addL2w("^cp\\s+(.*)\\s+(.*)", "Copy-Item -Path '$1' -Destination '$2'");
        addL2w("^mv\\s+(.*)\\s+(.*)", "Move-Item -Path '$1' -Destination '$2'");
        addL2w("^ln\\s+-s\\s+(.*)\\s+(.*)", "New-Item -Path '$2' -ItemType Junction -Target '$1'");
        addL2w("^find\\s+(.+?)\\s+-name\\s+['\"]([^'\"]+)['\"]",
                "Get-ChildItem -Path '$1' -Recurse -Filter '$2' | Select-Object FullName");
        addL2w("^find\\s+(.*)", "Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Select-Object FullName,Length,LastWriteTime");

        // 搜索/过滤
        addL2w("^grep\\s+-r\\s+(.+?)\\s+(.+)", "Select-String -Pattern '$1' -Path '$2' -Recurse | Select-Object Path,Line");
        addL2w("^grep\\s+-i\\s+(.+?)\\s+(.+)", "Select-String -Pattern '$1' -Path '$2' | Select-Object Path,Line");
        addL2w("^grep\\s+(.+?)\\s+(.+)", "Select-String -Pattern '$1' -Path '$2' | Select-Object Path,Line");
        addL2w("^grep\\s+(.*)", "Select-String -Pattern '$1' | Select-Object Path,Line");
        addL2w("^ag\\s+(.*)", "Select-String -Pattern '$1' -Recurse");
        addL2w("^rg\\s+(.*)", "Select-String -Pattern '$1' -Recurse");

        // 磁盘/空间
        addL2w("^df\\s+-h\\s*(.*)",
                "Get-Volume | Where-Object { $_.DriveLetter } | ForEach-Object { '{0} {1:N1}GB rem / {2:N1}GB total' -f $_.DriveLetter, ($_.SizeRemaining/1GB), ($_.Size/1GB) }");
        addL2w("^du\\s+-sh\\s+(.*)",
                "(Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1GB GB");
        addL2w("^du\\s+-h\\s+(.*)",
                "Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum | ForEach-Object { '{0:N2} GB' -f ($_.Sum/1GB) }");

        // 环境变量
        addL2w("^env\\s*(.*)", "[Environment]::GetEnvironmentVariables() | Format-Table");
        addL2w("^set\\s*(.*)", "[Environment]::GetEnvironmentVariables() | Format-Table");
        addL2w("^echo\\s+(.*)", "Write-Output '$1'");

        // 其他
        addL2w("^date\\s*(.*)", "(Get-Date).ToString('yyyy-MM-dd HH:mm:ss')");
        addL2w("^time\\s*(.*)", "(Get-Date).ToString('HH:mm:ss')");
        addL2w("^pwd\\s*(.*)", "Get-Location | Select-Object -ExpandProperty Path");
        addL2w("^cd\\s+(.*)", "Set-Location '$1'; Get-Location");
    }

    private static void addL2w(String regex, String replacement) {
        LINUX_TO_WIN.put(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement);
    }

    /**
     * 将 Linux 命令转换为等效的 PowerShell 命令。若不在映射表中则原样返回。
     */
    public static String translateLinuxCommand(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return cmd;
        }
        String trimmed = cmd.trim();
        for (Map.Entry<Pattern, String> entry : LINUX_TO_WIN.entrySet()) {
            java.util.regex.Matcher m = entry.getKey().matcher(trimmed);
            if (m.matches()) {
                return m.replaceAll(entry.getValue());
            }
        }
        return trimmed;
    }

    // ==================== ExecOperation ====================

    @Getter
    public static class ExecOperation {

        /**
         * 客户端实例
         */
        private final WinRmExecClient client;
        /**
         * command
         */
        private String command;

        ExecOperation(WinRmExecClient client) {
            this.client = client;
        }

        /** Command */
        public ExecOperation command(String cmd) {
            this.command = cmd;
            return this;
        }

        /** 执行 */
        public ExecResult execute() {
            try {
                if (!client.isConnected()) {
                    throw new WinRMException("WinRM 未连接，请先调用 connect()");
                }
                String translated = translateLinuxCommand(command);
                ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
                ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
                try (Writer outWriter = new OutputStreamWriter(outBuf, StandardCharsets.UTF_8);
                     Writer errWriter = new OutputStreamWriter(errBuf, StandardCharsets.UTF_8)) {
                    int exitCode = client.getWinRmClient().command(translated, outWriter, errWriter);
                    return new ExecResult(exitCode, outBuf.toString(StandardCharsets.UTF_8.name()),
                            errBuf.toString(StandardCharsets.UTF_8.name()));
                }
            } catch (Exception e) {
                throw new WinRMException("WinRM 命令执行失败: " + command, e);
            }
        }

        /** 执行And获取Output */
        public String executeAndGetOutput() {
            return execute().stdout();
        }

        /** 执行And获取ExitCode */
        public int executeAndGetExitCode() {
            return execute().exitCode();
        }
    }

    // ==================== ShellOperation ====================

    @Getter
    public static class ShellOperation {

        /**
         * 客户端实例
         */
        private final WinRmExecClient client;
        /**
         * shell
         */
        private ShellCommand shell;

        ShellOperation(WinRmExecClient client) {
            this.client = client;
        }

        /** 连接 */
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

        /** 发送 */
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

        /** 读取All */
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
         * output Callback
         */
        private Consumer<String> outputCallback;
        /**
         * close Callback
         */
        private Runnable closeCallback;
        /**
         * output Buffer
         */
        private StringBuilder outputBuffer = new StringBuilder();

        TerminalOperation(WinRmExecClient client) {
            this.client = client;
        }

        /** Pty */
        public TerminalOperation pty(boolean v) {
            return this;
        }

        /** Width */
        public TerminalOperation width(int w) {
            return this;
        }

        /** Height */
        public TerminalOperation height(int h) {
            return this;
        }

        /** OnOutput */
        public TerminalOperation onOutput(Consumer<String> callback) {
            this.outputCallback = callback;
            return this;
        }

        /** On关闭 */
        public TerminalOperation onClose(Runnable callback) {
            this.closeCallback = callback;
            return this;
        }

        /** 连接 */
        public TerminalOperation connect() {
            if (!client.isConnected()) {
                throw new WinRMException("WinRM 未连接，请先调用 connect()", null);
            }
            connected = true;
            return this;
        }

        /** 发送 */
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

        /** 读取Buffer */
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

        /** 是否Connected */
        public boolean isConnected() {
            return connected;
        }
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
         * 认证方案（NTLM / Basic），默认 NTLM
         */
        private String authenticationScheme = "NTLM";
        /**
         * 是否关闭负载加密（Basic 认证时需配合目标机 AllowUnencrypted=true）
         */
        private boolean payloadEncryptionOff;

        /** Host */
        public Builder host(String h) {
            this.host = h;
            return this;
        }

        /** Port */
        public Builder port(int p) {
            this.port = p;
            return this;
        }

        /** Username */
        public Builder username(String u) {
            this.username = u;
            return this;
        }

        /** Password */
        public Builder password(String p) {
            this.password = p;
            return this;
        }

        /** Domain */
        public Builder domain(String d) {
            this.domain = d;
            return this;
        }

        /** 连接Timeout */
        public Builder connectTimeout(int t) {
            this.connectTimeout = t;
            return this;
        }

        /** SessionTimeout */
        public Builder sessionTimeout(int t) {
            this.sessionTimeout = t;
            return this;
        }

        /** 认证Scheme */
        public Builder authenticationScheme(String scheme) {
            this.authenticationScheme = scheme;
            return this;
        }

        /** 关闭负载加密 */
        public Builder payloadEncryptionOff(boolean off) {
            this.payloadEncryptionOff = off;
            return this;
        }

        /** 构建 */
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
