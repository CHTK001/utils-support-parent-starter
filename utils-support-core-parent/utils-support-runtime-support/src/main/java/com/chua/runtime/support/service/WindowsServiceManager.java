package com.chua.runtime.support.service;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 窗口 系统服务管理器 — 基于 {@code sc.exe} 命令管理 窗口 服务。
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
    /**
     * 名称
    */
    public String name() {
        return "windows";
    }

    @Override
    /**
     * 是否支持
    */
    public boolean isSupported() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win");
    }

    @Override
    /**
     * Install
    */
    public CmdResult install(ManagedService service) {
        log.info("[runtime-service] 正在安装 Windows 服务[{}]: {}", service.getServiceName(), service.getDisplayName());

        String executable = service.getExecutable();
        String args = service.getArgs() != null && !service.getArgs().isEmpty()
                ? " " + String.join(" ", service.getArgs())
                : "";

        // sc.exe 要求 "binPath=" 与取值是两个独立参数（等号后必须留空格），
        // 取值整体保持带引号形式以支持含空格的镜像路径
        String startupType = mapStartupType(service.getStartupType());
        CmdResult result = CmdExecutors.execute(
                sc("create",
                        service.getServiceName(),
                        "binPath=", executable + args,
                        "start=", startupType,
                        "DisplayName=",
                        service.getDisplayName() != null ? service.getDisplayName() : service.getServiceName()),
                CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result.isSuccess()) {
            log.info("[runtime-service] Windows 服务[{}] 安装成功", service.getServiceName());

            // 设置服务描述
            if (service.getDescription() != null && !service.getDescription().isBlank()) {
                CmdExecutors.execute(sc("description", service.getServiceName(), service.getDescription()),
                        CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } else {
            log.error("[runtime-service] Windows 服务[{}] 安装失败: {}", service.getServiceName(), result.getStderr());
        }

        return result;
    }

    @Override
    /**
     * Uninstall
    */
    public CmdResult uninstall(String serviceName) {
        log.info("[runtime-service] 正在卸载 Windows 服务[{}]", serviceName);
        CmdResult result = CmdExecutors.execute(sc("delete", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result.isSuccess()) {
            log.info("[runtime-service] Windows 服务[{}] 卸载成功", serviceName);
        } else {
            log.error("[runtime-service] Windows 服务[{}] 卸载失败: {}", serviceName, result.getStderr());
        }

        return result;
    }

    @Override
    /**
     * 开始
    */
    public CmdResult start(String serviceName) {
        log.info("[runtime-service] 正在启动 Windows 服务[{}]", serviceName);
        return CmdExecutors.execute(sc("start", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * 停止
    */
    public CmdResult stop(String serviceName) {
        log.info("[runtime-service] 正在停止 Windows 服务[{}]", serviceName);
        return CmdExecutors.execute(sc("stop", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * Restart
    */
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
    /**
     * 状态
    */
    public CmdResult status(String serviceName) {
        return CmdExecutors.execute(sc("query", serviceName), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * 启用
    */
    public CmdResult enable(String serviceName) {
        log.info("[runtime-service] 设置 Windows 服务[{}] 开机自启", serviceName);
        return CmdExecutors.execute(sc("config", serviceName, "start=", "auto"), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * 禁用
    */
    public CmdResult disable(String serviceName) {
        log.info("[runtime-service] 禁用 Windows 服务[{}] 开机自启", serviceName);
        return CmdExecutors.execute(sc("config", serviceName, "start=", "disabled"), CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    /**
     * 是否已启用
    */
    public boolean isEnabled(String serviceName) {
        CmdResult result = status(serviceName);
        if (!result.isSuccess()) {
            return false;
        }
        String stdout = result.getStdout().toLowerCase();
        return stdout.contains("auto") || stdout.contains("delayed-auto");
    }

    @Override
    /**
     * 是否Installed
    */
    public boolean isInstalled(String serviceName) {
        CmdResult result = status(serviceName);
        return result.isSuccess();
    }

    /**
     * 组装 sc.exe 参数数组，使服务名与描述不经过 shell 二次解析。
     *
     * @param args sc 子命令及其参数
     * @return 命令数组
     */
    private static String[] sc(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "sc";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return cmd;
    }

    /**
     * 将启动类型映射为 sc.exe 的 启动 参数值。
     *
     * @param startupType 启动类型（auto / manual / 已禁用）
     * @return sc.exe 的 启动 参数值
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