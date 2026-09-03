package com.chua.common.support.service.impl;

import com.chua.common.support.service.RemoteServiceManager;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * SSH 远程服务管理器（反射实现，运行时动态加载 ssh-starter）。
 *
 * <p>本实现不直接依赖 Apache MINA SSHD；通过反射在运行时查找并调用
 * {@code com.chua.ssh.support.client.SshClient}。
 * 若 ssh-starter 不在 classpath 中，则所有远程操作返回 -1 并记录警告。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@SpiDefault
@Spi("ssh")
public class SshServiceManager implements RemoteServiceManager {

    private static final String SSH_CLIENT_CLASS = "com.chua.ssh.support.client.SshClient";
    private static final String SFTP_CLIENT_CLASS = "com.chua.ssh.support.client.SftpClient";
    private static final String DEFAULT_REMOTE_JAR = "/opt/app/app.jar";

    /** SSH 客户端实例（通过反射持有） */
    private Object sshClient;
    /** SFTP 客户端实例（通过反射持有） */
    private Object sftpClient;
    /** 配置 */
    private SshConfig config;

    @Override
    public void connect(SshConfig cfg) {
        this.config = cfg;
        try {
            Class<?> cls = Class.forName(SSH_CLIENT_CLASS);
            Object builder = cls.getMethod("builder").invoke(null);
            builder = invoke(builder, "host", cfg.host());
            builder = invoke(builder, "port", cfg.port());
            builder = invoke(builder, "username", cfg.username());
            if (cfg.password() != null && !cfg.password().isBlank()) {
                builder = invoke(builder, "password", cfg.password());
            }
            if (cfg.privateKeyPath() != null && !cfg.privateKeyPath().isBlank()) {
                builder = invoke(builder, "privateKey", cfg.privateKeyPath());
            }
            this.sshClient = cls.getMethod("build").invoke(builder);
            cls.getMethod("connect").invoke(sshClient);
            log.info("[service-remote] SSH 已连接: {}@{}:{}", cfg.username(), cfg.host(), cfg.port());
        } catch (ClassNotFoundException e) {
            log.warn("[service-remote] ssh-starter 未在 classpath 中，远程功能不可用: {}", e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] SSH 连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        if (sshClient != null) {
            try {
                Class<?> cls = sshClient.getClass();
                cls.getMethod("close").invoke(sshClient);
            } catch (Exception ignored) {
            }
        }
        sshClient = null;
        sftpClient = null;
        config = null;
        log.info("[service-remote] SSH 已断开");
    }

    @Override
    public boolean isConnected() {
        if (sshClient == null) {
            return false;
        }
        try {
            return (boolean) sshClient.getClass().getMethod("isConnected").invoke(sshClient);
        } catch (Exception e) {
            return false;
        }
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
            execAndWait("kill " + pid);
        } else if (serviceName != null) {
            execAndWait("pkill -f " + serviceName);
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
        execAndWait("cat > /etc/systemd/system/" + serviceName + ".service << 'UNIT_EOF'\n" + unitContent + "\nUNIT_EOF");
        execAndWait("systemctl daemon-reload && systemctl enable " + serviceName);
        log.info("[service-remote] 远程安装完成: {}", serviceName);
    }

    @Override
    public void uninstallRemote(String serviceName) {
        requireConnected();
        execAndWait("systemctl disable " + serviceName + " 2>/dev/null; systemctl stop " + serviceName + " 2>/dev/null");
        execAndWait("rm -f /etc/systemd/system/" + serviceName + ".service && systemctl daemon-reload");
        log.info("[service-remote] 远程卸载完成: {}", serviceName);
    }

    // ========== 私有方法 ==========

    private void requireConnected() {
        if (sshClient == null) {
            throw new IllegalStateException("[service-remote] SSH 未连接，请先调用 connect()");
        }
    }

    private String execAndWait(String cmd) {
        requireConnected();
        try {
            Object result = sshClient.getClass().getMethod("exec").invoke(sshClient);
            Object execBuilder = result.getClass().getMethod("command", String.class).invoke(result, cmd);
            Object execResult = execBuilder.getClass().getMethod("execute").invoke(execBuilder);
            if (execResult == null) {
                return "";
            }
            Method getOutput = execResult.getClass().getMethod("getOutput");
            Object out = getOutput.invoke(execResult);
            return out != null ? out.toString().trim() : "";
        } catch (Exception e) {
            throw new RuntimeException("[service-remote] 命令执行失败: " + cmd, e);
        }
    }

    private long execDetach(String cmd) {
        requireConnected();
        try {
            // 远程后台执行并获取 PID：cmd & disown; echo $!
            String wrapped = cmd + " & disown; echo $!";
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
            // 通过 SftpClient 上传
            Object sftp = sshClient.getClass().getMethod("sftp").invoke(sshClient);
            sftp.getClass().getMethod("mkdir", String.class).invoke(sftp, Path.of(remotePath).getParent().toString());
            sftp.getClass().getMethod("put", String.class, String.class).invoke(sftp, localPath, remotePath);
            sftp.getClass().getMethod("close").invoke(sftp);
            log.info("[service-remote] jar 已上传: {} -> {}", localPath, remotePath);
        } catch (ClassNotFoundException e) {
            log.warn("[service-remote] SFTP 不可用，跳过上传: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[service-remote] jar 上传失败，可能已存在: {}", e.getMessage());
        }
    }

    private static String normalizeRemotePath(String jarPath) {
        if (jarPath == null) {
            return DEFAULT_REMOTE_JAR;
        }
        String p = jarPath.strip();
        return p.startsWith("/") ? p : "/opt/app/" + Path.of(p).getFileName();
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
               "ExecStart=" + startCmd + "\n" +
               "Restart=on-failure\n" +
               "RestartSec=5\n\n" +
               "[Install]\n" +
               "WantedBy=multi-user.target";
    }

    /**
     * 通用反射调用助手：调用对象的带参方法并返回结果。
     */
    @SuppressWarnings("unchecked")
    private static Object invoke(Object obj, String methodName, Object... args) throws Exception {
        Class<?> cls = obj.getClass();
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        return cls.getMethod(methodName, paramTypes).invoke(obj, args);
    }

    private static String truncate(String s, int maxLen) {
        return s == null ? "" : (s.length() <= maxLen ? s : s.substring(0, maxLen) + "...");
    }
}
