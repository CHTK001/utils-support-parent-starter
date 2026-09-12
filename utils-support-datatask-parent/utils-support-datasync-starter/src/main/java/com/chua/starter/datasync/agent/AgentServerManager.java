package com.chua.starter.datasync.agent;

import com.chua.datasync.agent.support.DataSyncAgent;

/**
   * 智能体 服务器管理器，统一管理本地与远程 智能体。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface AgentServerManager {

    /**
      * 注册 智能体。
     *
     * @param agent 智能体 实例
     */
    void register(DataSyncAgent agent);

    /**
      * 注销 智能体。
     *
     * @param agentId 智能体 标识
     */
    void unregister(String agentId);

    /**
      * 获取 智能体。
     *
     * @param agentId 智能体 标识
     * @return Agent 实例，不存在返回 空
     */
    DataSyncAgent getAgent(String agentId);

    /**
      * 获取所有已注册 智能体。
     *
     * @return Agent 列表
     */
    java.util.List<DataSyncAgent> getAgents();

    /**
      * 推送数据到 智能体 的 Sink。
     *
     * @param agentId 智能体 标识
     * @param sinkId Sink 标识
     * @param data 数据
     */
    void push(String agentId, String sinkId, java.util.List<java.util.Map<String, Object>> data);

    /** 开始 */
    default void start() {}

    /** 停止 */
    default void stop() {}
}
