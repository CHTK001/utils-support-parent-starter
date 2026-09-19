package com.chua.runtime.support.javaagent;

import com.chua.runtime.support.RuntimeManager;
import com.chua.runtime.support.DefaultRuntimeManager;
import com.chua.runtime.support.model.RuntimeArtifact;
import com.chua.runtime.support.model.RuntimeStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时上下文持有者 — 通过 智能体 注入后提供全局运行时管理器访问入口。
 *
 * <p>当 RuntimeAgent 通过 premain 或 agentmain 加载到 JVM 后，
 * 会将 runtime管理器 实例存入本上下文，应用可通过静态方法随时获取。</p>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * RuntimeManager manager = RuntimeContextHolder.getManager();
 * if (manager != null) {
 *     RuntimeStatus status = manager.status("my-app");
 *     System.out.println("状态: " + status);
 * }
 *
 * // 直接注册工件
 * RuntimeContextHolder.register(RuntimeArtifact.builder()
 *         .id("my-app")
 *         .name("My Application")
 *         .type(RuntimeType.JAR)
 *         .executable(Paths.get("/opt/app/app.jar"))
 *         .build());
 *
 * // 启动
 * RuntimeContextHolder.start("my-app");
 * }</pre> 启动
 * runtime上下文holder.启动("my-app");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuntimeContextHolder {

    /**
     * 全局 runtime管理器 实例
     */
    private static final AtomicReference<RuntimeManager> managerRef = new AtomicReference<>(null);

    /**
     * 快捷工件注册表
     */
    private static final Map<String, RuntimeArtifact> quickArtifacts = new ConcurrentHashMap<>();

    /** 创建 runtime上下文holder 实例 */
    private RuntimeContextHolder() {
    }

    /**
    * 设置全局 runtime管理器 实例。
    *
    * @param manager runtime管理器 实例
     */
    public static void setManager(RuntimeManager manager) {
        RuntimeManager old = managerRef.getAndSet(manager);
        if (old != null) {
            try {
                old.close();
            } catch (Exception e) {
                // 旧实例关闭异常
            }
        }
    }

    /**
     * 获取全局 runtime管理器 实例。
     *
     * @return RuntimeManager 实例，未初始化返回 空
     */
    public static RuntimeManager getManager() {
        return managerRef.get();
    }

    /**
     * 获取或创建全局 runtime管理器 实例。
     *
     * @return RuntimeManager 实例
     */
    public static RuntimeManager getOrCreateManager() {
        RuntimeManager manager = managerRef.get();
        if (manager == null) {
            synchronized (RuntimeContextHolder.class) {
                manager = managerRef.get();
                if (manager == null) {
                    manager = new DefaultRuntimeManager();
                    managerRef.set(manager);
                }
            }
        }
        return manager;
    }

    /**
     * 快捷注册工件。
     *
     * @param artifact 工件描述
     */
    public static void register(RuntimeArtifact artifact) {
        RuntimeManager manager = getOrCreateManager();
        manager.register(artifact);
        quickArtifacts.put(artifact.getId(), artifact);
    }

    /**
     * 快捷获取工件。
     *
     * @param id 工件 标识
     * @return 工件描述
     */
    public static RuntimeArtifact getArtifact(String id) {
        return quickArtifacts.get(id);
    }

    /**
     * 快捷启动工件。
     *
     * @param id 工件 标识
     * @return 运行时实例
     */
    public static com.chua.runtime.support.RuntimeInstance start(String id) {
        return getOrCreateManager().start(id);
    }

    /**
     * 快捷停止工件。
     *
     * @param id 工件 标识
     * @return 运行时实例
     */
    public static com.chua.runtime.support.RuntimeInstance stop(String id) {
        return getOrCreateManager().stop(id);
    }

    /**
     * 快捷重启工件。
     *
     * @param id 工件 标识
     * @return 运行时实例
     */
    public static com.chua.runtime.support.RuntimeInstance restart(String id) {
        return getOrCreateManager().restart(id);
    }

    /**
     * 快捷获取工件状态。
     *
     * @param id 工件 标识
     * @return 运行时状态
     */
    public static RuntimeStatus getStatus(String id) {
        return getOrCreateManager().status(id);
    }

    /**
     * 快捷查询工件是否正在运行。
     *
     * @param id 工件 标识
     * @return 运行中返回 true
     */
    public static boolean isRunning(String id) {
        return getStatus(id) == RuntimeStatus.RUNNING;
    }

    /**
     * 快捷订阅工件实时日志。
     *
     * @param id       工件 标识
     * @param callback 日志行回调
     */
    public static void tailLog(String id, com.chua.common.support.lang.cmd.LineCallback callback) {
        getOrCreateManager().tailLog(id, callback);
    }

    /**
     * 快捷查询所有工件 标识。
     *
     * @return 工件 标识 列表
     */
    public static java.util.List<String> listArtifactIds() {
        return getOrCreateManager().getArtifactIds();
    }

    /**
     * 快捷查询所有运行中实例。
     *
     * @return 运行时实例列表
     */
    public static java.util.List<com.chua.runtime.support.RuntimeInstance> listRunningInstances() {
        return getOrCreateManager().getRunningInstances();
    }

    /**
     * 关闭全局 runtime管理器。
     */
    public static void shutdown() throws Exception {
        RuntimeManager manager = managerRef.get();
        if (manager != null) {
            manager.close();
            managerRef.set(null);
            quickArtifacts.clear();
        }
    }
}