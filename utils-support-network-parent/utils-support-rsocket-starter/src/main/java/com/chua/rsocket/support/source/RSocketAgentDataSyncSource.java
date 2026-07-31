package com.chua.rsocket.support.source;

import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.common.support.lang.json.Json;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketConnector;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * RSocket Agent 数据源
 * <p>Server 侧通过 RSocket request-stream / fire-and-forget 与 Agent 交互。</p>
 *
 * @author CH
 * @since 2026-07-20
 */
public class RSocketAgentDataSyncSource implements DataSyncSource {

    private final String agentId;
    private final String sourceId;
    private final String host;
    private final int port;

    public RSocketAgentDataSyncSource(String agentId, String sourceId, String host, int port) {
        this.agentId = agentId;
        this.sourceId = sourceId;
        this.host = host;
        this.port = port;
    }

    public RSocketAgentDataSyncSource(String agentId, String sourceId) {
        this(agentId, sourceId, "localhost", 8080);
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
                RSocket rSocket = RSocketConnector.create()
                        .keepAlive(Duration.ofSeconds(30), Duration.ofSeconds(10))
                        .connect(TcpClientTransport.create(host, port))
                        .block();

                String request = Json.toJson(Map.of(
                        "type", "pull",
                        "agentId", agentId,
                        "sourceId", sourceId,
                        "offset", offset != null ? offset.offsetValue() : null,
                        "params", params != null ? params : Map.of()
                ));

                return rSocket.requestStream(DefaultPayload.create(request))
                        .map(io.rsocket.Payload::getDataUtf8)
                        .map(json -> Json.fromJson(json, Map.class))
                        .filter(row -> row != null && !row.isEmpty())
                        .map(row -> (Map<String, Object>) row);
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
            RSocket rSocket = RSocketConnector.create()
                    .keepAlive(Duration.ofSeconds(30), Duration.ofSeconds(10))
                    .connect(TcpClientTransport.create(host, port))
                    .block();
            String request = Json.toJson(Map.of(
                    "type", "push",
                    "agentId", agentId,
                    "sourceId", sourceId,
                    "data", rows
            ));
            rSocket.fireAndForget(DefaultPayload.create(request)).block();
            rSocket.dispose();
        } catch (Exception e) {
            throw new RuntimeException("RSocket push failed", e);
        }
    }

    @Override
    public void close() {
    }
}
