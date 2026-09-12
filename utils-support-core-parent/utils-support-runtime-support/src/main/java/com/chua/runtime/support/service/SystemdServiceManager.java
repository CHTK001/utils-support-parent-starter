package com.chua.runtime.support.service;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
* Linux systemd 系统服务管理器 — 通过 {@code systemctl} 命令管理 systemd 服务。
*
* <p>在 Linux 系统上使用 systemd 的 service 文件注册和管理服务。
* 服务 文件生成到 {@code /etc/systemd/system/} 目录。</p>
*
* <p>SPI 名称：{@code "systemd"}</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("systemd")
public class SystemdServiceManager implements ServiceManager {

    /**
    * systemd 服务 文件目录
     */
    private static final String SYSTEMD_SERVICE_DIR = "/etc/systemd/system";

    /**
    * 命令执行超时（秒）
     */
    private static final int CMD_TIMEOUT_SECONDS = 30;

    @Override
    /** 名称 */
    public String name() {
        return "systemd";
    }

    @Override
    /** 是否支持 */
    public boolean isSupported() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("nix") && !osName.contains("nux") && !osName.contains("aix")) {
            return false;
        }
        CmdResult result = CmdExecutors.execute("which systemctl", 5, TimeUnit.SECONDS);
        return result.isSuccess();
    }

    @Override
    /** Install */
    public CmdResult install(ManagedService service) {
        log.info("[runtime-service] 正在安装 systemd 服务[{}]: {}", service.getServiceName(), service.getDisplayName());

        try {
            String serviceContent = generateServiceFile(service);
            Path servicePath = Paths.get(SYSTEMD_SERVICE_DIR, service.getServiceName() + ".service");

 // 需要 根 权限写入 /etc/systemd/系统
            String tempFile = "/tmp/" + service.getServiceName() + ".service";
            Files.writeString(Path.of(tempFile), serviceContent);

            CmdResult copyResult = CmdExecutors.execute(
                    "cp " + tempFile + " " + servicePath.toString(),
                    CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!copyResult.isSuccess()) {
                log.error("[runtime-service] 复制 service 文件失败: {}", copyResult.getStderr());
                // 尝试用 sudo
                copyResult = CmdExecutors.execute(
                        "sudo cp " + tempFile + " " + servicePath.toString(),
                        CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            // 清理临时文件
            CmdExecutors.execute("rm -f " + tempFile, 5, TimeUnit.SECONDS);

            if (!copyResult.isSuccess()) {
                return CmdResult.builder()
                        .exitCode(copyResult.getExitCode())
                        .stderr("无法写入 " + servicePath + "，请使用 root 或 sudo 运行")
                        .command("install service " + service.getServiceName())
                        .build();
            }

            // 重新加载 systemd 配置
            CmdExecutors.execute("systemctl daemon-reload", CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 设置开机自启
            if ("auto".equalsIgnoreCase(service.getStartupType())) {
                enable(service.getServiceName());
            }

            log.info("[runtime-service] systemd 服务[{}] 安装成功", service.getServiceName());
            return CmdResult.builder()
                    .exitCode(0)
                    .stdout("systemd 服务[" + service.getServiceName() + "] 安装成功")
                    .command("install service " + service.getServiceName())
                    .build();

        } catch (IOException e) {
            log.error("[runtime-service] 生成 service 文件失败", e);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("生成 service 文件失败: " + e.getMessage())
                    .command("install service " + service.getServiceName())
                    .throwable(e)
                    .build();
        }
    }

    @Override
    /** Uninstall */
    public CmdResult uninstall(String serviceName) {
        log.info("[runtime-service] 正在卸载 systemd 服务[{}]", serviceName);

        // 先停止服务
        stop(serviceName);

        // 禁用开机自启
        CmdExecutors.execute("systemctl disable \"" + serviceName + "\"", CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

 // 删除 服务 文件
        Path servicePath = Paths.get(SYSTEMD_SERVICE_DIR, serviceName + ".service");
        CmdResult result = CmdExecutors.execute(
                "rm -f " + servicePath.toString(),
                CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!result.isSuccess()) {
            result = CmdExecutors.execute(
                    "sudo rm -f " + servicePath.toString(),
                    CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        // 重新加载 systemd
        CmdExecutors.execute("systemctl daemon-reload", CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        log.info("[runtime-service] systemd 服务[{}] 卸载完成", serviceName);
        return result;
    }

    @Override
    /** 开始 */
    public CmdResult start(String serviceName) {
        log.info("[runtime-service] 正在启动 systemd 服务[{}]", serviceName);
        String cmd = "systemctl start \"" + serviceName + "\"";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 停止 */
    public CmdResult stop(String serviceName) {
        log.info("[runtime-service] 正在停止 systemd 服务[{}]", serviceName);
        String cmd = "systemctl stop \"" + serviceName + "\"";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** Restart */
    public CmdResult restart(String serviceName) {
        log.info("[runtime-service] 正在重启 systemd 服务[{}]", serviceName);
        String cmd = "systemctl restart \"" + serviceName + "\"";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 状态 */
    public CmdResult status(String serviceName) {
        String cmd = "systemctl status \"" + serviceName + "\" 2>&1";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 启用 */
    public CmdResult enable(String serviceName) {
        log.info("[runtime-service] 设置 systemd 服务[{}] 开机自启", serviceName);
        String cmd = "systemctl enable \"" + serviceName + "\"";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 禁用 */
    public CmdResult disable(String serviceName) {
        log.info("[runtime-service] 禁用 systemd 服务[{}] 开机自启", serviceName);
        String cmd = "systemctl disable \"" + serviceName + "\"";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 是否已启用 */
    public boolean isEnabled(String serviceName) {
        String cmd = "systemctl is-enabled \"" + serviceName + "\" 2>&1";
        CmdResult result = CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return result.isSuccess() && "enabled".equals(result.getStdout().trim());
    }

    @Override
    /** 是否Installed */
    public boolean isInstalled(String serviceName) {
        Path servicePath = Paths.get(SYSTEMD_SERVICE_DIR, serviceName + ".service");
        CmdResult result = CmdExecutors.execute(
                "test -f " + servicePath.toString() + " && echo yes || echo no",
                CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return result.isSuccess() && "yes".equals(result.getStdout().trim());
    }

    /**
    * 生成 systemd 服务 文件内容。
    *
    * @param service 服务配置
    * @return service 文件内容
     */
    private String generateServiceFile(ManagedService service) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]\n");
        sb.append("Description=").append(service.getDisplayName() != null
                ? service.getDisplayName() : service.getServiceName()).append("\n");
        if (service.getDescription() != null && !service.getDescription().isBlank()) {
            sb.append("Documentation=").append(service.getDescription()).append("\n");
        }
        for (String dep : service.getDependencies()) {
            sb.append("After=").append(dep).append("\n");
        }
        if (service.getDependencies().isEmpty()) {
            sb.append("After=network.target\n");
        }
        sb.append("\n");

        sb.append("[Service]\n");
        sb.append("Type=simple\n");
        sb.append("ExecStart=").append(service.getExecutable());
        if (service.getArgs() != null && !service.getArgs().isEmpty()) {
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
            sb.append("Restart=on-failure\n");
            sb.append("RestartSec=").append(service.getRestartSec()).append("\n");
        }
        if (service.getEnv() != null && !service.getEnv().isEmpty()) {
            service.getEnv().forEach((k, v) -> sb.append("Environment=\"").append(k).append("=").append(v).append("\"\n"));
        }
        sb.append("\n");

        sb.append("[Install]\n");
        sb.append("WantedBy=multi-user.target\n");

        return sb.toString();
    }
}