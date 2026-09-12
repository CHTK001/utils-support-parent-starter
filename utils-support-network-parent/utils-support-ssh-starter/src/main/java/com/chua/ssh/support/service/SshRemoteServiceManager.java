package com.chua.ssh.support.service;

import com.chua.common.support.service.RemoteServiceManager;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.ssh.support.client.SftpClient;
import com.chua.ssh.support.client.SshClient;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
* SSH 远程服务管理器。
*
* <p>直接基于 {@link SshClient} 与 {@link SftpClient}（Apache MINA SSHD）实现，
* 用于在远程主机上对 Java 服务进行 启停/重启/安装/卸载 管理。</p>
*
* <pre>{@code
* SshRemoteServiceManager mgr = ServiceProvider.of(RemoteServiceManager.class)
*         .getNewExtension("ssh");
* mgr.connect(new SshConfig("192.168.1.10", 22, "root", "pass", null));
* mgr.installRemote("app", "/opt/app/app.jar", "java -jar /opt/app/app.jar");
* mgr.startRemote("app", "/opt/app/app.jar", "java -jar /opt/app/app.jar");
* }</pre>te("app", "/opt/app/app.jar", "java -jar /opt/app/app.jar");
* }</pre>
*
* @author CH
* @since 4.0.0.43
 */
@Slf4j
@Spi("ssh")
public class SshRemoteServiceManager implements RemoteServiceManager {

    /**
    * 默认远程部署目录。
     */
    private static final String DEFAULT_REMOTE_DIR = "/opt/sip-server";

    /**
    * SSH 客户端。
     */
    private SshClient sshClient;

    /**
    * SFTP 客户端（上传 jar 用）。
     */
    private SftpClient sftpClient;

    /**
    * 当前配置。
     */
    private SshConfig config;

    @Override
    public void connect(SshConfig cfg) {
        this.config = cfg;
        SshClient.Builder builder = SshClient.builder()
                .host(cfg.host())
                .port(cfg.port())
                .username(cfg.username())
                .password(cfg.password());
        if (cfg.privateKeyPath() != null && !cfg.privateKeyPath().isBlank()) {
            builder.privateKey(cfg.privateKeyPath());
        }
        this.sshClient = builder.build().connect();
        log.info("[service-remote] SSH 已连接: {}@{}:{}", cfg.username(), cfg.host(), cfg.port());
    }

    @Override
    public void disconnect() {
        if (sftpClient != null) {
            sftpClient.close();
            sftpClient = null;
        }
        if (sshClient != null) {
            sshClient.close();
            sshClient = null;
        }
        config = null;
        log.info("[service-remote] SSH 已断开");
    }

    @Override
    public boolean isConnected() {
        return sshClient != null && sshClient.isConnected();
    }

    /**
    * 同步执行远程命令并返回输出（供远程主机诊断/配置使用）。
    *
    * @param command 要执行的命令
    * @return 命令输出（stdout 为空时回退 stderr）
     */
    public String execCommand(String command) {
        return execAndWait(command);
    }

    @Override
    public long startRemote(String serviceName, String jarPath, String startCmd) {
        requireConnected();
        String remotePath = isWindows() ? normalizeWindowsPath(jarPath) : normalizeRemotePath(jarPath);
        uploadJarIfNeeded(jarPath, remotePath);
        String cmd = replaceToken(startCmd, "{jar}", remotePath);
        log.info("[service-remote] 远程启动: {} cmd={}", serviceName, StringUtils.left(cmd, 100));
        if (isWindows()) {
            return execDetachWindows(cmd);
        }
        return execDetach(cmd);
    }

    @Override
    public void stopRemote(long pid, String serviceName) {
        requireConnected();
        if (isWindows()) {
            stopRemoteWindows(pid, serviceName);
            return;
        }
        if (pid > 0) {
            log.info("[service-remote] 远程停止: pid={}", pid);
            execAndWait("kill " + pid + " 2>/dev/null || echo ok");
        } else if (serviceName != null) {
            execAndWait("pkill -f " + serviceName + " 2>/dev/null || echo ok");
        }
    }

    /**
    * 停止 窗口 远程 Java 进程（按 PID 或按命令行过滤）。
    * @param pid pid
    * @param serviceName 服务名称
     */
    private void stopRemoteWindows(long pid, String serviceName) {
        if (pid > 0) {
            log.info("[service-remote] 远程停止: pid={}", pid);
            execAndWait("powershell -Command \"Stop-Process -Id " + pid + " -Force -ErrorAction SilentlyContinue\"");
        } else if (serviceName != null) {
            execAndWait("powershell -Command \"Get-CimInstance Win32_Process -Filter \\\"Name like '%java%'\\\" | "
                    + "Where-Object { $_.CommandLine -like '*" + serviceName + "*' } | "
                    + "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }\"");
        }
    }

    /**
    * 在 窗口 远程主机后台启动命令并返回 PID。
    * @param cmd CMD
    * @return 执行detach窗口的结果
     */
    private long execDetachWindows(String cmd) {
        try {
            String wrapped = "powershell -Command \"$p = Start-Process -FilePath 'java' -ArgumentList @('-jar', '"
                    + extractJarArg(cmd) + "') -WindowStyle Hidden -PassThru; $p.Id\"";
            String out = execAndWait(wrapped);
            try {
                return Long.parseLong(out.split("\\n")[0].trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] 远程启动失败: " + e.getMessage(), e);
        }
    }

    /**
    * 从 {@code java -jar <path>} 命令中提取 jar 路径。
    * @param cmd CMD
    * @return extractjar参数的结果
     */
    private static String extractJarArg(String cmd) {
        int jarIdx = cmd.toLowerCase().indexOf("-jar");
        if (jarIdx < 0) {
            return cmd;
        }
        String rest = cmd.substring(jarIdx + 4).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end > 0 ? rest.substring(1, end) : rest.replace("\"", "");
        }
        int space = rest.indexOf(' ');
        return space > 0 ? rest.substring(0, space) : rest;
    }

    @Override
    public void restartRemote(long pid, String serviceName, String jarPath, String startCmd) {
        stopRemote(pid, serviceName);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        startRemote(serviceName, jarPath, startCmd);
    }

    @Override
    public boolean isRemoteRunning(long pid) {
        if (pid <= 0) {
            return false;
        }
        requireConnected();
        try {
            String out;
            if (isWindows()) {
                out = execAndWait("powershell -Command \"(Get-Process -Id " + pid
                        + " -ErrorAction SilentlyContinue) -ne $null\"");
                return "True".equalsIgnoreCase(out);
            }
            out = execAndWait("kill -0 " + pid + " 2>&1 && echo alive || echo dead");
            return out.contains("alive");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isRemoteServiceRunning(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return false;
        }
        requireConnected();
        try {
            String out;
            if (isWindows()) {
                // 中英文系统兼容：RUNNING / 正在运行
                out = execAndWait("sc.exe query \"" + serviceName + "\"");
                String upper = out.toUpperCase();
                return upper.contains("RUNNING") || upper.contains("\u6B63\u5728\u8FD0\u884C");
            } else {
                out = execAndWait("systemctl is-active " + serviceName + " 2>/dev/null || echo inactive");
                return StringUtils.isNotBlank(out) && !"inactive".equalsIgnoreCase(out.trim());
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void uploadJar(String localPath, String remotePath) {
        requireConnected();
        uploadJarIfNeeded(localPath, remotePath);
    }

    /**
    * 检测远程主机操作系统类型（窗口 返回 true）。
    *
    * @return true 表示远程主机为 窗口
     */
    private boolean isWindows() {
        try {
            String out = execAndWait("ver 2>&1");
            return out.toLowerCase().contains("windows");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void installRemote(String serviceName, String remoteJarPath, String startCmd) {
        requireConnected();
        if (isWindows()) {
            installRemoteWindows(serviceName, remoteJarPath, startCmd);
        } else {
            installRemoteLinux(serviceName, remoteJarPath, startCmd);
        }
    }

    @Override
    public void uninstallRemote(String serviceName) {
        requireConnected();
        if (isWindows()) {
            uninstallRemoteWindows(serviceName);
        } else {
            uninstallRemoteLinux(serviceName);
        }
    }

    /**
    * 在 窗口 远程主机上安装服务（sc.exe 创建）。
    * @param serviceName 服务名称
    * @param remoteJarPath 远程jar路径
    * @param startCmd 启动CMD
     */
    private void installRemoteWindows(String serviceName, String remoteJarPath, String startCmd) {
        String winPath = normalizeWindowsPath(remoteJarPath);
        String remoteDir = Path.of(winPath).getParent().toString().replace("\\", "/");
        execAndWait("powershell -Command \"New-Item -ItemType Directory -Path '" + remoteDir + "' -Force | Out-Null\"");
 // 将 启动CMD 中的 Linux 风格路径替换为 窗口 绝对路径
        String winStartCmd = replaceToken(startCmd, remoteJarPath, winPath);
        execAndWait("sc.exe create \"" + serviceName + "\" binPath= \"" + winStartCmd + "\" start= auto");
        execAndWait("sc.exe description \"" + serviceName + "\" \"" + serviceName + " service\"");
        execAndWait("sc.exe failure \"" + serviceName + "\" reset= 86400 actions= restart/60000");
        log.info("[service-remote] Windows 服务安装完成: {}", serviceName);
    }

    /**
    * 将 Linux 风格远程路径归一化为 窗口 绝对路径（/opt/x → C:\opt\x）。
    * @param path 路径
    * @return normalize窗口路径的结果
     */
    private static String normalizeWindowsPath(String path) {
        if (path == null) {
            return "C:\\opt\\app.jar";
        }
        String p = path.strip().replace("/", "\\");
        if (p.matches("^[A-Za-z]:.*")) {
            return p;
        }
        if (p.startsWith("\\opt")) {
            return "C:" + p;
        }
        return "C:\\opt\\" + Path.of(p).getFileName();
    }

    /**
    * 在 Linux 远程主机上安装服务（systemd unit）。
    * @param serviceName 服务名称
    * @param remoteJarPath 远程jar路径
    * @param startCmd 启动CMD
     */
    private void installRemoteLinux(String serviceName, String remoteJarPath, String startCmd) {
        String unitContent = buildSystemdUnit(serviceName, remoteJarPath, startCmd);
        execAndWait("cat > /etc/systemd/system/" + serviceName + ".service << 'UNIT_EOF'\n"
                + unitContent + "\nUNIT_EOF");
        execAndWait("systemctl daemon-reload && systemctl enable " + serviceName);
        log.info("[service-remote] Linux 服务安装完成: {}", serviceName);
    }

    /**
    * 在 窗口 远程主机上卸载服务。
    * @param serviceName 服务名称
     */
    private void uninstallRemoteWindows(String serviceName) {
        execAndWait("sc.exe stop \"" + serviceName + "\" 2>nul");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        execAndWait("sc.exe delete \"" + serviceName + "\" 2>nul");
        log.info("[service-remote] Windows 服务卸载完成: {}", serviceName);
    }

    /**
    * 在 Linux 远程主机上卸载服务。
    * @param serviceName 服务名称
     */
    private void uninstallRemoteLinux(String serviceName) {
        execAndWait("systemctl disable " + serviceName + " 2>/dev/null; systemctl stop "
                + serviceName + " 2>/dev/null");
        execAndWait("rm -f /etc/systemd/system/" + serviceName + ".service && systemctl daemon-reload");
        log.info("[service-remote] Linux 服务卸载完成: {}", serviceName);
    }

    // ========== 私有方法 ==========

    /**
    * 校验 SSH 连接是否已建立，未连接时抛出异常。
     */
    private void requireConnected() {
        if (sshClient == null || !sshClient.isConnected()) {
            throw new IllegalStateException("[service-remote] SSH 未连接，请先调用 connect()");
        }
    }

    /**
    * 在远程主机上同步执行命令并返回输出。
    *
    * @param cmd 要执行的命令
    * @return 命令输出（stdout 为空时回退 stderr）
     */
    private String execAndWait(String cmd) {
        requireConnected();
        try {
            SshClient.ExecResult r = sshClient.exec().command(cmd).execute();
            String out = "".equals(r.stdout()) ? r.stderr() : r.stdout();
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] 命令执行失败: " + cmd, e);
        }
    }

    /**
    * 在远程主机后台执行命令并返回进程 PID。
    *
    * @param cmd 后台启动命令
    * @return 进程 PID，解析失败返回 -1
     */
    private long execDetach(String cmd) {
        requireConnected();
        try {
            // 后台执行并取 PID：nohup ... > /dev/null 2>&1 & echo $!
            String wrapped = "nohup " + cmd + " > /dev/null 2>&1 & echo $!";
            String out = execAndWait(wrapped);
            try {
                return Long.parseLong(out.split("\\n")[0].trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] 远程启动失败: " + e.getMessage(), e);
        }
    }

    /**
    * 将本地 jar 上传到远程路径。
    *
    * @param localPath  本地 jar 路径
    * @param remotePath 远程目标路径（含文件名）
     */
    private void uploadJarIfNeeded(String localPath, String remotePath) {
        if (localPath == null || localPath.isBlank()) {
            return;
        }
        try {
            if (!Files.exists(Path.of(localPath))) {
                log.warn("[service-remote] 本地 jar 不存在: {}", localPath);
                return;
            }
            ensureSftp();
 // SFTP 统一使用正斜杠路径（窗口 打开ssh 亦兼容）
            String sftpPath = remotePath.replace("\\", "/");
            String remoteDir = Path.of(sftpPath).getParent().toString();
 // 使用 SSH 命令创建目录（比 SFTP mkdir 更可靠，尤其 窗口）
            execAndWait("powershell -Command \"New-Item -ItemType Directory -Path '" + remoteDir + "' -Force | Out-Null\"");
            sftpClient.upload().local(localPath).remote(sftpPath).exec();
            log.info("[service-remote] jar 已上传: {} -> {}", localPath, sftpPath);
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] jar 上传失败: " + localPath, e);
        }
    }

    /**
    * 懒加载 SFTP 客户端，仅首次上传时建立连接。
     */
    private void ensureSftp() {
        if (sftpClient != null) {
            return;
        }
        SftpClient.Builder builder = SftpClient.builder()
                .host(config.host())
                .port(config.port())
                .username(config.username())
                .password(config.password());
        if (config.privateKeyPath() != null && !config.privateKeyPath().isBlank()) {
            builder.privateKey(config.privateKeyPath());
        }
        this.sftpClient = builder.build().connect();
    }

    /**
    * 规范化为绝对远程路径：相对路径拼接到默认部署目录。
    *
    * @param jarPath 原始 jar 路径
    * @return 归一化远程路径
     */
    private static String normalizeRemotePath(String jarPath) {
        if (jarPath == null) {
            return DEFAULT_REMOTE_DIR + "/app.jar";
        }
        String p = jarPath.strip().replace("\\", "/");
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
            return p;
        }
        return DEFAULT_REMOTE_DIR + "/" + Path.of(p).getFileName();
    }

    /**
    * 替换模板中的占位符 令牌。
    *
    * @param template 模板字符串
    * @param token    占位符（如 {jar}）
    * @param value    替换值
    * @return 替换后的字符串
     */
    private static String replaceToken(String template, String token, String value) {
        return template == null ? value : template.replace(token, value);
    }

    /**
    * 构建 systemd unit 文件内容。
    *
    * @param serviceName 服务名
    * @param jarPath     jar 远程路径（用于推断工作目录）
    * @param startCmd    启动命令
    * @return unit 文件文本
     */
    private static String buildSystemdUnit(String serviceName, String jarPath, String startCmd) {
        return "[Unit]\n" +
               "Description=" + serviceName + "\n" +
               "After=network.target\n\n" +
               "[Service]\n" +
               "Type=simple\n" +
               "WorkingDirectory=" + Path.of(jarPath).getParent() + "\n" +
               "ExecStart=" + startCmd + "\n" +
               "Restart=on-failure\n" +
               "RestartSec=5\n\n" +
               "[Install]\n" +
               "WantedBy=multi-user.target";
    }
}