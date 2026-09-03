package com.chua.common.support.service.impl;

import com.chua.common.support.service.ServiceManager;
import com.chua.common.support.service.ServiceProcessTracker;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 本地进程级服务管理器（默认实现）。
 *
 * <p>使用 {@link ServiceProcessTracker} 追踪 Java 进程 PID，
 * 支持通过 PID 文件或进程名停止服务。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@SpiDefault
@Spi("process")
public class LocalServiceManager implements ServiceManager {

    private static final String DEFAULT_PID_DIR = System.getProperty("java.io.tmpdir") + "/sip-services";
    private static final String NAME_FALLBACK = "unknown-service";

    private final ServiceProcessTracker tracker;

    public LocalServiceManager(ServiceProcessTracker tracker) {
        this.tracker = tracker;
    }

    public LocalServiceManager() {
        this(new PidFileProcessTracker());
    }

    @Override
    public long start(String jarPath, String startCmd) {
        return start(jarPath, startCmd, null);
    }

    @Override
    public long start(String jarPath, String startCmd, String pidFile) {
        if (pidFile == null || pidFile.isBlank()) {
            pidFile = DEFAULT_PID_DIR + "/" + extractName(jarPath) + ".pid";
        }
        try {
            Path dir = Path.of(pidFile).getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            log.info("[service] 启动服务: {} cmd={}", jarPath, truncate(startCmd, 80));
            long pid = tracker.startProcess(extractName(jarPath), startCmd, pidFile);
            if (pid > 0 && pidFile != null) {
                tracker.writePidToFile(pidFile, pid);
            }
            return pid;
        } catch (IOException e) {
            throw new RuntimeException("[service] 启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(long pid, String serviceName) {
        if (pid > 0) {
            log.info("[service] 停止进程: pid={}", pid);
            tracker.kill(pid);
        } else if (serviceName != null && !serviceName.isBlank()) {
            long fallback = tracker.findPidByName(serviceName);
            if (fallback > 0) {
                log.info("[service] 停止进程（按名称）: name={} pid={}", serviceName, fallback);
                tracker.kill(fallback);
            }
        }
    }

    @Override
    public void restart(long pid, String serviceName, String jarPath, String startCmd) {
        stop(pid, serviceName);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        start(jarPath, startCmd);
    }

    @Override
    public boolean isRunning(long pid) {
        return pid > 0 && tracker.isRunning(pid);
    }

    @Override
    public long findPidByName(String serviceName) {
        return tracker.findPidByName(serviceName);
    }

    @Override
    public void install(String serviceName, String jarPath, String startCmd) {
        log.info("[service] install 无需操作（进程级管理）");
    }

    @Override
    public void uninstall(String serviceName) {
        log.info("[service] uninstall 无需操作（进程级管理）");
    }

    private static String extractName(String jarPath) {
        if (jarPath == null) {
            return NAME_FALLBACK;
        }
        int lastSlash = Math.max(jarPath.lastIndexOf('/'), jarPath.lastIndexOf('\\'));
        String name = lastSlash >= 0 ? jarPath.substring(lastSlash + 1) : jarPath;
        return name.replace(".jar", "").replace(".exe", "");
    }

    private static String truncate(String s, int maxLen) {
        return s == null ? "" : (s.length() <= maxLen ? s : s.substring(0, maxLen) + "...");
    }
}
