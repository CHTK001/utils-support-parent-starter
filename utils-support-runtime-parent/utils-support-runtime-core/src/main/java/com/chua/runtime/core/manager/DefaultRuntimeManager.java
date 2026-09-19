package com.chua.runtime.core.manager;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.network.download.Downloader;
import com.chua.runtime.core.model.LogStream;
import com.chua.runtime.core.model.ManagedService;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.core.model.RuntimeStatus;
import com.chua.runtime.core.service.JavaAgentManager;
import com.chua.runtime.core.service.ServiceManager;
import com.chua.runtime.core.service.SystemdServiceManager;
import com.chua.runtime.core.service.WindowsServiceManager;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 默认运行时管理器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultRuntimeManager implements RuntimeManager {


    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(DefaultRuntimeManager.class.getName());
    /**
     * 工件注册表
     */
    private final ConcurrentMap<String, RuntimeArtifact> artifactMap;

    /**
     * 实例表
     */
    private final ConcurrentMap<String, RuntimeInstance> instanceMap;

    /**
     * 服务管理器
     */
    private volatile ServiceManager serviceManager;

    /**
     * 智能体 管理器
     */
    private volatile JavaAgentManager javaAgentManager;

    /** 创建 默认runtime管理器 实例 */
    public DefaultRuntimeManager() {
        this.artifactMap = new ConcurrentHashMap<>();
        this.instanceMap = new ConcurrentHashMap<>();
    }

    @Override
    /** 注册 */
    public RuntimeArtifact register(RuntimeArtifact artifact) {
        if (artifactMap.containsKey(artifact.getId())) {
            throw new IllegalArgumentException("工件已存在: " + artifact.getId());
        }
        artifactMap.put(artifact.getId(), artifact);
        return artifact;
    }

    @Override
    /** 注册或替换 */
    public RuntimeArtifact registerOrReplace(RuntimeArtifact artifact) {
        RuntimeInstance old = instanceMap.get(artifact.getId());
        if (old != null) {
            try {
                old.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, String.format("关闭旧实例异常", e));
            }
            instanceMap.remove(artifact.getId());
        }
        return artifactMap.put(artifact.getId(), artifact);
    }

    @Override
    /** 注销 */
    public boolean unregister(String id) {
        RuntimeInstance inst = instanceMap.get(id);
        if (inst != null) {
            try {
                inst.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, String.format("关闭异常", e));
            }
            instanceMap.remove(id);
        }
        RuntimeArtifact removed = artifactMap.remove(id);
        return removed != null;
    }

    @Override
    /** 获取Artifact */
    public RuntimeArtifact getArtifact(String id) {
        return artifactMap.get(id);
    }

    @Override
    /** Download */
    public CompletableFuture<Boolean> download(String id, LineCallback callback) {
        RuntimeArtifact art = artifactMap.get(id);
        if (art == null) {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("工件不存在: " + id));
            return f;
        }
        String url = art.getDownloadUrl();
        if (url == null || url.isBlank()) {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("未配置下载 URL"));
            return f;
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path targetDir = art.getWorkDir() != null ? art.getWorkDir() : Path.of(System.getProperty("user.dir"));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                String filename = art.getDownloadFilename() != null && !art.getDownloadFilename().isBlank()
                        ? art.getDownloadFilename() : url.substring(url.lastIndexOf('/') + 1);
                Path targetFile = targetDir.resolve(filename);

                if (callback != null) {
                    callback.onLine("[下载] 开始: " + url);
                }

                Downloader dl = Downloader.create()
                        .url(url).target(targetDir).filename(filename).forceDownload(true);
                if (art.getExpectedMd5() != null && !art.getExpectedMd5().isBlank()) {
                    dl.expectedMd5(art.getExpectedMd5());
                }
                if (art.isAutoExtract()) {
                    dl.autoExtract(true);
                    if (art.getExtractTo() != null) {
                        dl.extractTo(art.getExtractTo());
                    }
                }
                dl.execute();

                RuntimeArtifact updated = RuntimeArtifact.builder()
                        .id(art.getId()).name(art.getName()).type(art.getType())
                        .executable(targetFile).workDir(art.getWorkDir()).downloadUrl(url)
                        .downloadFilename(art.getDownloadFilename())
                        .args(art.getArgs()).env(art.getEnv())
                        .startupTimeoutMs(art.getStartupTimeoutMs())
                        .healthCheckUrl(art.getHealthCheckUrl())
                        .healthCheckCommand(art.getHealthCheckCommand())
                        .expectedMd5(art.getExpectedMd5()).autoExtract(art.isAutoExtract())
                        .extractTo(art.getExtractTo()).autoRestart(art.isAutoRestart())
                        .maxRestartAttempts(art.getMaxRestartAttempts()).build();
                artifactMap.put(id, updated);

                if (callback != null) {
                    callback.onLine("[下载] 完成: " + targetFile);
                }
                return true;
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("下载失败", e));
                if (callback != null) {
                    callback.onError("download", e);
                }
                throw new RuntimeException("下载失败: " + e.getMessage(), e);
            }
        });
    }

    @Override
    /** 开始 */
    public RuntimeInstance start(String id) {
        RuntimeArtifact art = artifactMap.get(id);
        if (art == null) {
            throw new IllegalArgumentException("工件不存在: " + id);
        }
        RuntimeInstance inst = instanceMap.computeIfAbsent(id, k -> new DefaultRuntimeInstance(art));
        inst.start();
        return inst;
    }

    @Override
    /** 停止 */
    public RuntimeInstance stop(String id) {
        RuntimeInstance inst = instanceMap.get(id);
        if (inst != null) {
            inst.stop();
        }
        return inst;
    }

    @Override
    /** Restart */
    public RuntimeInstance restart(String id) {
        RuntimeInstance inst = instanceMap.get(id);
        if (inst != null) {
            return inst;
        }
        return start(id);
    }

    @Override
    /** 状态 */
    public RuntimeStatus status(String id) {
        RuntimeInstance inst = instanceMap.get(id);
        if (inst != null) {
            return inst.status();
        }
        if (artifactMap.containsKey(id)) {
            return RuntimeStatus.STOPPED;
        }
        return RuntimeStatus.UNKNOWN;
    }

    @Override
    /** Tail记录日志 */
    public void tailLog(String id, LineCallback callback) {
        RuntimeInstance inst = instanceMap.get(id);
        if (inst == null) {
            throw new IllegalArgumentException("未启动: " + id);
        }
        inst.logStream().subscribe(callback);
    }

    @Override
    /** 获取artifact标识 */
    public List<String> getArtifactIds() {
        return new ArrayList<>(artifactMap.keySet());
    }

    @Override
    /** 获取runninginstances */
    public List<RuntimeInstance> getRunningInstances() {
        List<RuntimeInstance> list = new ArrayList<>();
        for (RuntimeInstance i : instanceMap.values()) {
            if (i.status() == RuntimeStatus.RUNNING) {
                list.add(i);
            }
        }
        return list;
    }

    @Override
    /** 获取Instance */
    public RuntimeInstance getInstance(String id) {
        return instanceMap.get(id);
    }

    @Override
    /** 获取服务管理器 */
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

    @Override
    /** 获取java智能体管理器 */
    public JavaAgentManager getJavaAgentManager() {
        if (javaAgentManager == null) {
            synchronized (this) {
                if (javaAgentManager == null) {
 // 默认: 创建 a 简单 implementation
                    javaAgentManager = new com.chua.runtime.core.manager.DefaultJavaAgentManager();
                }
            }
        }
        return javaAgentManager;
    }

    @Override
    /** installas服务 */
    public CmdResult installAsService(String id, ManagedService service) {
        RuntimeArtifact art = artifactMap.get(id);
        if (art == null) {
            return CmdResult.builder().exitCode(CmdResult.EXIT_CODE_ERROR).stderr("工件不存在: " + id).build();
        }
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder().exitCode(CmdResult.EXIT_CODE_ERROR).stderr("无可用服务管理器").build();
        }
        if (service.getExecutable() == null && art.getExecutable() != null) {
            ManagedService updated = ManagedService.builder()
                    .serviceType(service.getServiceType())
                    .serviceName(service.getServiceName())
                    .displayName(service.getDisplayName() != null ? service.getDisplayName() : art.getName())
                    .description(service.getDescription() != null ? service.getDescription() : art.getName())
                    .artifactId(id)
                    .executable(art.getExecutable().toAbsolutePath().toString())
                    .args(service.getArgs() != null ? service.getArgs() : art.getArgs())
                    .workDir(service.getWorkDir() != null ? service.getWorkDir()
                            : (art.getWorkDir() != null ? art.getWorkDir().toString() : null))
                    .env(service.getEnv() != null ? service.getEnv() : art.getEnv())
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

    @Override
    /** uninstall服务 */
    public CmdResult uninstallService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder().exitCode(1).stderr("无服务管理器").build();
        }
        return sm.uninstall(serviceName);
    }

    @Override
    /** 开始服务 */
    public CmdResult startService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder().exitCode(1).stderr("无服务管理器").build();
        }
        return sm.start(serviceName);
    }

    @Override
    /** 停止服务 */
    public CmdResult stopService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder().exitCode(1).stderr("无服务管理器").build();
        }
        return sm.stop(serviceName);
    }

    @Override
    /** restart服务 */
    public CmdResult restartService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder().exitCode(1).stderr("无服务管理器").build();
        }
        return sm.restart(serviceName);
    }

    @Override
    /** 服务状态 */
    public CmdResult serviceStatus(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder().exitCode(1).stderr("无服务管理器").build();
        }
        return sm.status(serviceName);
    }

    @Override
    /** 启用服务 */
    public CmdResult enableService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder().exitCode(1).stderr("无服务管理器").build();
        }
        return sm.enable(serviceName);
    }

    @Override
    /** 禁用服务 */
    public CmdResult disableService(String serviceName) {
        ServiceManager sm = getServiceManager();
        if (sm == null) {
            return CmdResult.builder().exitCode(1).stderr("无服务管理器").build();
        }
        return sm.disable(serviceName);
    }

    @Override
    /** attach转为jvm */
    public CmdResult attachToJvm(int pid, Path agentPath, String options) {
        LOG.log(Level.INFO, String.format("正在注入 Agent 到 PID[%s]...", pid));
        try {
            Class<?> vmClass = ReflectUtils.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = ReflectUtils.invokeStatic(vmClass, "attach", Object.class, new Class<?>[]{String.class}, String.valueOf(pid));
            ReflectUtils.invoke(vm, "loadAgent", void.class, new Class<?>[]{String.class, Object.class}, agentPath.toAbsolutePath().toString(), options);
            ReflectUtils.invoke(vm, "detach", void.class);
            return CmdResult.builder().exitCode(0).stdout("Agent 注入成功: PID[" + pid + "]").build();
        } catch (Exception e) {
            return CmdResult.builder().exitCode(CmdResult.EXIT_CODE_ERROR).stderr(e.getMessage()).throwable(e).build();
        }
    }

    @Override
    /** 列表java处理 */
    public Map<Integer, String> listJavaProcesses() {
        Map<Integer, String> jvms = new java.util.LinkedHashMap<>();
        try {
            CmdResult r = CmdExecutors.execute(
                    "ps -eo pid,comm,args | grep -E '[j]ava|sun.tools.launcher' | grep -v grep",
                    10, TimeUnit.SECONDS);
            if (r.isSuccess()) {
                for (String line : r.getStdout().split("\n")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            jvms.put(Integer.parseInt(parts[0]), line.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, String.format("列出 Java 进程失败", e));
        }
        return jvms;
    }

    /**
     * discover服务管理器
     *
     * @return discover服务管理器的结果
     */
    private ServiceManager discoverServiceManager() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new WindowsServiceManager();
        }
        CmdResult r = CmdExecutors.execute("which systemctl", 5, TimeUnit.SECONDS);
        if (r.isSuccess()) {
            return new SystemdServiceManager();
        }
        return null;
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        for (RuntimeInstance i : instanceMap.values()) {
            try {
                i.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, String.format("关闭异常", e));
            }
        }
        instanceMap.clear();
        artifactMap.clear();
    }
}