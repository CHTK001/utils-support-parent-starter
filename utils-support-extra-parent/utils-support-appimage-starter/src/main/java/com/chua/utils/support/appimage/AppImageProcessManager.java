package com.chua.utils.support.appimage;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AppImage 进程管理器
 * <p>
 * 提供 AppImage 进程的启动、停止、状态查询等操作，
 * 支持 Windows 和 Linux 平台
 *
 * @author CH
 */
@Slf4j
public class AppImageProcessManager {

    /**
     * 启动 AppImage 进程
     *
     * @param config AppImage 运行时配置
     * @return 启动成功的 Process 对象，失败返回 null
     * @throws IllegalArgumentException 配置参数无效时抛出
     */
    public Process startProcess(AppImageRuntimeConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("运行时配置不能为空");
        }
        if (config.getAppImagePath() == null || config.getAppImagePath().isEmpty()) {
            throw new IllegalArgumentException("AppImage 文件路径不能为空");
        }

        File appImageFile = new File(config.getAppImagePath());
        if (!appImageFile.exists()) {
            log.error("AppImage 文件不存在: {}", config.getAppImagePath());
            return null;
        }
        if (!appImageFile.canExecute()) {
            log.warn("AppImage 文件不可执行，尝试修改权限: {}", config.getAppImagePath());
            if (!appImageFile.setExecutable(true)) {
                log.error("无法设置 AppImage 文件可执行权限: {}", config.getAppImagePath());
                return null;
            }
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(appImageFile.getAbsolutePath());

            if (config.getWorkDir() != null && !config.getWorkDir().isEmpty()) {
                File workDir = new File(config.getWorkDir());
                if (!workDir.exists()) {
                    if (!workDir.mkdirs()) {
                        log.warn("无法创建工作目录: {}", config.getWorkDir());
                    }
                }
                processBuilder.directory(workDir);
            }

            Map<String, String> environment = processBuilder.environment();
            if (config.getEnvironment() != null) {
                environment.putAll(config.getEnvironment());
            }

            processBuilder.redirectErrorStream(true);

            if (config.getLogFile() != null && !config.getLogFile().isEmpty()) {
                File logFile = new File(config.getLogFile());
                File logDir = logFile.getParentFile();
                if (logDir != null && !logDir.exists()) {
                    if (!logDir.mkdirs()) {
                        log.warn("无法创建日志目录: {}", logDir.getAbsolutePath());
                    }
                }
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
                processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
            } else {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            }

            log.info("启动 AppImage 进程: {}，工作目录: {}", appImageFile.getAbsolutePath(),
                    processBuilder.directory() != null ? processBuilder.directory().getAbsolutePath() : "默认");
            Process process = processBuilder.start();

            return process;
        } catch (IOException e) {
            log.error("启动 AppImage 进程失败: {}", config.getAppImagePath(), e);
            return null;
        }
    }

    /**
     * 停止指定进程
     *
     * @param pid     进程 ID
     * @param timeout 等待超时时间（毫秒）
     * @return true 成功停止，false 停止失败
     */
    public boolean stopProcess(long pid, long timeout) {
        if (pid <= 0) {
            log.warn("无效的进程 PID: {}", pid);
            return false;
        }

        java.util.Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);
        if (processHandle.isEmpty()) {
            log.warn("进程不存在: {}", pid);
            return false;
        }

        ProcessHandle handle = processHandle.get();
        if (!handle.isAlive()) {
            log.info("进程已停止: {}", pid);
            return true;
        }

        log.info("正在停止进程: {}", pid);
        handle.destroy();

        try {
            if (handle.onExit().get(timeout, TimeUnit.MILLISECONDS) != null) {
                log.info("进程已成功停止: {}", pid);
                return true;
            }
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("等待进程停止超时: {}", pid);
        } catch (Exception e) {
            log.error("停止进程时出错: {}", pid, e);
        }

        log.warn("强制终止进程: {}", pid);
        handle.destroyForcibly();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean isAlive = handle.isAlive();
        if (isAlive) {
            log.error("进程无法终止: {}", pid);
        } else {
            log.info("进程已强制终止: {}", pid);
        }

        return !isAlive;
    }

    /**
     * 检查进程是否存活
     *
     * @param pid 进程 ID
     * @return true 进程存活
     */
    public boolean isProcessAlive(long pid) {
        if (pid <= 0) {
            return false;
        }
        return ProcessHandle.of(pid)
                .map(ProcessHandle::isAlive)
                .orElse(false);
    }

    /**
     * 获取进程详细信息
     *
     * @param pid 进程 ID
     * @return 进程信息字符串
     */
    public String getProcessInfo(long pid) {
        if (pid <= 0) {
            return "无效 PID: " + pid;
        }
        return ProcessHandle.of(pid)
                .map(handle -> {
                    StringBuilder info = new StringBuilder();
                    info.append("PID: ").append(pid);
                    info.append(", 存活: ").append(handle.isAlive());
                    info.append(", 父进程: ").append(handle.parent().map(p -> String.valueOf(p.pid())).orElse("无"));
                    info.append(", 子进程数: ").append(handle.children().count());
                    info.append(", CPU 耗时: ").append(handle.info().totalCpuDuration().map(d -> d.toMillis() + "ms").orElse("未知"));
                    info.append(", 启动时间: ").append(handle.info().startInstant().map(Object::toString).orElse("未知"));
                    return info.toString();
                })
                .orElse("进程不存在: " + pid);
    }
}