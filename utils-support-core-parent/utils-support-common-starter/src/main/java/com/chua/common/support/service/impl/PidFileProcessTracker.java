package com.chua.common.support.service.impl;

import com.chua.common.support.service.ServiceProcessTracker;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PID 文件式进程追踪器（默认实现）。
 *
 * <p>启动时记录 PID 到文件，后续通过 {@code kill PID} 和 {@code kill -0 PID} 管理进程。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@SpiDefault
@Spi("pidfile")
public class PidFileProcessTracker implements ServiceProcessTracker {

    private final ConcurrentHashMap<String, Long> pidCache = new ConcurrentHashMap<>(); // pid缓存

    @Override
    public long startProcess(String serviceName, String startCmd, String pidFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            String shell = isWindows() ? "cmd.exe" : "sh";
            String arg = isWindows() ? "/c" : "-c";
            pb.command(shell, arg, startCmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            long pid = detectPid(process, serviceName);
            if (pidFile != null && !pidFile.isBlank() && pid > 0) {
                writePidToFile(pidFile, pid);
            }
            pidCache.put(serviceName, pid);
            log.info("[service] 进程已启动: name={} pid={}", serviceName, pid);
            return pid;
        } catch (IOException e) {
            throw new RuntimeException("[service] 启动进程失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void kill(long pid) {
        if (pid <= 0) {
            return;
        }
        try {
            String cmd = isWindows() ? "taskkill /PID " + pid + " /F" : "kill " + pid;
            Runtime.getRuntime().exec(cmd).waitFor();
            log.info("[service] 进程已停止: pid={}", pid);
        } catch (Exception e) {
            log.warn("[service] 停止进程失败 pid={}: {}", pid, e.getMessage());
        } finally {
            pidCache.remove(String.valueOf(pid));
        }
    }

    @Override
    public void killForce(long pid) {
        kill(pid);
    }

    @Override
    public boolean isRunning(long pid) {
        if (pid <= 0) {
            return false;
        }
        try {
            String cmd = isWindows() ? "tasklist /FI \"PID eq " + pid + "\"" : "kill -0 " + pid + " 2>&1";
            Process p = Runtime.getRuntime().exec(cmd);
            int exit = p.waitFor();
            boolean running = isWindows()
                    ? exit == 0
                    : exit == 0;
            return running;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long readPidFromFile(String pidFile) {
        if (pidFile == null || pidFile.isBlank()) {
            return -1;
        }
        try {
            String content = Files.readString(Path.of(pidFile)).trim();
            return Long.parseLong(content);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void writePidToFile(String pidFile, long pid) {
        try {
            Path path = Path.of(pidFile);
            Files.createDirectories(path.getParent());
            Files.writeString(path, String.valueOf(pid),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("[service] PID 文件写入失败: {}", e.getMessage());
        }
    }

    @Override
    public void deletePidFile(String pidFile) {
        if (pidFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(pidFile));
        } catch (IOException ignored) {
        }
    }

    @Override
    public long findPidByName(String serviceName) {
        Long cached = pidCache.get(serviceName);
        if (cached != null && isRunning(cached)) {
            return cached;
        }
        try {
            String cmd = isWindows()
                    ? "wmic process where \"CommandLine like '% " + serviceName + "%'\" get ProcessId"
                    : "pgrep -f " + serviceName;
            Process p = Runtime.getRuntime().exec(cmd);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.matches("\\d+")) {
                    continue;
                }
                long pid = Long.parseLong(line);
                pidCache.put(serviceName, pid);
                return pid;
            }
        } catch (Exception e) {
            log.debug("[service] 按名称查找 PID 失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 检测进程 PID（跨平台兼容）。
     * @param process 进程
     * @param serviceName 服务名称
     * @return 检测到的 PID
     */
    private long detectPid(Process process, String serviceName) {
        long pid = process.pid();
        if (pid > 0) {
            pidCache.put(serviceName, pid);
        }
        return pid;
    }

    /**
     * 是否Windows。
     *
     * @return 是否成功（true 表示成功）
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
