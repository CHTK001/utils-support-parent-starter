package com.chua.starter.datasync;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.starter.datasync.agent.AgentServerManager;
import com.chua.starter.datasync.mapping.DataSyncMappingManager;
import com.chua.starter.datasync.mapping.DefaultDataSyncMappingManager;
import com.chua.starter.datasync.model.DataSyncMapping;
import com.chua.starter.datasync.scheduler.DefaultSyncDataSchedulerManager;
import com.chua.starter.datasync.scheduler.SyncDataSchedulerManager;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DataSyncServer 默认实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultDataSyncServer implements DataSyncServer {

    /** 默认服务标识 */
    private static final String DEFAULT_SERVER_ID = "default-server";

    /** 代理服务管理器 */
    private final AgentServerManager agentServerManager;
    /** 映射管理器 */
    private DataSyncMappingManager mappingManager;
    /** 调度管理器 */
    private final SyncDataSchedulerManager schedulerManager;
    /** 执行器管理器 */
    private volatile ExecutorManager executorManager;

    /** 数据源注册表 */
    private final Map<String, DataSyncAgentSource> sourceRegistry = new ConcurrentHashMap<>();
    /** 写入端注册表 */
    private final Map<String, DataSyncAgentSink> sinkRegistry = new ConcurrentHashMap<>();
    /** 代理注册表 */
    private final Map<String, DataSyncAgent> agentRegistry = new ConcurrentHashMap<>();

    public DefaultDataSyncServer(AgentServerManager agentServerManager) {
        this(agentServerManager, DefaultSyncDataSchedulerManager.SchedulerConfig.builder().build());
    }

    public DefaultDataSyncServer(AgentServerManager agentServerManager,
                                  DefaultSyncDataSchedulerManager.SchedulerConfig schedulerConfig) {
        this.agentServerManager = agentServerManager;
        this.mappingManager = new DefaultDataSyncMappingManager();
        this.executorManager = new DefaultExecutorManager(DEFAULT_SERVER_ID, schedulerConfig.isDirectDispatch());
        this.schedulerManager = new DefaultSyncDataSchedulerManager(this, schedulerConfig);
    }

    /**
     * 替换执行器管理器（与 Datalake 整合时使用）。
     * 必须在 {@link #start()} 之前调用。
     */
    public void setExecutorManager(ExecutorManager executorManager) {
        this.executorManager = executorManager;
    }

    /**
     * 替换映射管理器（测试或外部共享时使用）。
     * 必须在 {@link #start()} 之前调用。
     *
     * @param mappingManager 映射管理器
     */
    public void setMappingManager(DataSyncMappingManager mappingManager) {
        this.mappingManager = mappingManager;
    }

    @Override
    public DataSyncMappingManager mappingManager() {
        return mappingManager;
    }

    @Override
    public SyncDataSchedulerManager schedulerManager() {
        return schedulerManager;
    }

    @Override
    public AgentServerManager agentServerManager() {
        return agentServerManager;
    }

    @Override
    public ExecutorManager executorManager() {
        return executorManager;
    }

    @Override
    public void addMapping(DataSyncMapping mapping) {
        mappingManager.addMapping(mapping);
    }

    @Override
    public void registerSource(DataSyncAgentSource source) {
        if (source == null || source.sourceId() == null) {
            return;
        }
        sourceRegistry.put(source.sourceId(), source);
        log.debug("Source 已注册: sourceId={}", source.sourceId());
    }

    @Override
    public void unregisterSource(String sourceId) {
        if (sourceId == null) {
            return;
        }
        DataSyncAgentSource removed = sourceRegistry.remove(sourceId);
        if (removed != null) {
            log.info("Source 已注销: sourceId={}", sourceId);
        }
    }

    @Override
    public void registerSink(DataSyncAgentSink sink) {
        if (sink == null || sink.sinkId() == null) {
            return;
        }
        sinkRegistry.put(sink.sinkId(), sink);
        log.debug("Sink 已注册: sinkId={}", sink.sinkId());
    }

    @Override
    public void unregisterSink(String sinkId) {
        if (sinkId == null) {
            return;
        }
        DataSyncAgentSink removed = sinkRegistry.remove(sinkId);
        if (removed != null) {
            log.info("Sink 已注销: sinkId={}", sinkId);
        }
    }

    @Override
    public DataSyncAgentSource getSource(String sourceId) {
        return sourceRegistry.get(sourceId);
    }

    @Override
    public DataSyncAgentSink getSink(String sinkId) {
        return sinkRegistry.get(sinkId);
    }

    @Override
    public List<DataSyncAgentSource> getSources() {
        return List.copyOf(sourceRegistry.values());
    }

    @Override
    public List<DataSyncAgentSink> getSinks() {
        return List.copyOf(sinkRegistry.values());
    }

    @Override
    public void registerAgent(DataSyncAgent agent) {
        if (agent == null || agent.agentId() == null) {
            return;
        }
        agentRegistry.put(agent.agentId(), agent);
        log.info("Agent 已注册: agentId={}", agent.agentId());
    }

    @Override
    public void unregisterAgent(String agentId) {
        if (agentId == null) {
            return;
        }
        DataSyncAgent removed = agentRegistry.remove(agentId);
        if (removed != null) {
            log.info("Agent 已注销: agentId={}", agentId);
        }
    }

    @Override
    public DataSyncAgent getAgent(String agentId) {
        return agentRegistry.get(agentId);
    }

    @Override
    public List<DataSyncAgent> getAgents() {
        return List.copyOf(agentRegistry.values());
    }

    @Override
    public void start() {
        log.info("DataSync Server 启动中...");
        executorManager.start();
        agentServerManager.start();
        schedulerManager.start();
        log.info("DataSync Server 启动成功");
    }

    @Override
    public void stop() {
        log.info("DataSync Server 停止中...");
        schedulerManager.stop();
        agentServerManager.stop();
        executorManager.stop();
        log.info("DataSync Server 已停止");
    }
}