package com.chua.runtime.core.service;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.runtime.core.model.ManagedService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
* Linux systemd 系统服务管理器。
*
* @author CH
* @since 4.0.0.42
 */
public class SystemdServiceManager implements ServiceManager {


    /**
    * 日志
     */
    private static final Logger LOG = Logger.getLogger(SystemdServiceManager.class.getName());
    /**
    * systemd 服务目录
     */
    private static final String SYSTEMD_DIR = "/etc/systemd/system";

    /**
    * 命令超时（秒）
     */
    private static final int CMD_TIMEOUT = 30;

    @Override
    /** 名称 */
    public String name() {
        return "systemd";
    }

    @Override
    /** 是否支持 */
    public boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("nix") && !os.contains("nux")) {
            return false;
        }
        return CmdExecutors.execute("which systemctl", 5, TimeUnit.SECONDS).isSuccess();
    }

    @Override
    /** Install */
    public CmdResult install(ManagedService service) {
        LOG.log(Level.INFO, String.format("正在安装 systemd 服务[%s]", service.getServiceName()));
        try {
            String content = generateServiceFile(service);
            String tmp = "/tmp/" + service.getServiceName() + ".service";
            Files.writeString(Path.of(tmp), content);
            Path svc = Paths.get(SYSTEMD_DIR, service.getServiceName() + ".service");
            CmdResult r = CmdExecutors.execute("cp " + tmp + " " + svc, CMD_TIMEOUT, TimeUnit.SECONDS);
            if (!r.isSuccess()) {
                r = CmdExecutors.execute("sudo cp " + tmp + " " + svc, CMD_TIMEOUT, TimeUnit.SECONDS);
            }
            CmdExecutors.execute("rm -f " + tmp, 5, TimeUnit.SECONDS);
            if (!r.isSuccess()) {
                return CmdResult.builder()
                        .exitCode(r.getExitCode())
                        .stderr("无法写入 " + svc + "，请使用 root 运行")
                        .command("install service " + service.getServiceName())
                        .build();
            }
            CmdExecutors.execute("systemctl daemon-reload", CMD_TIMEOUT, TimeUnit.SECONDS);
            if ("auto".equalsIgnoreCase(service.getStartupType())) {
                enable(service.getServiceName());
            }
            LOG.log(Level.INFO, String.format("systemd 服务[%s] 安装成功", service.getServiceName()));
            return CmdResult.builder()
                    .exitCode(0)
                    .stdout("systemd 服务[" + service.getServiceName() + "] 安装成功")
                    .command("install service " + service.getServiceName())
                    .build();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, String.format("生成 service 文件失败", e));
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("生成失败: " + e.getMessage())
                    .command("install service " + service.getServiceName())
                    .throwable(e)
                    .build();
        }
    }

    @Override
    /** Uninstall */
    public CmdResult uninstall(String serviceName) {
        stop(serviceName);
        CmdExecutors.execute("systemctl disable \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
        Path svc = Paths.get(SYSTEMD_DIR, serviceName + ".service");
        CmdResult r = CmdExecutors.execute("rm -f " + svc, CMD_TIMEOUT, TimeUnit.SECONDS);
        if (!r.isSuccess()) {
            CmdExecutors.execute("sudo rm -f " + svc, CMD_TIMEOUT, TimeUnit.SECONDS);
        }
        CmdExecutors.execute("systemctl daemon-reload", CMD_TIMEOUT, TimeUnit.SECONDS);
        LOG.log(Level.INFO, String.format("systemd 服务[%s] 卸载完成", serviceName));
        return r;
    }

    @Override
    /** 开始 */
    public CmdResult start(String serviceName) {
        return CmdExecutors.execute("systemctl start \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    /** 停止 */
    public CmdResult stop(String serviceName) {
        return CmdExecutors.execute("systemctl stop \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    /** Restart */
    public CmdResult restart(String serviceName) {
        return CmdExecutors.execute("systemctl restart \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    /** 状态 */
    public CmdResult status(String serviceName) {
        return CmdExecutors.execute("systemctl status \"" + serviceName + "\" 2>&1", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    /** 启用 */
    public CmdResult enable(String serviceName) {
        return CmdExecutors.execute("systemctl enable \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    /** 禁用 */
    public CmdResult disable(String serviceName) {
        return CmdExecutors.execute("systemctl disable \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    /** 是否已启用 */
    public boolean isEnabled(String serviceName) {
        CmdResult r = CmdExecutors.execute("systemctl is-enabled \"" + serviceName + "\" 2>&1",
                CMD_TIMEOUT, TimeUnit.SECONDS);
        return r.isSuccess() && "enabled".equals(r.getStdout().trim());
    }

    @Override
    /** 是否Installed */
    public boolean isInstalled(String serviceName) {
        Path svc = Paths.get(SYSTEMD_DIR, serviceName + ".service");
        CmdResult r = CmdExecutors.execute(
                "test -f " + svc + " && echo yes || echo no", CMD_TIMEOUT, TimeUnit.SECONDS);
        return r.isSuccess() && "yes".equals(r.getStdout().trim());
    }

    /**
    * generate服务文件
    *
    * @param service 服务
    * @return generate服务文件的结果
     */
    private String generateServiceFile(ManagedService service) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]\n");
        sb.append("Description=").append(service.getDisplayName() != null ? service.getDisplayName() : service.getServiceName()).append("\n");
        for (String dep : service.getDependencies()) {
            sb.append("After=").append(dep).append("\n");
        }
        if (service.getDependencies().isEmpty()) {
            sb.append("After=network.target\n");
        }
        sb.append("\n[Service]\nType=simple\n");
        sb.append("ExecStart=").append(service.getExecutable());
        if (CollectionUtils.isNotEmpty(service.getArgs())) {
            sb.append(" ").append(String.join(" ", service.getArgs()));
        }
        sb.append("\n");
        if (service.getWorkDir() != null && !service.getWorkDir().isBlank()) {
            sb.append("WorkingDirectory=").append(service.getWorkDir()).append("\n");
        }
        if (service.getRunAsUser() != null && !service.getRunAsUser().isBlank()) {
            sb.append("User=").append(service.getRunAsUser()).append("\n");
        }
        if (service.isAutoRestart()) {
            sb.append("Restart=on-failure\nRestartSec=").append(service.getRestartSec()).append("\n");
        }
        if (CollectionUtils.isNotEmpty(service.getEnv())) {
            service.getEnv().forEach((k, v) -> sb.append("Environment=\"").append(k).append("=").append(v).append("\"\n"));
        }
        sb.append("\n[Install]\nWantedBy=multi-user.target\n");
        return sb.toString();
    }
}