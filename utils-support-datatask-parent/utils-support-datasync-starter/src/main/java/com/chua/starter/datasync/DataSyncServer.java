package com.chua.starter.datasync;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.starter.datasync.agent.AgentServerManager;
import com.chua.starter.datasync.mapping.DataSyncMappingManager;
import com.chua.starter.datasync.model.DataSyncMapping;
import com.chua.starter.datasync.scheduler.SyncDataSchedulerManager;

import java.util.List;

/**
* 数据同步服务器总接口，聚合映射管理、调度管理、Agent 管理与执行器管理。
*
* @author CH
* @since 4.0.0.42
 */
public interface DataSyncServer {

    /**
    * 获取映射管理器。
    *
    * @return 映射管理器
    */
    DataSyncMappingManager mappingManager();

    /**
    * 获取调度器管理器。
    *
    * @return 调度器管理器
    */
    SyncDataSchedulerManager schedulerManager();

    /**
    * 获取 Agent 服务器管理器。
    *
    * @return Agent 管理器
    */
    AgentServerManager agentServerManager();

    /**
    * 获取执行器管理器。
    *
    * @return 执行器管理器
    */
    ExecutorManager executorManager();

    /**
    * 添加数据同步映射。
    *
    * @param mapping 映射配置
    */
    void addMapping(DataSyncMapping mapping);

    /**
    * 注册数据源。
    *
    * @param source 数据源实例
    */
    default void registerSource(DataSyncAgentSource source) {}

    /**
    * 注销数据源。
    *
    * @param sourceId 数据源实例 标识
    */
    default void unregisterSource(String sourceId) {}

    /**
    * 注册数据写入目标。
    *
    * @param sink 数据写入目标实例
    */
    default void registerSink(DataSyncAgentSink sink) {}

    /**
    * 注销数据写入目标。
    *
    * @param sinkId 数据写入目标实例 标识
    */
    default void unregisterSink(String sinkId) {}

    /**
    * 根据 源id 获取数据源。
    *
    * @param sourceId 源 实例 标识
    * @return 数据源实例，不存在返回 空
    */
    default DataSyncAgentSource getSource(String sourceId) {
        return null;
    }

    /**
    * 根据 sinkid 获取数据写入目标。
    *
    * @param sinkId Sink 实例 标识
    * @return Sink 实例，不存在返回 空
    */
    default DataSyncAgentSink getSink(String sinkId) {
        return null;
    }

    /**
    * 获取所有已注册数据源。
    *
    * @return Source 列表
    */
    default List<DataSyncAgentSource> getSources() {
        return List.of();
    }

    /**
    * 获取所有已注册数据写入目标。
    *
    * @return Sink 列表
    */
    default List<DataSyncAgentSink> getSinks() {
        return List.of();
    }

    /**
    * 注册 Agent。
    *
    * @param agent Agent 实例
    */
    default void registerAgent(DataSyncAgent agent) {}

    /**
    * 注销 Agent。
    *
    * @param agentId Agent 标识
    */
    default void unregisterAgent(String agentId) {}

    /**
    * 获取 Agent。
    *
    * @param agentId Agent 标识
    * @return Agent 实例，不存在返回 空
    */
    default DataSyncAgent getAgent(String agentId) {
        return null;
    }

    /**
    * 获取所有已注册 Agent。
    *
    * @return Agent 列表
    */
    default List<DataSyncAgent> getAgents() {
        return List.of();
    }

    /**
    * 启动数据同步服务器。
    */
    void start();

    /**
    * 停止数据同步服务器。
    */
    void stop();
}
