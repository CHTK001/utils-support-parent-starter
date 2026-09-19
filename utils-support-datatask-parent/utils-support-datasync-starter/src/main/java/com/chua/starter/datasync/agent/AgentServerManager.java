package com.chua.starter.datasync.agent;

import com.chua.datasync.agent.support.DataSyncAgent;

/**
 * Agent 服务器管理器，统一管理本地与远程 Agent。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface AgentServerManager {

    /**
     * 注册 Agent。
     *
     * @param agent Agent 实例
     */
    void register(DataSyncAgent agent);

    /**
     * 注销 Agent。
     *
     * @param agentId Agent 标识
     */
    void unregister(String agentId);

    /**
     * 获取 Agent。
     *
     * @param agentId Agent 标识
     * @return Agent 实例，不存在返回 空
     */
    DataSyncAgent getAgent(String agentId);

    /**
     * 获取所有已注册 Agent。
     *
     * @return Agent 列表
     */
    java.util.List<DataSyncAgent> getAgents();

    /**
     * 推送数据到 Agent 的 Sink。
     *
     * @param agentId Agent 标识
     * @param sinkId Sink 标识
     * @param data 数据
     */
    void push(String agentId, String sinkId, java.util.List<java.util.Map<String, Object>> data);

    /** 开始 */
    default void start() {}

    /** 停止 */
    default void stop() {}
}
