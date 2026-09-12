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

    RuntimeArtifact register(RuntimeArtifact artifact);

    RuntimeArtifact registerOrReplace(RuntimeArtifact artifact);

    boolean unregister(String id);

    RuntimeArtifact getArtifact(String id);

    CompletableFuture<Boolean> download(String id, LineCallback callback);

    RuntimeInstance start(String id);

    RuntimeInstance stop(String id);

    RuntimeInstance restart(String id);

    RuntimeStatus status(String id);

    void tailLog(String id, LineCallback callback);

    List<String> getArtifactIds();

    List<RuntimeInstance> getRunningInstances();

    RuntimeInstance getInstance(String id);

    ServiceManager getServiceManager();

    CmdResult installAsService(String id, ManagedService service);

    CmdResult uninstallService(String serviceName);

    CmdResult startService(String serviceName);

    CmdResult stopService(String serviceName);

    CmdResult restartService(String serviceName);

    CmdResult serviceStatus(String serviceName);

    CmdResult enableService(String serviceName);

    CmdResult disableService(String serviceName);

    JavaAgentManager getJavaAgentManager();

    CmdResult attachToJvm(int pid, Path agentPath, String options);

    Map<Integer, String> listJavaProcesses();

    @Override
    void close() throws Exception;
}