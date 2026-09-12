package com.chua.socketio.support.server;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.common.support.lang.json.Json;
import com.chua.socketio.support.source.SocketIOAgentDataSyncSource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
* Socket.IO 数据同步 智能体 服务端
* <p>运行在 DataSyncServer 侧，通过 SocketIO 管理 Agent 连接，支持事件拉取和推送。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class SocketIODataSyncAgentServer extends com.chua.starter.datasync.agent.DefaultDataSyncAgentServer {

    /**
    * 端口号
     */
    private final int port;
    /**
    * 服务器实例
     */
    private com.corundumstudio.socketio.SocketIOServer server;

    /**
    * 创建 套接字io数据同步智能体服务端 实例
    * @param port 端口
     */
    public SocketIODataSyncAgentServer(int port) {
        super("socketio");
        this.port = port;
    }

    @Override
    /** 开始 */
    public void start() {
        if (running) {
            return;
        }
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname("0.0.0.0");
        config.setPort(port);
        server = new com.corundumstudio.socketio.SocketIOServer(config);

        server.addEventListener("register", Map.class, (client, data, ackSender) -> {
            String agentId = (String) data.get("agentId");
            String sourceId = (String) data.get("sourceId");
            if (agentId != null) {
                onAgentConnected(new SimpleDataSyncAgent(agentId, sourceId, client));
                ackSender.sendAckData(Map.of("status", "ok"));
            }
        });

        server.addEventListener("pull_result", Map.class, (client, data, ackSender) -> {
 // 处理 by 源 请求 correlation
        });

        server.addEventListener("push_result", Map.class, (client, data, ackSender) -> {
 // 处理 by 源 请求 correlation
        });

        server.start();
        running = true;
        log.info("[SocketIODataSyncAgentServer] 已启动，监听端口: {}", port);
    }

    @Override
    /** 停止 */
    public void stop() {
        if (server != null) {
            server.stop();
        }
        running = false;
        log.info("[SocketIODataSyncAgentServer] 已停止");
    }

    @Override
    /**
    * 转换转为源
    *
    * @param agent 智能体
    * @param data 数据
    * @return 转换转为源的结果
    * @author CH
    * @since 4.0.0
     */
    public DataSyncSource convertToSource(DataSyncAgent agent, Object data) {
        if (agent == null) {
            return null;
        }
        DataSyncSource source = agent.toSource();
        if (source != null) {
            return source;
        }
        if (agent instanceof SimpleDataSyncAgent simple && simple.getClient() != null) {
            return new SocketIOAgentDataSyncSource(simple.getClient(), simple.agentId(), simple.getSourceId());
        }
        return null;
    }

    private static class SimpleDataSyncAgent implements DataSyncAgent {
        /**
        * 智能体 标识
         */
        private final String agentId;
        /**
        * 源 标识
         */
        private final String sourceId;
        /**
        * 客户端实例
         */
        private final com.corundumstudio.socketio.SocketIOClient client;

        SimpleDataSyncAgent(String agentId, String sourceId, com.corundumstudio.socketio.SocketIOClient client) {
            this.agentId = agentId;
            this.sourceId = sourceId;
            this.client = client;
        }

        @Override public String agentId() { return agentId; }
        @Override public DataSyncSource toSource() { return null; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return false; }
        @Override public String dataUrl() { return ""; }
        com.corundumstudio.socketio.SocketIOClient getClient() { return client; }
        String getSourceId() { return sourceId; }
    }
}
