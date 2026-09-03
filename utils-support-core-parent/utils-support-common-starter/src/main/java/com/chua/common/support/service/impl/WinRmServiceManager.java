package com.chua.common.support.service.impl;

import com.chua.common.support.service.RemoteServiceManager;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * WinRM 远程服务管理器（通过 PowerShell WSMan 实现）。
 *
 * <p>利用本地 PowerShell 的 {@code Invoke-Command} 执行远程命令，
 * 支持 Kerberos 和 Basic 认证。适用于 Windows 远程管理场景。</p>
 *
 * <h3>前提条件</h3>
 * <ul>
 *   <li>远程主机开启 WinRM（{@code winrm quickconfig}）</li>
 *   <li>本地已配置 TrustedHosts（{@code Set-Item WSMan:\\localhost\\Client\\TrustedHosts -Value "<host>"}）</li>
 *   <li>防火墙允许 5985（HTTP）或 5986（HTTPS）端口</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@SpiDefault
@Spi("winrm")
public class WinRmServiceManager implements RemoteServiceManager {

    private static final String POWERSHELL = "powershell";
    private static final String POWERSHELL_EXE = System.getProperty("com.chua.service.powershell", "powershell.exe");

    private volatile SshConfig config;
    private volatile boolean connected;

    @Override
    public void connect(SshConfig cfg) {
        this.config = cfg;
        try {
            String psCmd = buildConnectScript(cfg);
            String out = runPowerShell(psCmd);
            if (out.contains("True") || out.contains("Success")) {
                connected = true;
                log.info("[service-remote] WinRM 已连接: {}@{}:{}", cfg.username(), cfg.host(), cfg.port());
            } else {
                connected = false;
                log.warn("[service-remote] WinRM 连接失败: {}", out);
            }
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] WinRM 连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        config = null;
        connected = false;
        log.info("[service-remote] WinRM 已断开");
    }

    @Override
    public boolean isConnected() {
        return connected && config != null;
    }

    @Override
    public long startRemote(String serviceName, String jarPath, String startCmd) {
        requireConnected();
        String remotePath = normalizeRemotePath(jarPath);
        uploadJarIfNeeded(jarPath, remotePath);
        String cmd = replaceToken(startCmd, "{jar}", remotePath);
        log.info("[service-remote] 远程启动: {} cmd={}", serviceName, truncate(cmd, 100));
        return execDetach(cmd);
    }

    @Override
    public void stopRemote(long pid, String serviceName) {
        requireConnected();
        if (pid > 0) {
            log.info("[service-remote] 远程停止: pid={}", pid);
            execAndWait("Stop-Process -Id " + pid + " -Force -ErrorAction SilentlyContinue");
        } else if (serviceName != null) {
            execAndWait("Stop-Process -Name java -Force -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*" + serviceName + "*' }");
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
            String out = execAndWait("Get-Process -Id " + pid + " -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id");
            return !out.isBlank();
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
        // Windows Service 安装（sc.exe）
        String createCmd = "sc.exe create \"" + serviceName + "\" binPath= \"" + startCmd + "\" start= auto";
        execAndWait(createCmd);
        execAndWait("sc.exe description \"" + serviceName + "\" \"SIP " + serviceName + " service\"");
        execAndWait("sc.exe failure \"" + serviceName + "\" reset= 86400 actions= restart/60000");
        log.info("[service-remote] WinRM 远程安装完成: {}", serviceName);
    }

    @Override
    public void uninstallRemote(String serviceName) {
        requireConnected();
        execAndWait("sc.exe stop \"" + serviceName + "\" 2>nul");
        execAndWait("timeout /t 3 /nobreak >nul");
        execAndWait("sc.exe delete \"" + serviceName + "\"");
        log.info("[service-remote] WinRM 远程卸载完成: {}", serviceName);
    }

    // ========== 私有方法 ==========

    private void requireConnected() {
        if (!connected || config == null) {
            throw new IllegalStateException("[service-remote] WinRM 未连接，请先调用 connect()");
        }
    }

    private String buildConnectScript(SshConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("$cred = New-Object PSCredential('").append(cfg.username()).append("', (ConvertTo-SecureString '").append(cfg.password() != null ? cfg.password() : "").append("' -AsPlainText -Force));\n");
        sb.append("Test-WSMan -ComputerName ").append(cfg.host()).append(" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty ProductVersion;\n");
        sb.append("$session = New-PSSession -ComputerName ").append(cfg.host()).append(" -Credential $cred -Authentication Basic -ErrorAction SilentlyContinue;\n");
        sb.append("if ($session) { Write-Host 'Connected' } else { Write-Host 'Failed' };\n");
        sb.append("Remove-PSSession -Session $session -ErrorAction SilentlyContinue");
        return sb.toString();
    }

    private String execAndWait(String cmd) {
        requireConnected();
        StringBuilder psCmd = new StringBuilder();
        psCmd.append("$cred = New-Object PSCredential('").append(config.username()).append("', (ConvertTo-SecureString '")
                .append(config.password() != null ? config.password() : "").append("' -AsPlainText -Force));\n");
        psCmd.append("try { Invoke-Command -ComputerName ").append(config.host())
                .append(" -Credential $cred -Authentication Basic -ScriptBlock { ").append(cmd).append(" } -ErrorAction Stop } ");
        psCmd.append("catch { Write-Host $_.Exception.Message }");
        return runPowerShell(psCmd.toString());
    }

    private long execDetach(String cmd) {
        requireConnected();
        // Windows: Start-Process 后台执行，返回 PID
        String wrapped = "Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', '" + cmd
                .replace("'", "'\"'\"'") + "' -WindowStyle Hidden -PassThru | Select-Object -ExpandProperty Id";
        try {
            String out = execAndWait(wrapped);
            try {
                return Long.parseLong(out.trim().split("\\n")[0].trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] 远程启动失败: " + e.getMessage(), e);
        }
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
            // 使用 PowerShell Copy-Item 上传
            String psCmd = "$cred = New-Object PSCredential('" + config.username()
                    + "', (ConvertTo-SecureString '" + (config.password() != null ? config.password() : "")
                    + "' -AsPlainText -Force)); Copy-Item -Path '" + localPath
                    .replace("\\", "\\\\") + "' -Destination '" + remotePath
                    .replace("\\", "\\\\") + "' -ToSession (New-PSSession -ComputerName "
                    + config.host() + " -Credential $cred -Authentication Basic) -Force";
            runPowerShell(psCmd);
            log.info("[service-remote] jar 已上传: {} -> {}", localPath, remotePath);
        } catch (Exception e) {
            log.warn("[service-remote] jar 上传失败: {}", e.getMessage());
        }
    }

    /**
     * 执行 PowerShell 脚本并返回输出。
     */
    private String runPowerShell(String script) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command(POWERSHELL_EXE, "-NoProfile", "-NonInteractive", "-Command", script);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor(30, TimeUnit.SECONDS);
            return sb.toString().trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("[service-remote] PowerShell 执行失败: " + e.getMessage(), e);
        }
    }

    private static String normalizeRemotePath(String jarPath) {
        if (jarPath == null) {
            return "C:\\opt\\app\\app.jar";
        }
        String p = jarPath.strip();
        return p.startsWith("C:\\") || p.startsWith("D:\\") ? p : "C:\\opt\\app\\" + Path.of(p).getFileName();
    }

    private static String replaceToken(String template, String token, String value) {
        return template == null ? value : template.replace(token, value);
    }

    private static String truncate(String s, int maxLen) {
        return s == null ? "" : (s.length() <= maxLen ? s : s.substring(0, maxLen) + "...");
    }
}
