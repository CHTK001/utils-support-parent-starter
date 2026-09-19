package com.chua.runtime.support;

import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.runtime.support.model.RuntimeArtifact;
import com.chua.runtime.support.model.RuntimeStatus;
import com.chua.runtime.support.service.ManagedService;
import com.chua.runtime.support.service.ServiceManager;
import com.chua.runtime.support.javaagent.JavaAgentManager;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 运行时管理器 — 管理所有可运行工件的注册、下载、生命周期和日志。
 *
 * <p>核心功能：</p>
 * <ul>
 *   <li><strong>注册/注销</strong> — 注册工件到管理器，支持按 ID 查询</li>
 *   <li><strong>下载</strong> — 从远程 URL 下载工件到本地</li>
 *   <li><strong>生命周期</strong> — 启动、停止、重启单个工件</li>
 *   <li><strong>状态查询</strong> — 查询所有工件或单个工件的运行状态</li>
 *   <li><strong>实时日志</strong> — 通过回调订阅工件的实时日志</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * RuntimeManager manager = new DefaultRuntimeManager();
 *
 * // 注册工件
 * manager.register(RuntimeArtifact.builder()
 *         .id("my-app")
 *         .name("My Application")
 *         .type(RuntimeType.JAR)
 *         .executable(Paths.get("/opt/app/my-app.jar"))
 *         .args(List.of("--server.port=8080"))
 *         .build());
 *
 * // 启动
 * manager.start("my-app");
 *
 * // 查看状态
 * RuntimeStatus status = manager.status("my-app");
 *
 * // 订阅实时日志
 * manager.tailLog("my-app", line -> System.out.println("[LOG] " + line));
 *
 * // 停止
 * manager.stop("my-app");
 * }</pre>日志
 * manager.tailLog("my-app", line -> System.out.println("[LOG] " + line));
 *
 * // 停止
 * manager.stop("my-app");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RuntimeManager extends AutoCloseable {

    /**
     * 注册一个工件到管理器。
     *
     * @param artifact 工件描述
     * @return 已注册的工件
     * @throws IllegalArgumentException 如果 标识 已存在
     */
    RuntimeArtifact register(RuntimeArtifact artifact);

    /**
     * 注册或替换已有工件。
     *
     * @param artifact 工件描述
     * @return 已注册的工件
     */
    RuntimeArtifact registerOrReplace(RuntimeArtifact artifact);

    /**
     * 注销指定工件。
     *
     * <p>如果工件正在运行，会先停止再注销。</p>
     *
     * @param id 工件 标识
     * @return 如果存在并成功注销返回 true
     */
    boolean unregister(String id);

    /**
     * 根据 标识 获取工件。
     *
     * @param id 工件 标识
     * @return 工件描述，不存在返回 空
     */
    RuntimeArtifact getArtifact(String id);

    /**
     * 下载工件到本地。
     *
     * <p>根据工件的 downloadUrl 下载到 executable 路径。
     * 支持进度回调，可自动解压。</p>
     *
     * @param id       工件 标识
     * @param callback 下载进度回调
     * @return 下载完成后的 期货
     */
    CompletableFuture<Boolean> download(String id, LineCallback callback);

    /**
     * 启动指定工件。
     *
     * @param id 工件 标识
     * @return 运行时实例
     * @throws IllegalArgumentException 如果工件不存在
     */
    RuntimeInstance start(String id);

    /**
     * 停止指定工件。
     *
     * @param id 工件 标识
     * @return 运行时实例
     * @throws IllegalArgumentException 如果工件不存在
     */
    RuntimeInstance stop(String id);

    /**
     * 重启指定工件。
     *
     * @param id 工件 标识
     * @return 运行时实例
     * @throws IllegalArgumentException 如果工件不存在
     */
    RuntimeInstance restart(String id);

    /**
     * 获取指定工件的运行状态。
     *
     * @param id 工件 标识
     * @return 运行时状态，工件不存在返回 UNKNOWN
     */
    RuntimeStatus status(String id);

    /**
     * 订阅指定工件的实时日志。
     *
     * @param id       工件 标识
     * @param callback 日志行回调
     * @throws IllegalArgumentException 如果工件不存在或未启动
     */
    void tailLog(String id, LineCallback callback);

    /**
     * 获取所有已注册的工件 标识。
     *
     * @return 工件 标识 列表
     */
    List<String> getArtifactIds();

    /**
     * 获取所有正在运行的实例。
     *
     * @return 运行时实例列表
     */
    List<RuntimeInstance> getRunningInstances();

    /**
     * 获取指定工件的运行时实例。
     *
     * @param id 工件 标识
     * @return 运行时实例，未启动返回 空
     */
    RuntimeInstance getInstance(String id);

    // ==================== 服务管理 ====================

    /**
     * 获取当前平台可用的系统服务管理器。
     *
     * <p>自动检测当前操作系统支持的 ServiceManager 实现。
     * 窗口 返回 {@code WindowsServiceManager}，Linux 返回 {@code SystemdServiceManager}。</p>
     *
     * @return 服务管理器，无可用实现返回 空
     */
    ServiceManager getServiceManager();

    /**
     * 将工件安装为系统服务。
     *
     * <p>根据当前平台自动选择服务管理器，将工件注册为系统级服务。
     * 安装后可通过 OS 工具（如 sc.exe、systemctl）管理。</p>
     *
     * @param id      工件 标识
     * @param service 服务配置
     * @return 安装结果
     * @throws IllegalArgumentException 如果工件不存在或无可用服务管理器
     */
    CmdResult installAsService(String id, ManagedService service);

    /**
     * 卸载系统服务。
     *
     * @param serviceName 服务名称
     * @return 卸载结果
     */
    CmdResult uninstallService(String serviceName);

    /**
     * 启动系统服务。
     *
     * @param serviceName 服务名称
     * @return 启动结果
     */
    CmdResult startService(String serviceName);

    /**
     * 停止系统服务。
     *
     * @param serviceName 服务名称
     * @return 停止结果
     */
    CmdResult stopService(String serviceName);

    /**
     * 重启系统服务。
     *
     * @param serviceName 服务名称
     * @return 重启结果
     */
    CmdResult restartService(String serviceName);

    /**
     * 查询系统服务状态。
     *
     * @param serviceName 服务名称
     * @return 状态查询结果
     */
    CmdResult serviceStatus(String serviceName);

    /**
     * 设置系统服务开机自启。
     *
     * @param serviceName 服务名称
     * @return 操作结果
     */
    CmdResult enableService(String serviceName);

    /**
     * 禁用系统服务开机自启。
     *
     * @param serviceName 服务名称
     * @return 操作结果
     */
    CmdResult disableService(String serviceName);

    // ==================== Java Agent ====================

    /**
     * 获取 Java 智能体 管理器。
     *
     * <p>用于将运行时管理器作为 Java Agent 注入到目标 JVM，
     * 或在运行时管理 智能体 的附加和卸载。</p>
     *
     * @return Java 智能体 管理器
     */
    JavaAgentManager getJavaAgentManager();

    /**
     * 将运行时管理器作为 智能体 注入到目标 JVM。
     *
     * <p>这是 {@link #getJavaAgentManager()} 的快捷方法。</p>
     *
     * @param pid       目标进程 标识
     * @param options   智能体 参数
     * @return 注入结果
     */
    CmdResult attachToJvm(int pid, String options);

    /**
     * 列出本机所有 Java 进程。
     *
     * @return PID 到进程描述的映射
     */
    java.util.Map<Integer, String> listJavaProcesses();

    /**
     * 释放所有资源，停止所有运行中的实例。
     */
    @Override
    void close() throws Exception;
}