package com.chua.runtime.core.service;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.runtime.core.model.ManagedService;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Windows 系统服务管理器。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WindowsServiceManager implements ServiceManager {

    /**
     * 命令超时（秒）
     */
    private static final int CMD_TIMEOUT = 30;

    @Override
    public String name() {
        return "windows";
    }

    @Override
    public boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    @Override
    public CmdResult install(ManagedService service) {
        log.info("正在安装 Windows 服务[{}]", service.getServiceName());
        String exec = service.getExecutable();
        String args = service.getArgs() != null && !service.getArgs().isEmpty()
                ? " " + String.join(" ", service.getArgs()) : "";
        String startup = mapStartup(service.getStartupType());
        String cmd = String.format("sc create \"%s\" binPath= \"%s%s\" start= %s DisplayName= \"%s\"",
                service.getServiceName(), exec, args, startup,
                service.getDisplayName() != null ? service.getDisplayName() : service.getServiceName());
        CmdResult result = CmdExecutors.execute(cmd, CMD_TIMEOUT, TimeUnit.SECONDS);
        if (result.isSuccess()) {
            log.info("Windows 服务[{}] 安装成功", service.getServiceName());
            if (service.getDescription() != null && !service.getDescription().isBlank()) {
                CmdExecutors.execute(
                        "sc description \"" + service.getServiceName() + "\" \"" + service.getDescription() + "\"",
                        CMD_TIMEOUT, TimeUnit.SECONDS);
            }
        } else {
            log.error("Windows 服务[{}] 安装失败: {}", service.getServiceName(), result.getStderr());
        }
        return result;
    }

    @Override
    public CmdResult uninstall(String serviceName) {
        log.info("正在卸载 Windows 服务[{}]", serviceName);
        return CmdExecutors.execute("sc delete \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    public CmdResult start(String serviceName) {
        return CmdExecutors.execute("sc start \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    public CmdResult stop(String serviceName) {
        return CmdExecutors.execute("sc stop \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    public CmdResult restart(String serviceName) {
        stop(serviceName);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return start(serviceName);
    }

    @Override
    public CmdResult status(String serviceName) {
        return CmdExecutors.execute("sc query \"" + serviceName + "\"", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    public CmdResult enable(String serviceName) {
        return CmdExecutors.execute("sc config \"" + serviceName + "\" start= auto", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    public CmdResult disable(String serviceName) {
        return CmdExecutors.execute("sc config \"" + serviceName + "\" start= disabled", CMD_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    public boolean isEnabled(String serviceName) {
        CmdResult r = status(serviceName);
        if (!r.isSuccess()) return false;
        String s = r.getStdout().toLowerCase();
        return s.contains("auto") || s.contains("delayed-auto");
    }

    @Override
    public boolean isInstalled(String serviceName) {
        return status(serviceName).isSuccess();
    }

    private String mapStartup(String type) {
        if (type == null) return "auto";
        return switch (type.toLowerCase()) {
            case "manual" -> "demand";
            case "disabled" -> "disabled";
            default -> "auto";
        };
    }
}