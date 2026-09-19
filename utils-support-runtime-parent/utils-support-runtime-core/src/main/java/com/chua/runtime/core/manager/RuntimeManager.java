package com.chua.runtime.core.manager;

import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.runtime.core.manager.RuntimeInstance;
import com.chua.runtime.core.manager.RuntimeManager;
import com.chua.runtime.core.model.LogStream;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.core.model.RuntimeStatus;
import com.chua.runtime.core.service.JavaAgentManager;
import com.chua.runtime.core.service.ServiceManager;
import com.chua.runtime.core.model.ManagedService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Path;

/**
 * 运行时管理器接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RuntimeManager extends AutoCloseable {

    /**
     * 注册。
     *
     * @param artifact 方法入参 artifact
     * @return RuntimeArtifact 对象
     */
    RuntimeArtifact register(RuntimeArtifact artifact);

    /**
     * 注册OrReplace。
     *
     * @param artifact 方法入参 artifact
     * @return RuntimeArtifact 对象
     */
    RuntimeArtifact registerOrReplace(RuntimeArtifact artifact);

    /**
     * 注销。
     *
     * @param id ID，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    boolean unregister(String id);

    /**
     * 获取Artifact。
     *
     * @param id ID，不允许为 null
     * @return RuntimeArtifact 对象
     */
    RuntimeArtifact getArtifact(String id);

    /**
     * download。
     *
     * @param id ID，不允许为 null
     * @param callback 回调，不允许为 null
     * @return CompletableFuture 对象
     */
    CompletableFuture<Boolean> download(String id, LineCallback callback);

    /**
     * 启动。
     *
     * @param id ID，不允许为 null
     * @return Runtime实例 对象
     */
    RuntimeInstance start(String id);

    /**
     * 停止。
     *
     * @param id ID，不允许为 null
     * @return Runtime实例 对象
     */
    RuntimeInstance stop(String id);

    /**
     * restart。
     *
     * @param id ID，不允许为 null
     * @return Runtime实例 对象
     */
    RuntimeInstance restart(String id);

    /**
     * 状态。
     *
     * @param id ID，不允许为 null
     * @return Runtime状态 对象
     */
    RuntimeStatus status(String id);

    /**
     * 尾部Log。
     *
     * @param id ID，不允许为 null
     * @param callback 回调，不允许为 null
     */
    void tailLog(String id, LineCallback callback);

    /**
     * 获取ArtifactIds。
     *
     * @return 结果列表，无数据时为空列表
     */
    List<String> getArtifactIds();

    /**
     * 获取RunningInstances。
     *
     * @return 结果列表，无数据时为空列表
     */
    List<RuntimeInstance> getRunningInstances();

    /**
     * 获取实例。
     *
     * @param id ID，不允许为 null
     * @return Runtime实例 对象
     */
    RuntimeInstance getInstance(String id);

    /**
     * 获取服务Manager。
     *
     * @return 服务Manager 对象
     */
    ServiceManager getServiceManager();

    /**
     * installAs服务。
     *
     * @param id ID，不允许为 null
     * @param service 服务，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult installAsService(String id, ManagedService service);

    /**
     * uninstall服务。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult uninstallService(String serviceName);

    /**
     * 启动服务。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult startService(String serviceName);

    /**
     * 停止服务。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult stopService(String serviceName);

    /**
     * restart服务。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult restartService(String serviceName);

    /**
     * 服务状态。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult serviceStatus(String serviceName);

    /**
     * enable服务。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult enableService(String serviceName);

    /**
     * disable服务。
     *
     * @param serviceName 服务名称，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult disableService(String serviceName);

    /**
     * 获取JavaAgentManager。
     *
     * @return JavaAgentManager 对象
     */
    JavaAgentManager getJavaAgentManager();

    /**
     * 挂载转为Jvm。
     *
     * @param pid 方法入参 pid
     * @param agentPath agent路径，不允许为 null
     * @param options 选项，不允许为 null
     * @return Cmd结果 对象
     */
    CmdResult attachToJvm(int pid, Path agentPath, String options);

    /**
     * 列出JavaProcesses。
     *
     * @return 结果映射，无数据时为空映射
     */
    Map<Integer, String> listJavaProcesses();

    @Override
    void close() throws Exception;
}