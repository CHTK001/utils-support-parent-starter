package com.chua.runtime.support.service;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
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

    /**
     * 让 systemd 重新读取单元文件的固定参数
     */
    private static final String[] SYSTEMCTL_RELOAD = {"systemctl", "daemon-reload"};

    @Override
    /**
     * 名称
    */
    public String name() {
        return "systemd";
    }

    @Override
    /**
     * 是否支持
    */
    public boolean isSupported() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("nix") && !osName.contains("nux") && !osName.contains("aix")) {
            return false;
        }
        CmdResult result = CmdExecutors.execute(new String[]{"which", "systemctl"}, 5, TimeUnit.SECONDS);
        return result.isSuccess();
    }

    @Override
    /**
     * Install
    */
    public CmdResult install(ManagedService service) {
        log.info("[runtime-service] 正在安装 systemd 服务[{}]: {}", service.getServiceName(), service.getDisplayName());

        try {
            String serviceContent = generateServiceFile(service);
            Path servicePath = unitFile(service.getServiceName());

 // 需要 根 权限写入 /etc/systemd/系统
            String tempFile = "/tmp/" + service.getServiceName() + ".service";
            Files.writeString(Path.of(tempFile), serviceContent);

            CmdResult copyResult = CmdExecutors.execute(
                    new String[]{"cp", tempFile, servicePath.toString()},
                    CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!copyResult.isSuccess()) {
                log.error("[runtime-service] 复制 service 文件失败: {}", copyResult.getStderr());
                // 尝试用 sudo
                copyResult = CmdExecutors.execute(
                        new String[]{"sudo", "cp", tempFile, servicePath.toString()},
                        CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            // 清理临时文件
            CmdExecutors.execute(new String[]{"rm", "-f", tempFile}, 5, TimeUnit.SECONDS);

            if (!copyResult.isSuccess()) {
                return CmdResult.builder()
                        .exitCode(copyResult.getExitCode())
                        .stderr("无法写入 " + servicePath + "，请使用 root 或 sudo 运行")
                        .command("install service " + service.getServiceName())
                        .build();
            }

            // 重新加载 systemd 配置
            CmdExecutors.execute(SYSTEMCTL_RELOAD, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

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
    /**
     * Uninstall
    */
    public CmdResult uninstall(String serviceName) {
        log.info("[runtime-service] 正在卸载 systemd 服务[{}]", serviceName);
        Path servicePath = unitFile(serviceName);

        // 先停止服务
        stop(serviceName);

        // 禁用开机自启
        CmdExecutors.execute(systemctl("disable", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 删除 服务 文件
        CmdResult result = CmdExecutors.execute(
                new String[]{"rm", "-f", servicePath.toString()},
                CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!result.isSuccess()) {
            result = CmdExecutors.execute(
                    new String[]{"sudo", "rm", "-f", servicePath.toString()},
                    CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        // 重新加载 systemd
        CmdExecutors.execute(SYSTEMCTL_RELOAD, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        log.info("[runtime-service] systemd 服务[{}] 卸载完成", serviceName);
        return result;
    }

    @Override
    /**
     * 开始
    */
    public CmdResult start(String serviceName) {
        log.info("[runtime-service] 正在启动 systemd 服务[{}]", serviceName);
        return CmdExecutors.execute(systemctl("start", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * 停止
    */
    public CmdResult stop(String serviceName) {
        log.info("[runtime-service] 正在停止 systemd 服务[{}]", serviceName);
        return CmdExecutors.execute(systemctl("stop", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * Restart
    */
    public CmdResult restart(String serviceName) {
        log.info("[runtime-service] 正在重启 systemd 服务[{}]", serviceName);
        return CmdExecutors.execute(systemctl("restart", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * 状态
    */
    public CmdResult status(String serviceName) {
        // 状态输出含错误流，走已合并 stderr 的实时输出通道
        return CmdExecutors.executeWithOutput(systemctl("status", serviceName),
                CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS, new LineCallback() {
                });
    }

    @Override
    /**
     * 启用
    */
    public CmdResult enable(String serviceName) {
        log.info("[runtime-service] 设置 systemd 服务[{}] 开机自启", serviceName);
        return CmdExecutors.execute(systemctl("enable", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * 禁用
    */
    public CmdResult disable(String serviceName) {
        log.info("[runtime-service] 禁用 systemd 服务[{}] 开机自启", serviceName);
        return CmdExecutors.execute(systemctl("disable", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * 是否已启用
    */
    public boolean isEnabled(String serviceName) {
        CmdResult result = CmdExecutors.executeWithOutput(systemctl("is-enabled", serviceName),
                CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS, new LineCallback() {
                });
        return result.isSuccess() && "enabled".equals(result.getStdout().trim());
    }

    @Override
    /**
     * 是否Installed
    */
    public boolean isInstalled(String serviceName) {
        Path servicePath = unitFile(serviceName);
        return Files.isRegularFile(servicePath);
    }

    /**
     * 组装 systemctl 子命令，服务名独占一个参数位。
     *
     * @param verb 子命令
     * @param serviceName 服务名
     * @return 进程参数数组
     */
    private static String[] systemctl(String verb, String serviceName) {
        return new String[]{"systemctl", verb, serviceName};
    }

    /**
     * 解析 systemd 单元文件路径，并拒绝含路径分隔符的服务名。
     *
     * <p>服务名会拼进单元文件路径并交给 {@code cp} / {@code rm -f}，若允许 {@code ../}
     * 就能读写删除 {@code /etc/systemd/system} 之外的任意文件。</p>
     *
     * @param serviceName 服务名
     * @return 单元文件路径
     */
    private static Path unitFile(String serviceName) {
        if (serviceName == null || serviceName.isBlank()
                || serviceName.indexOf('/') >= 0 || serviceName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("非法的 systemd 服务名: " + serviceName);
        }
        return Paths.get(SYSTEMD_SERVICE_DIR, serviceName + ".service");
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