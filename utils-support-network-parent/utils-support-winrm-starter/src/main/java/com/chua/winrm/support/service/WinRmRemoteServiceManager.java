package com.chua.winrm.support.service;

import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.service.RemoteServiceManager;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.winrm.support.client.WinRmExecClient;
import com.chua.winrm.support.client.WinRmFileClient;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WinRM 远程服务管理器。
 *
 * <p>直接基于 {@link WinRmExecClient} 与 {@link WinRmFileClient}（winrm4j）实现，
 * 用于在远程 Windows 主机上对 Java 服务进行 启停/重启/安装/卸载 管理。</p>
 *
 * <pre>{@code
 * WinRmRemoteServiceManager mgr = ServiceProvider.of(RemoteServiceManager.class)
 *         .getNewExtension("winrm");
 * mgr.connect(new SshConfig("192.168.1.10", 5985, "administrator", "pass", null));
 * mgr.installRemote("app", "C:/opt/app.jar", "java -jar C:/opt/app.jar");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@Spi("winrm")
public class WinRmRemoteServiceManager implements RemoteServiceManager {

    /**
     * 默认远程部署目录。
     */
    private static final String DEFAULT_REMOTE_DIR = "C:/opt/sip-server";

    /**
     * WinRM 命令执行客户端。
     */
    private WinRmExecClient execClient;

    /**
     * WinRM 文件客户端（上传 jar 用）。
     */
    private WinRmFileClient fileClient;

    /**
     * 当前配置。
     */
    private SshConfig config;

    @Override
    public void connect(SshConfig cfg) {
        this.config = cfg;
        WinRmExecClient.Builder builder = WinRmExecClient.builder()
                .host(cfg.host())
                .port(cfg.port())
                .username(cfg.username())
                .password(cfg.password());
        this.execClient = builder.build().connect();
        log.info("[service-remote] WinRM 已连接: {}@{}:{}", cfg.username(), cfg.host(), cfg.port());
    }

    @Override
    public void disconnect() {
        if (fileClient != null) {
            fileClient.closeQuietly();
            fileClient = null;
        }
        if (execClient != null) {
            execClient.close();
            execClient = null;
        }
        config = null;
        log.info("[service-remote] WinRM 已断开");
    }

    @Override
    public boolean isConnected() {
        return execClient != null && execClient.isConnected();
    }

    @Override
    public long startRemote(String serviceName, String jarPath, String startCmd) {
        requireConnected();
        String remotePath = normalizeRemotePath(jarPath);
        uploadJarIfNeeded(jarPath, remotePath);
        String cmd = replaceToken(startCmd, "{jar}", remotePath);
        log.info("[service-remote] 远程启动: {} cmd={}", serviceName, StringUtils.left(cmd, 100));
        return execDetach(cmd);
    }

    @Override
    public void stopRemote(long pid, String serviceName) {
        requireConnected();
        if (pid > 0) {
            log.info("[service-remote] 远程停止: pid={}", pid);
            execAndWait("Stop-Process -Id " + pid + " -Force -ErrorAction SilentlyContinue");
        } else if (serviceName != null) {
            execAndWait("Get-CimInstance Win32_Process -Filter \"Name like '%java%'\" | "
                    + "Where-Object { $_.CommandLine -like '*" + serviceName + "*' } | "
                    + "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }");
        }
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
            String out = execAndWait("(Get-Process -Id " + pid
                    + " -ErrorAction SilentlyContinue) -ne $null");
            return "True".equalsIgnoreCase(out);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void uploadJar(String localPath, String remotePath) {
        requireConnected();
        uploadJarIfNeeded(localPath, remotePath);
    }

    @Override
    public void installRemote(String serviceName, String remoteJarPath, String startCmd) {
        requireConnected();
        // 创建目录并注册 Windows 服务（sc.exe）
        String remoteDir = Path.of(remoteJarPath).getParent().toString()
                .replace("\\", "/");
        execAndWait("New-Item -ItemType Directory -Path \"" + remoteDir
                + "\" -Force | Out-Null");
        execAndWait("sc.exe create \"" + serviceName + "\" binPath= \"" + startCmd
                + "\" start= auto");
        execAndWait("sc.exe description \"" + serviceName + "\" \"" + serviceName + " service\"");
        execAndWait("sc.exe failure \"" + serviceName + "\" reset= 86400 actions= restart/60000");
        log.info("[service-remote] 远程安装完成: {}", serviceName);
    }

    @Override
    public void uninstallRemote(String serviceName) {
        requireConnected();
        execAndWait("sc.exe stop \"" + serviceName + "\" 2>$null");
        execAndWait("Start-Sleep -Seconds 3");
        execAndWait("sc.exe delete \"" + serviceName + "\"");
        log.info("[service-remote] 远程卸载完成: {}", serviceName);
    }

    // ========== 私有方法 ==========

    private void requireConnected() {
        if (execClient == null || !execClient.isConnected()) {
            throw new IllegalStateException("[service-remote] WinRM 未连接，请先调用 connect()");
        }
    }

    private String execAndWait(String cmd) {
        requireConnected();
        try {
            WinRmExecClient.ExecResult r = execClient.exec().command(cmd).execute();
            String out = "".equals(r.stdout()) ? r.stderr() : r.stdout();
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] 命令执行失败: " + cmd, e);
        }
    }

    private long execDetach(String cmd) {
        requireConnected();
        try {
            // PowerShell 后台启动并返回 PID
            String wrapped = "$p = Start-Process -FilePath 'java' -ArgumentList @('-jar', '"
                    + extractJarArg(cmd) + "') -WindowStyle Hidden -PassThru; $p.Id";
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

    private static String extractJarArg(String cmd) {
        // 提取 java -jar <path> 中的 jar 路径
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

    private void uploadJarIfNeeded(String localPath, String remotePath) {
        if (localPath == null || localPath.isBlank()) {
            return;
        }
        try {
            if (!Files.exists(Path.of(localPath))) {
                log.warn("[service-remote] 本地 jar 不存在: {}", localPath);
                return;
            }
            ensureFileClient();
            String remoteDir = Path.of(remotePath).getParent().toString()
                    .replace("\\", "/");
            fileClient.createDirectory(remoteDir, true);
            try (var in = Files.newInputStream(Path.of(localPath))) {
                fileClient.uploadFile(in, remotePath);
            }
            log.info("[service-remote] jar 已上传: {} -> {}", localPath, remotePath);
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] jar 上传失败: " + localPath, e);
        }
    }

    private void ensureFileClient() {
        if (fileClient != null) {
            return;
        }
        ClientSetting setting = new ClientSetting();
        setting.setHost(config.host());
        setting.setPort(config.port());
        setting.setUsername(config.username());
        setting.setPassword(config.password());
        WinRmFileClient client = new WinRmFileClient(setting);
        try {
            client.connect();
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] WinRM 文件客户端连接失败", e);
        }
        this.fileClient = client;
    }

    private static String normalizeRemotePath(String jarPath) {
        if (jarPath == null) {
            return DEFAULT_REMOTE_DIR + "/app.jar";
        }
        String p = jarPath.strip().replace("/", "\\");
        if (p.matches("^[A-Za-z]:.*")) {
            return p;
        }
        return DEFAULT_REMOTE_DIR + "\\" + Path.of(p).getFileName();
    }

    private static String replaceToken(String template, String token, String value) {
        return template == null ? value : template.replace(token, value);
    }
}