package com.chua.vertx.support.source;

import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.common.support.lang.json.Json;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * WebSocket Agent 数据源
 * <p>Server 侧通过 WebSocket 与 Agent 交互，支持拉取和推送。</p>
 *
 * @author CH
 * @since 2026-07-20
 */
public class WebSocketAgentDataSyncSource implements DataSyncSource {

    /** Connection */
    private final com.chua.vertx.support.server.WebSocketDataSyncAgentServer.Connection connection;
    /** 来源标识 */
    private final String sourceId;

    /**
     * 创建 webSocketAgent数据同步源 实例
     * @param connection connection
     * @param sourceId 字符串
     * @param sourceId 源标识
     */
    public WebSocketAgentDataSyncSource(com.chua.vertx.support.server.WebSocketDataSyncAgentServer.Connection connection, String sourceId) {
        this.connection = connection;
        this.sourceId = sourceId;
    }

    @Override
    /** Direction */
    public Direction direction() {
        return Direction.INPUT;
    }

    @Override
    /** 源标识 */
    public String sourceId() {
        return sourceId;
    }

    @Override
    /** Agent标识 */
    public String agentId() {
        return connection.getAgentId();
    }

    @Override
    /** 读取 */
    public Flux<Map<String, Object>> read(SyncDataOffset offset, Map<String, Object> params) {
        return Flux.defer(() -> {
            try {
                String requestId = java.util.UUID.randomUUID().toString();
                String request = Json.toJson(Map.of(
                        "type", "pull",
                        "requestId", requestId,
                        "agentId", connection.getAgentId(),
                        "sourceId", sourceId,
                        "offset", offset != null ? offset.offsetValue() : null,
                        "params", params != null ? params : Map.of()
                ));
                connection.sendTextFrame(request);
                return Flux.empty();
            } catch (Exception e) {
                return Flux.error(e);
            }
        });
    }

    @Override
    /** 当前偏移量 */
    public SyncDataOffset currentOffset() {
        return null;
    }

    @Override
    /** 写入 */
    public void write(Flux<Map<String, Object>> data) {
        try {
            List<Map<String, Object>> rows = data.collectList().block();
            if (rows == null || rows.isEmpty()) {
                return;
            }
            String request = Json.toJson(Map.of(
                    "type", "push",
                    "agentId", connection.getAgentId(),
                    "sourceId", sourceId,
                    "data", rows
            ));
            connection.sendTextFrame(request);
        } catch (Exception e) {
            throw new RuntimeException("WebSocket push failed", e);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
    }
}
