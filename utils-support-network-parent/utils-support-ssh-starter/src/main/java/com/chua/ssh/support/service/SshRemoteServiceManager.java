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
            execAndWait("kill " + pid + " 2>/dev/null || echo ok");
        } else if (serviceName != null) {
            execAndWait("pkill -f " + serviceName + " 2>/dev/null || echo ok");
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
            String out = execAndWait("kill -0 " + pid + " 2>&1 && echo alive || echo dead");
            return out.contains("alive");
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
        String unitContent = buildSystemdUnit(serviceName, remoteJarPath, startCmd);
        // 单引号括号避免 shell 变量/命令替换
        execAndWait("cat > /etc/systemd/system/" + serviceName + ".service << 'UNIT_EOF'\n"
                + unitContent + "\nUNIT_EOF");
        execAndWait("systemctl daemon-reload && systemctl enable " + serviceName);
        log.info("[service-remote] 远程安装完成: {}", serviceName);
    }

    @Override
    public void uninstallRemote(String serviceName) {
        requireConnected();
        execAndWait("systemctl disable " + serviceName + " 2>/dev/null; systemctl stop "
                + serviceName + " 2>/dev/null");
        execAndWait("rm -f /etc/systemd/system/" + serviceName + ".service && systemctl daemon-reload");
        log.info("[service-remote] 远程卸载完成: {}", serviceName);
    }

    // ========== 私有方法 ==========

    private void requireConnected() {
        if (sshClient == null || !sshClient.isConnected()) {
            throw new IllegalStateException("[service-remote] SSH 未连接，请先调用 connect()");
        }
    }

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
            String remoteDir = Path.of(remotePath).getParent().toString();
            sftpClient.mkdir().path(remoteDir).recursive(true).exec();
            sftpClient.upload().local(localPath).remote(remotePath).exec();
            log.info("[service-remote] jar 已上传: {} -> {}", localPath, remotePath);
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] jar 上传失败: " + localPath, e);
        }
    }

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

    private static String normalizeRemotePath(String jarPath) {
        if (jarPath == null) {
            return DEFAULT_REMOTE_DIR + "/app.jar";
        }
        String p = jarPath.strip();
        if (p.startsWith("/")) {
            return p;
        }
        return DEFAULT_REMOTE_DIR + "/" + Path.of(p).getFileName();
    }

    private static String replaceToken(String template, String token, String value) {
        return template == null ? value : template.replace(token, value);
    }

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