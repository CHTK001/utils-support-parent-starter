package com.chua.runtime.support.service;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Windows 系统服务管理器 — 基于 {@code sc.exe} 命令管理 Windows 服务。
 *
 * <p>支持 Windows XP 及以上版本，通过系统自带的 sc.exe 工具实现服务安装、
 * 卸载、启动、停止、查询状态等操作。不支持开机自启类型设置（sc.exe 原生能力有限）。</p>
 *
 * <p>SPI 名称：{@code "windows"}</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("windows")
public class WindowsServiceManager implements ServiceManager {

    /**
     * 命令执行超时（秒）
     */
    private static final int CMD_TIMEOUT_SECONDS = 30;

    @Override
    /** Name */
    public String name() {
        return "windows";
    }

    @Override
    /** 是否Supported */
    public boolean isSupported() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win");
    }

    @Override
    /** Install */
    public CmdResult install(ManagedService service) {
        log.info("[runtime-service] 正在安装 Windows 服务[{}]: {}", service.getServiceName(), service.getDisplayName());

        String executable = service.getExecutable();
        String args = service.getArgs() != null && !service.getArgs().isEmpty()
                ? " " + String.join(" ", service.getArgs())
                : "";

        // sc create <ServiceName> binPath= "<executable> <args>" start= <startupType> DisplayName= "<displayName>"
        String startupType = mapStartupType(service.getStartupType());
        String cmd = String.format(
                "sc create \"%s\" binPath= \"%s%s\" start= %s DisplayName= \"%s\"",
                service.getServiceName(),
                executable,
                args,
                startupType,
                service.getDisplayName() != null ? service.getDisplayName() : service.getServiceName()
        );

        CmdResult result = CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result.isSuccess()) {
            log.info("[runtime-service] Windows 服务[{}] 安装成功", service.getServiceName());

            // 设置服务描述
            if (service.getDescription() != null && !service.getDescription().isBlank()) {
                String descCmd = String.format("sc description \"%s\" \"%s\"",
                        service.getServiceName(), service.getDescription());
                CmdExecutors.execute(descCmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } else {
            log.error("[runtime-service] Windows 服务[{}] 安装失败: {}", service.getServiceName(), result.getStderr());
        }

        return result;
    }

    @Override
    /** Uninstall */
    public CmdResult uninstall(String serviceName) {
        log.info("[runtime-service] 正在卸载 Windows 服务[{}]", serviceName);
        String cmd = "sc delete \"" + serviceName + "\"";
        CmdResult result = CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result.isSuccess()) {
            log.info("[runtime-service] Windows 服务[{}] 卸载成功", serviceName);
        } else {
            log.error("[runtime-service] Windows 服务[{}] 卸载失败: {}", serviceName, result.getStderr());
        }

        return result;
    }

    @Override
    /** 开始 */
    public CmdResult start(String serviceName) {
        log.info("[runtime-service] 正在启动 Windows 服务[{}]", serviceName);
        String cmd = "sc start \"" + serviceName + "\"";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 停止 */
    public CmdResult stop(String serviceName) {
        log.info("[runtime-service] 正在停止 Windows 服务[{}]", serviceName);
        String cmd = "sc stop \"" + serviceName + "\"";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** Restart */
    public CmdResult restart(String serviceName) {
        log.info("[runtime-service] 正在重启 Windows 服务[{}]", serviceName);
        CmdResult stopResult = stop(serviceName);
        if (!stopResult.isSuccess()) {
            log.warn("[runtime-service] Windows 服务[{}] 停止异常，继续尝试启动", serviceName);
        }
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return start(serviceName);
    }

    @Override
    /** Status */
    public CmdResult status(String serviceName) {
        String cmd = "sc query \"" + serviceName + "\"";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 启用 */
    public CmdResult enable(String serviceName) {
        log.info("[runtime-service] 设置 Windows 服务[{}] 开机自启", serviceName);
        String cmd = "sc config \"" + serviceName + "\" start= auto";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 禁用 */
    public CmdResult disable(String serviceName) {
        log.info("[runtime-service] 禁用 Windows 服务[{}] 开机自启", serviceName);
        String cmd = "sc config \"" + serviceName + "\" start= disabled";
        return CmdExecutors.execute(cmd, CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /** 是否Enabled */
    public boolean isEnabled(String serviceName) {
        CmdResult result = status(serviceName);
        if (!result.isSuccess()) {
            return false;
        }
        String stdout = result.getStdout().toLowerCase();
        return stdout.contains("auto") || stdout.contains("delayed-auto");
    }

    @Override
    /** 是否Installed */
    public boolean isInstalled(String serviceName) {
        CmdResult result = status(serviceName);
        return result.isSuccess();
    }

    /**
     * 将启动类型映射为 sc.exe 的 start 参数值。
     *
     * @param startupType 启动类型（auto / manual / disabled）
     * @return sc.exe 的 start 参数值
     */
    private String mapStartupType(String startupType) {
        if (startupType == null) {
            return "auto";
        }
        return switch (startupType.toLowerCase()) {
            case "manual" -> "demand";
            case "disabled" -> "disabled";
            default -> "auto";
        };
    }
}