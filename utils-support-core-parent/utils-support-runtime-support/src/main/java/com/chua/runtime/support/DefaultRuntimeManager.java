package com.chua.runtime.support;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.lang.cmd.RuntimeType;
import com.chua.common.support.network.download.Downloader;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.runtime.support.model.LogStream;
import com.chua.runtime.support.model.RuntimeArtifact;
import com.chua.runtime.support.model.RuntimeStatus;
import com.chua.runtime.support.service.ManagedService;
import com.chua.runtime.support.service.ServiceManager;
import com.chua.runtime.support.javaagent.DefaultJavaAgentManager;
import com.chua.runtime.support.javaagent.JavaAgentManager;
import com.chua.runtime.support.javaagent.AgentInjector;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认运行时管理器实现 — 基于 {@link ConcurrentHashMap} 管理工件注册表和运行时实例。
 *
 * <p>所有操作线程安全，支持并发注册、启动和停止多个工件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultRuntimeManager implements RuntimeManager {

    /**
     * 工件注册表
     */
    private final ConcurrentMap<String, RuntimeArtifact> artifactMap;

    /**
     * 运行时实例表
     */
    private final ConcurrentMap<String, RuntimeInstance> instanceMap;

    /**
     * 系统服务管理器（延迟初始化）
     */
    private volatile ServiceManager serviceManager;

    /**
     * Java 智能体 管理器（延迟初始化）
     */
    private volatile JavaAgentManager javaAgentManager;

    /**
     * 创建空的运行时管理器。
     */
    public DefaultRuntimeManager() {
        this.artifactMap = new ConcurrentHashMap<>();
        this.instanceMap = new ConcurrentHashMap<>();
    }

    /**
     * 注册
    */
    @Override
    public RuntimeArtifact register(RuntimeArtifact artifact) {
        String id = artifact.getId();
        if (artifactMap.containsKey(id)) {
            throw new IllegalArgumentException("工件已存在: " + id);
        }
        artifactMap.put(id, artifact);
        log.info("[runtime] 注册工件[{}]: {}", id, artifact.getName());
        return artifact;
    }

    /**
     * 注册或替换
    */
    @Override
    public RuntimeArtifact registerOrReplace(RuntimeArtifact artifact) {
        String id = artifact.getId();
        RuntimeInstance oldInstance = instanceMap.get(id);
        if (oldInstance != null) {
            try {
                oldInstance.close();
            } catch (Exception e) {
                log.warn("[runtime] 关闭旧实例[{}] 异常", id, e);
            }
            instanceMap.remove(id);
        }
        artifactMap.put(id, artifact);
        log.info("[runtime] 注册/替换工件[{}]: {}", id, artifact.getName());
        return artifact;
    }

    /**
     * 注销
    */
    @Override
    public boolean unregister(String id) {
        RuntimeInstance instance = instanceMap.get(id);
        if (instance != null) {
            try {
                instance.close();
            } catch (Exception e) {
                log.warn("[runtime] 关闭实例[{}] 异常", id, e);
            }
            instanceMap.remove(id);
        }
        RuntimeArtifact removed = artifactMap.remove(id);
        if (removed != null) {
            log.info("[runtime] 注销工件[{}]: {}", id, removed.getName());
            return true;
        }
        return false;
    }

    /**
     * 获取Artifact
    */
    @Override
    public RuntimeArtifact getArtifact(String id) {
        return artifactMap.get(id);
    }

    /**
     * Download
    */
    @Override
    public CompletableFuture<Boolean> download(String id, LineCallback callback) {
        RuntimeArtifact artifact = artifactMap.get(id);
        if (artifact == null) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("工件不存在: " + id));
            return future;
        }

        String downloadUrl = artifact.getDownloadUrl();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("工件[" + id + "] 未配置 downloadUrl"));
            return future;
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path targetDir = artifact.getWorkDir() != null
                        ? artifact.getWorkDir()
                        : Path.of(System.getProperty("user.dir"));

                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }

                String filename = artifact.getDownloadFilename();
                if (filename == null || filename.isBlank()) {
                    filename = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
                }
                Path targetFile = targetDir.resolve(filename);

                if (callback != null) {
                    callback.onLine("[下载] 开始下载: " + downloadUrl);
                    callback.onLine("[下载] 目标文件: " + targetFile);
                }

                Downloader downloader = Downloader.create()
                        .url(downloadUrl)
                        .target(targetDir)
                        .filename(filename)
                        .forceDownload(true);

                if (artifact.getExpectedMd5() != null && !artifact.getExpectedMd5().isBlank()) {
                    downloader.expectedMd5(artifact.getExpectedMd5());
                }
                if (artifact.isAutoExtract()) {
                    downloader.autoExtract(true);
                    if (artifact.getExtractTo() != null) {
                        downloader.extractTo(artifact.getExtractTo());
                    }
                }

                downloader.execute();

                // 更新工件的 executable 指向下载后的文件
                RuntimeArtifact updated = RuntimeArtifact.builder()
                        .id(artifact.getId())
                        .name(artifact.getName())
                        .type(artifact.getType())
                        .executable(targetFile)
                        .workDir(artifact.getWorkDir())
                        .downloadUrl(artifact.getDownloadUrl())
                        .downloadFilename(artifact.getDownloadFilename())
                        .args(artifact.getArgs())
                        .env(artifact.getEnv())
                        .startupTimeoutMs(artifact.getStartupTimeoutMs())
                        .healthCheckUrl(artifact.getHealthCheckUrl())
                        .healthCheckCommand(artifact.getHealthCheckCommand())
                        .expectedMd5(artifact.getExpectedMd5())
                        .autoExtract(artifact.isAutoExtract())
                        .extractTo(artifact.getExtractTo())
                        .autoRestart(artifact.isAutoRestart())
                        .maxRestartAttempts(artifact.getMaxRestartAttempts())
                        .build();

                artifactMap.put(id, updated);

                if (callback != null) {
                    callback.onLine("[下载] 下载完成: " + targetFile);
                }
                return true;

            } catch (Exception e) {
                log.error("[runtime] 下载工件[{}] 失败", id, e);
                if (callback != null) {
                    callback.onError("download", e);
                }
                throw new RuntimeException("下载失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 开始
    */
    @Override
    public RuntimeInstance start(String id) {
        RuntimeArtifact artifact = artifactMap.get(id);
        if (artifact == null) {
            throw new IllegalArgumentException("工件不存在: " + id);
        }

        RuntimeInstance instance = instanceMap.computeIfAbsent(id, k -> new DefaultRuntimeInstance(artifact));
        instance.start();
        return instance;
    }

    /**
     * 停止
    */
    @Override
    public RuntimeInstance stop(String id) {
        RuntimeInstance instance = instanceMap.get(id);
        if (instance != null) {
            instance.stop();
        } else {
            log.warn("[runtime] 工件[{}] 未在运行，无需停止", id);
        }
        return instance;
    }

    /**
     * Restart
    */
    @Override
    public RuntimeInstance restart(String id) {
        RuntimeInstance instance = instanceMap.get(id);
        if (instance != null) {
            instance.restart();
        } else {
            return start(id);
        }
        return instance;
    }

    /**
     * 状态
    */
    @Override
    public RuntimeStatus status(String id) {
        RuntimeInstance instance = instanceMap.get(id);
        if (instance != null) {
            return instance.status();
        }
        if (artifactMap.containsKey(id)) {
            return RuntimeStatus.STOPPED;
        }
        return RuntimeStatus.UNKNOWN;
    }

    /**
     * Tail记录日志
    */
    @Override
    public void tailLog(String id, LineCallback callback) {
        RuntimeInstance instance = instanceMap.get(id);
        if (instance == null) {
            throw new IllegalArgumentException("工件[" + id + "] 未启动，无法获取日志");
        }
        instance.logStream().subscribe(callback);
    }

    /**
     * 获取artifact标识
    */
    @Override
    public List<String> getArtifactIds() {
        return Wraps.list(artifactMap.keySet());
    }

    /**
     * 获取runninginstances
    */
    @Override
    public List<RuntimeInstance> getRunningInstances() {
        List<RuntimeInstance> running = Wraps.list();
        for (RuntimeInstance instance : instanceMap.values()) {
            if (instance.status() == RuntimeStatus.RUNNING) {
                running.add(instance);
            }
        }
        return running;
    }

    /**
     * 获取Instance
    */
    @Override
    public RuntimeInstance getInstance(String id) {
        return instanceMap.get(id);
    }

    /**
     * 获取服务管理器
    */
    @Override
    public ServiceManager getServiceManager() {
        if (serviceManager == null) {
            synchronized (this) {
                if (serviceManager == null) {
                    serviceManager = discoverServiceManager();
                }
            }
        }
        return serviceManager;
    }

    /**
     * installas服务
    */
    @Override
    public CmdResult installAsService(String id, ManagedService service) {
        RuntimeArtifact artifact = artifactMap.get(id);
        if (artifact == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("工件不存在: " + id)
                    .command("install service " + id)
                    .build();
        }

        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("当前平台无可用系统服务管理器")
                    .command("install service " + id)
                    .build();
        }

        // 使用工件的 executable 填充服务配置
        if (service.getExecutable() == null && artifact.getExecutable() != null) {
            ManagedService updated = ManagedService.builder()
                    .serviceType(service.getServiceType())
                    .serviceName(service.getServiceName())
                    .displayName(service.getDisplayName() != null ? service.getDisplayName() : artifact.getName())
                    .description(service.getDescription() != null ? service.getDescription() : artifact.getName())
                    .artifactId(id)
                    .executable(artifact.getExecutable().toAbsolutePath().toString())
                    .args(service.getArgs() != null ? service.getArgs() : artifact.getArgs())
                    .workDir(service.getWorkDir() != null ? service.getWorkDir()
                            : (artifact.getWorkDir() != null ? artifact.getWorkDir().toString() : null))
                    .env(service.getEnv() != null ? service.getEnv() : artifact.getEnv())
                    .startupType(service.getStartupType())
                    .runAsUser(service.getRunAsUser())
                    .dependencies(service.getDependencies())
                    .autoRestart(service.isAutoRestart())
                    .restartSec(service.getRestartSec())
                    .build();
            return sm.install(updated);
        }

        return sm.install(service);
    }

    /**
     * uninstall服务
    */
    @Override
    public CmdResult uninstallService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("当前平台无可用系统服务管理器")
                    .command("uninstall service " + serviceName)
                    .build();
        }
        return sm.uninstall(serviceName);
    }

    /**
     * 开始服务
    */
    @Override
    public CmdResult startService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("当前平台无可用系统服务管理器")
                    .command("start service " + serviceName)
                    .build();
        }
        return sm.start(serviceName);
    }

    /**
     * 停止服务
    */
    @Override
    public CmdResult stopService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("当前平台无可用系统服务管理器")
                    .command("stop service " + serviceName)
                    .build();
        }
        return sm.stop(serviceName);
    }

    /**
     * restart服务
    */
    @Override
    public CmdResult restartService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("当前平台无可用系统服务管理器")
                    .command("restart service " + serviceName)
                    .build();
        }
        return sm.restart(serviceName);
    }

    /**
     * 服务状态
    */
    @Override
    public CmdResult serviceStatus(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("当前平台无可用系统服务管理器")
                    .command("status service " + serviceName)
                    .build();
        }
        return sm.status(serviceName);
    }

    /**
     * 启用服务
    */
    @Override
    public CmdResult enableService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("当前平台无可用系统服务管理器")
                    .command("enable service " + serviceName)
                    .build();
        }
        return sm.enable(serviceName);
    }

    /**
     * 禁用服务
    */
    @Override
    public CmdResult disableService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("当前平台无可用系统服务管理器")
                    .command("disable service " + serviceName)
                    .build();
        }
        return sm.disable(serviceName);
    }

    /**
     * 通过 SPI 自动发现当前平台可用的 服务管理器 实现。
     *
     * @return ServiceManager 实例，无可用实现返回 空
     */
    private ServiceManager discoverServiceManager() {
        java.util.Map<String, ServiceManager> managers = ServiceProvider.of(ServiceManager.class).list();
        for (ServiceManager sm : managers.values()) {
            if (sm.isSupported()) {
                log.info("[runtime] 使用系统服务管理器: {}", sm.name());
                return sm;
            }
        }
        log.warn("[runtime] 未找到当前平台支持的系统服务管理器");
        return null;
    }

    /**
     * 获取Java智能体管理器
    */
    @Override
    public JavaAgentManager getJavaAgentManager() {
        if (javaAgentManager == null) {
            synchronized (this) {
                if (javaAgentManager == null) {
                    javaAgentManager = new DefaultJavaAgentManager();
                }
            }
        }
        return javaAgentManager;
    }

    /**
     * attach转为jvm
    */
    @Override
    public CmdResult attachToJvm(int pid, String options) {
        return getJavaAgentManager().attach(pid, null, options);
    }

    /**
     * 列表Java处理
    */
    @Override
    public java.util.Map<Integer, String> listJavaProcesses() {
        return AgentInjector.listJavaProcesses();
    }

    /**
     * 关闭
    */
    @Override
    public void close() throws Exception {
        log.info("[runtime] 关闭运行时管理器，停止所有实例...");
        for (RuntimeInstance instance : instanceMap.values()) {
            try {
                instance.close();
            } catch (Exception e) {
                log.warn("[runtime] 关闭实例异常", e);
            }
        }
        instanceMap.clear();
        artifactMap.clear();
        log.info("[runtime] 运行时管理器已关闭");
    }
}