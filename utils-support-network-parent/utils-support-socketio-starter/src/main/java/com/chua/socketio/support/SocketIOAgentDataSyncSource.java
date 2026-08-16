package com.chua.socketio.support.source;

import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.common.support.lang.json.Json;
import com.corundumstudio.socketio.SocketIOClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SocketIO Agent 数据源
 * <p>Server 侧通过 SocketIO 事件与 Agent 交互，支持拉取和推送。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SocketIOAgentDataSyncSource implements DataSyncSource {

    private final SocketIOClient client;
    private final String sourceId;
    private final String agentId;
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    public SocketIOAgentDataSyncSource(SocketIOClient client, String agentId, String sourceId) {
        this.client = client;
        this.agentId = agentId;
        this.sourceId = sourceId;
    }

    @Override
    public Direction direction() {
        return Direction.INPUT;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public Flux<Map<String, Object>> read(SyncDataOffset offset, Map<String, Object> params) {
        return Flux.defer(() -> {
            try {
                String requestId = java.util.UUID.randomUUID().toString();
                CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
                pending.put(requestId, future);

                client.sendEvent("pull", Map.of(
                        "requestId", requestId,
                        "agentId", agentId,
                        "sourceId", sourceId,
                        "offset", offset != null ? offset.offsetValue() : null,
                        "params", params != null ? params : Map.of()
                ));

                Map<String, Object> result = future.get(30, TimeUnit.SECONDS);
                Object data = result != null ? result.get("data") : null;
                if (data instanceof List<?> list) {
                    return Flux.fromIterable(list.stream()
                            .filter(e -> e instanceof Map)
                            .map(e -> (Map<String, Object>) e)
                            .toList());
                }
                return Flux.empty();
            } catch (Exception e) {
                return Flux.error(e);
            }
        });
    }

    @Override
    public SyncDataOffset currentOffset() {
        return null;
    }

    @Override
    public void write(Flux<Map<String, Object>> data) {
        try {
            List<Map<String, Object>> rows = data.collectList().block();
            if (rows == null || rows.isEmpty()) {
                return;
            }
            String requestId = java.util.UUID.randomUUID().toString();
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            pending.put(requestId, future);

            client.sendEvent("push", Map.of(
                    "requestId", requestId,
                    "agentId", agentId,
                    "sourceId", sourceId,
                    "data", rows
            ));

            future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("SocketIO push failed", e);
        }
    }

    @Override
    public void close() {
    }
}
