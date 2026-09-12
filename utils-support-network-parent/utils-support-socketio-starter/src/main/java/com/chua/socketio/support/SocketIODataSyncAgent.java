package com.chua.socketio.support.agent;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.common.support.lang.json.Json;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
   * Socket.IO 数据同步 智能体
 * <p>通过 SocketIO 与 DataSyncServer 保持长连接，支持双向事件通信。</p>
 *
 * <pre>{@code
 * SocketIODataSyncAgent agent = new SocketIODataSyncAgent(
 *         "agent-1", "source-1", "http://server:8080");
 * agent.start();
 * }</pre>8080");
 * agent.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SocketIODataSyncAgent implements DataSyncAgent {

    /**
      * 智能体 标识
     */
    private final String agentId;
    /**
      * 源 标识
     */
    private final String sourceId;
    /**
     * 服务器地址
     */
    private final String serverUrl;
    /**
      * 源
     */
    private final DataSyncSource source;

    /**
      * 套接字
     */
    private io.socket.client.Socket socket;
    /**
     * running
     */
    private volatile boolean running = false;
    /**
      * 消息 队列
     */
    private final BlockingQueue<Map<String, Object>> messageQueue = new LinkedBlockingQueue<>();

    /**
      * 创建 套接字io数据同步智能体 实例
     * @param agentId 智能体标识
     * @param agentId 字符串
     * @param agentId 字符串
     * @param source 数据同步源
     * @param sourceId 源标识
     * @param serverUrl 服务端url
     * @param source 源
     */
    public SocketIODataSyncAgent(String agentId, String sourceId, String serverUrl, DataSyncSource source) {
        this.agentId = agentId;
        this.sourceId = sourceId;
        this.serverUrl = serverUrl;
        this.source = source;
    }

    @Override
    /** 开始 */
    public void start() {
        if (running) {
            return;
        }
        try {
            socket = io.socket.client.IO.socket(URI.create(serverUrl));
            socket.on("pull", args -> handlePull(args));
            socket.on("push", args -> handlePush(args));
            socket.connect();
            running = true;
        } catch (Exception e) {
            throw new RuntimeException("SocketIO Agent 启动失败", e);
        }
    }

    @Override
    /** 停止 */
    public void stop() {
        running = false;
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    @Override
    /** 智能体id */
    public String agentId() { return agentId; }

    @Override
    /** 转为源 */
    public DataSyncSource toSource() { return source; }

    @Override
    /** 是否Running */
    public boolean isRunning() { return running; }

    @Override
    /** 数据url */
    public String dataUrl() { return ""; }

    /**
     * 处理拉取
     *
     * @param args 参数
     */
    private void handlePull(Object[] args) {
        if (args.length == 0) {
            return;
        }
        Map<String, Object> request = (Map<String, Object>) args[0];
        String offsetValue = (String) request.get("offset");
        SyncDataOffset offset = offsetValue != null ? new SyncDataOffset(agentId, offsetValue, System.currentTimeMillis(), null) : null;
        List<Map<String, Object>> rows = source.read(offset, Map.of()).collectList().block();
        emit(Map.of(
                "type", "pull_result",
                "agentId", agentId,
                "sourceId", sourceId,
                "data", rows != null ? rows : List.of(),
                "offset", source.currentOffset()
        ));
    }

    /**
     * 处理推送
     *
     * @param args 参数
     */
    private void handlePush(Object[] args) {
        if (args.length == 0) {
            return;
        }
        Map<String, Object> request = (Map<String, Object>) args[0];
        Object data = request.get("data");
        if (data instanceof List<?> list) {
            List<Map<String, Object>> rows = list.stream()
                    .filter(e -> e instanceof Map)
                    .map(e -> (Map<String, Object>) e)
                    .toList();
            source.write(Flux.fromIterable(rows));
        }
        emit(Map.of("type", "push_result", "status", "ok"));
    }

    /**
     * 发送
     *
     * @param data 数据
     */
    private void emit(Map<String, Object> data) {
        if (socket == null || !socket.connected()) {
            return;
        }
        socket.emit("datasync_event", data);
    }
}
