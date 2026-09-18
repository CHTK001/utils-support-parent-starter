package com.chua.starter.datasync.agent;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
* 数据同步 Agent 服务器默认实现，统一管理本地与远程 Agent 的注册、数据推送。
*
* @author CH
* @since 4.0.0.42
 */
public abstract class DefaultDataSyncAgentServer extends DataSyncAgentServer {

    /**
    * 已连接 Agent 列表。
    */
    protected final List<DataSyncAgent> connectedAgents = new CopyOnWriteArrayList<>();

    /**
    * 是否运行中。
    */
    protected volatile boolean running;

    /**
    * 构造默认 Agent 服务器。
    *
    * @param serverId 服务器标识
    */
    public DefaultDataSyncAgentServer(String serverId) {
        super(null, null, serverId);
    }

    /**
    * 获取已连接的 Agent 列表。
    *
    * @return Agent 列表
    */
    public List<DataSyncAgent> getConnectedAgents() {
        return connectedAgents;
    }

    /**
    * 当 Agent 连接时调用，子类可覆盖以注册资源。
    *
    * @param agent 新连接的 Agent
    */
    protected void onAgentConnected(DataSyncAgent agent) {
    }

    /**
    * 当 Agent 断开时调用，子类可覆盖以注销资源。
    *
    * @param agent 断开的 Agent
    */
    protected void onAgentDisconnected(DataSyncAgent agent) {
    }

    /**
    * 转换数据源，子类根据协议实现。
    *
    * @param agent Agent 实例
    * @param data  消息数据
    * @return DataSyncSource 实例
    */
    public DataSyncSource convertToSource(DataSyncAgent agent, Object data) {
        return null;
    }
}
