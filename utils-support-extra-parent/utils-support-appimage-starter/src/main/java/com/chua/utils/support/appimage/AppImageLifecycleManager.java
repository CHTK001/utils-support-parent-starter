package com.chua.utils.support.appimage;



import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;

/**
 * AppImage 生命周期管理器
 * <p>
 * 负责 AppImage 进程的启动、停止、重启以及健康检查等操作
 *
 * @author CH
 */
@Slf4j
public class AppImageLifecycleManager {

    /** 进程管理器 */
    private final AppImageProcessManager processManager;
    /** 健康检查器 */
    private final AppImageHealthChecker healthChecker;
    /** 定时调度器 */
    private final ScheduledExecutorService scheduler;
    /** 实例映射 */
    private final Map<String, AppImageInstance> instances;

    /*
     * 构造生命周期管理器
     */
    public AppImageLifecycleManager() {
        this.processManager = new AppImageProcessManager();
        this.healthChecker = new AppImageHealthChecker();
        this.scheduler = ThreadUtils.newScheduledThreadPoolExecutor(2);
        this.instances = new ConcurrentHashMap<>();
    }

    /**
     * 启动 AppImage 进程
     *
     * @param config AppImage 运行时配置
     * @return 操作结果
     */
    public AppImageManagementResponse startAppImage(AppImageRuntimeConfig config) {
        if (config == null) {
            return new AppImageManagementResponse(false, "运行时配置不能为空");
        }

        String appImageId = config.getAppImageId();
        log.info("正在启动 AppImage: {}", appImageId);

        AppImageInstance existingInstance = instances.get(appImageId);
        if (existingInstance != null && existingInstance.isRunning()) {
            String msg = "AppImage " + appImageId + " 已在运行，PID: " + existingInstance.getPid();
            log.warn(msg);
            return new AppImageManagementResponse(false, msg, existingInstance.getStatus(), existingInstance.getPid());
        }

        try {
            Process process = processManager.startProcess(config);
            if (process == null) {
                String msg = "AppImage 进程启动失败: " + appImageId;
                log.error(msg);
                return new AppImageManagementResponse(false, msg);
            }

            long pid = process.pid();
            log.info("AppImage 进程已创建: {}，PID: {}", appImageId, pid);

            AppImageInstance instance = new AppImageInstance();
            instance.setId(appImageId);
            instance.setAppName(config.getAppImageName());
            instance.setAppImageFile(new java.io.File(config.getAppImagePath()));
            instance.setWorkDir(config.getWorkDir() != null ? new java.io.File(config.getWorkDir()) : null);
            instance.setLogFile(config.getLogFile() != null ? new java.io.File(config.getLogFile()) : null);
            instance.setPid(pid);
            instance.setStatus(AppImageStatus.STARTING);
            instance.setStartTime(System.currentTimeMillis());
            instance.setRestartCount(0);
            instance.setLastError(null);

            instances.put(appImageId, instance);

            boolean started = waitForStartup(config, instance);
            if (started) {
                instance.setStatus(AppImageStatus.RUNNING);
                log.info("AppImage 启动成功: {}，PID: {}", appImageId, pid);

                if (config.isAutoRestart()) {
                    scheduleHealthCheck(config);
                }

                return new AppImageManagementResponse(true, "启动成功", AppImageStatus.RUNNING, pid);
            } else {
                String msg = "AppImage 启动超时: " + appImageId;
                log.error(msg);
                instance.setStatus(AppImageStatus.ERROR);
                instance.setLastError(msg);
                return new AppImageManagementResponse(false, msg, AppImageStatus.ERROR, pid);
            }
        } catch (Exception e) {
            String msg = "AppImage 启动异常: " + appImageId + "，原因: " + e.getMessage();
            log.error(msg, e);
            if (existingInstance != null) {
                existingInstance.setStatus(AppImageStatus.ERROR);
                existingInstance.setLastError(msg);
            }
            return new AppImageManagementResponse(false, msg);
        }
    }

    /**
     * 停止 AppImage 进程
     *
     * @param appImageId AppImage 实例 ID
     * @return 操作结果
     */
    public AppImageManagementResponse stopAppImage(String appImageId) {
        log.info("正在停止 AppImage: {}", appImageId);

        AppImageInstance instance = instances.get(appImageId);
        if (instance == null) {
            String msg = "AppImage 实例不存在: " + appImageId;
            log.warn(msg);
            return new AppImageManagementResponse(false, msg);
        }

        if (!instance.isRunning() && instance.getStatus() != AppImageStatus.STARTING) {
            String msg = "AppImage " + appImageId + " 当前状态不允许停止: " + instance.getStatus();
            log.warn(msg);
            return new AppImageManagementResponse(false, msg, instance.getStatus());
        }

        try {
            instance.setStatus(AppImageStatus.STOPPING);
            long pid = instance.getPid();

            boolean stopped = processManager.stopProcess(pid, 10000);
            if (stopped) {
                instance.setStatus(AppImageStatus.STOPPED);
                instance.setStopTime(System.currentTimeMillis());
                log.info("AppImage 已停止: {}，PID: {}", appImageId, pid);
                return new AppImageManagementResponse(true, "已停止", AppImageStatus.STOPPED, pid);
            } else {
                String msg = "AppImage 停止失败: " + appImageId + "，PID: " + pid;
                log.error(msg);
                instance.setStatus(AppImageStatus.ERROR);
                instance.setLastError(msg);
                return new AppImageManagementResponse(false, msg, AppImageStatus.ERROR, pid);
            }
        } catch (Exception e) {
            String msg = "AppImage 停止异常: " + appImageId + "，原因: " + e.getMessage();
            log.error(msg, e);
            instance.setStatus(AppImageStatus.ERROR);
            instance.setLastError(msg);
            return new AppImageManagementResponse(false, msg, AppImageStatus.ERROR);
        }
    }

    /**
     * 重启 AppImage 进程
     *
     * @param appImageId AppImage 实例 ID
     * @return 操作结果
     */
    public AppImageManagementResponse restartAppImage(String appImageId) {
        log.info("正在重启 AppImage: {}", appImageId);

        AppImageInstance instance = instances.get(appImageId);
        if (instance == null) {
            String msg = "AppImage 实例不存在: " + appImageId;
            log.warn(msg);
            return new AppImageManagementResponse(false, msg);
        }

        AppImageRuntimeConfig config = getConfigFromInstance(instance);
        if (config == null) {
            String msg = "无法获取 AppImage 运行时配置: " + appImageId;
            log.error(msg);
            return new AppImageManagementResponse(false, msg);
        }

        AppImageManagementResponse stopResponse = stopAppImage(appImageId);
        if (!stopResponse.isSuccess() && instance.getStatus() != AppImageStatus.STOPPED) {
            String msg = "停止 AppImage 失败: " + appImageId + "，原因: " + stopResponse.getMessage();
            log.error(msg);
            return new AppImageManagementResponse(false, msg, instance.getStatus());
        }

        try {
            Thread.sleep(config.getRestartDelay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        instance.setRestartCount(instance.getRestartCount() + 1);

        return startAppImage(config);
    }

    /**
     * 查看 AppImage 运行状态
     *
     * @param appImageId AppImage 实例 ID
     * @return 操作结果
     */
    public AppImageManagementResponse getStatus(String appImageId) {
        AppImageInstance instance = instances.get(appImageId);
        if (instance == null) {
            String msg = "AppImage 实例不存在: " + appImageId;
            log.warn(msg);
            return new AppImageManagementResponse(false, msg);
        }

        if (instance.getPid() > 0) {
            boolean alive = processManager.isProcessAlive(instance.getPid());
            if (!alive && instance.getStatus() == AppImageStatus.RUNNING) {
                instance.setStatus(AppImageStatus.ERROR);
                instance.setLastError("进程意外退出");
                log.warn("AppImage 进程已退出: {}，PID: {}", appImageId, instance.getPid());
            }
        }

        return new AppImageManagementResponse(true, "查询成功", instance.getStatus(), instance.getPid());
    }

    /**
     * 获取所有已注册的 AppImage 实例
     *
     * @return 实例映射
     */
    public Map<String, AppImageInstance> getAllInstances() {
        instances.forEach((id, instance) -> {
            if (instance.getPid() > 0) {
                boolean alive = processManager.isProcessAlive(instance.getPid());
                if (!alive && instance.getStatus() == AppImageStatus.RUNNING) {
                    instance.setStatus(AppImageStatus.ERROR);
                    instance.setLastError("进程意外退出");
                }
            }
        });
        return new ConcurrentHashMap<>(instances);
    }

    /**
     * 等待 AppImage 启动完成
     *
     * @param config   AppImage 运行时配置
     * @param instance AppImage 实例
     * @return true 启动成功，false 超时或失败
     */
    private boolean waitForStartup(AppImageRuntimeConfig config, AppImageInstance instance) {
        long startTime = System.currentTimeMillis();
        long timeout = config.getStartupTimeout();

        log.info("等待 AppImage 启动: {}，超时: {}ms", config.getAppImageId(), timeout);

        while (System.currentTimeMillis() - startTime < timeout) {
            if (!processManager.isProcessAlive(instance.getPid())) {
                log.error("AppImage 进程已退出: {}", config.getAppImageId());
                return false;
            }

            if (healthChecker.checkHealth(config)) {
                log.info("AppImage 健康检查通过: {}", config.getAppImageId());
                return true;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("AppImage 启动超时: {}，超时时间: {}ms", config.getAppImageId(), timeout);
        return false;
    }

    /**
     * 定时执行健康检查
     *
     * @param config AppImage 运行时配置
     */
    private void scheduleHealthCheck(AppImageRuntimeConfig config) {
        if (config.getHealthCheckUrl() == null || config.getHealthCheckUrl().isEmpty()) {
            log.debug("未配置健康检查 URL，跳过自动检查: {}", config.getAppImageId());
            return;
        }

        log.info("开始定时健康检查: {}，间隔: {}ms", config.getAppImageId(), config.getHealthCheckInterval());

        scheduler.scheduleAtFixedRate(() -> {
            try {
                AppImageInstance instance = instances.get(config.getAppImageId());
                if (instance == null || !instance.isRunning()) {
                    log.debug("AppImage 不在运行状态，跳过健康检查: {}", config.getAppImageId());
                    return;
                }

                boolean healthy = healthChecker.checkHealth(config);
                if (!healthy) {
                    log.warn("健康检查失败，准备重启: {}", config.getAppImageId());
                    if (instance.getRestartCount() < config.getMaxRestartAttempts()) {
                        restartAppImage(config.getAppImageId());
                    } else {
                        log.error("达到最大重启次数，不再自动重启: {}", config.getAppImageId());
                    }
                }
            } catch (Exception e) {
                log.error("健康检查异常: {}", config.getAppImageId(), e);
            }
        }, config.getHealthCheckInterval(), config.getHealthCheckInterval(), TimeUnit.MILLISECONDS);
    }

    /**
     * 从实例信息重建运行时配置
     *
     * @param instance AppImage 实例
     * @return 运行时配置
     */
    private AppImageRuntimeConfig getConfigFromInstance(AppImageInstance instance) {
        AppImageRuntimeConfig config = new AppImageRuntimeConfig();
        config.setAppImageId(instance.getId());
        config.setAppImageName(instance.getAppName());
        config.setAppImagePath(instance.getAppImageFile().getAbsolutePath());
        config.setAppImageType(AppImageType.JAVA_APPLICATION);
        if (instance.getWorkDir() != null) {
            config.setWorkDir(instance.getWorkDir().getAbsolutePath());
        }
        if (instance.getLogFile() != null) {
            config.setLogFile(instance.getLogFile().getAbsolutePath());
        }
        return config;
    }

    /**
     * 关闭管理器，停止所有线程和进程
     */
    public void shutdown() {
        log.info("正在关闭 AppImage 生命周期管理器");
        scheduler.shutdownNow();
        instances.forEach((id, instance) -> {
            if (instance.isRunning()) {
                stopAppImage(id);
            }
        });
    }
}