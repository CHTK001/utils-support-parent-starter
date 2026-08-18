package com.chua.socketio.support.server;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.common.support.lang.json.Json;
import com.chua.socketio.support.source.SocketIOAgentDataSyncSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * SocketIO 数据同步 Agent 服务端
 * <p>运行在 DataSyncServer 侧，通过 SocketIO 管理 Agent 连接，支持事件拉取和推送。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SocketIODataSyncAgentServer extends com.chua.starter.datasync.agent.DefaultDataSyncAgentServer {

    /**
     * 日志实例
     */
    private static final Logger log = LoggerFactory.getLogger(SocketIODataSyncAgentServer.class);

    /**
     * 端口号
     */
    private final int port;
    /**
     * 服务器实例
     */
    private com.corundumstudio.socketio.SocketIOServer server;

    public SocketIODataSyncAgentServer(int port) {
        super("socketio");
        this.port = port;
    }

    @Override
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
            // handled by source request correlation
        });

        server.addEventListener("push_result", Map.class, (client, data, ackSender) -> {
            // handled by source request correlation
        });

        server.start();
        running = true;
        log.info("[SocketIODataSyncAgentServer] 已启动，监听端口: {}", port);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
        running = false;
        log.info("[SocketIODataSyncAgentServer] 已停止");
    }

    @Override
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
         * agent Id
         */
        private final String agentId;
        /**
         * source Id
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
