package com.chua.rsocket.support.server;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.common.support.lang.json.Json;
import com.chua.rsocket.support.source.RSocketAgentDataSyncSource;
import io.rsocket.core.RSocketServer;
import io.rsocket.transport.netty.server.TcpServerTransport;
import io.rsocket.util.DefaultPayload;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
* r套接字 数据同步 智能体 服务端
* <p>运行在 DataSyncServer 侧，通过 RSocket 管理 Agent 连接，支持 request-stream 拉取数据。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class RSocketDataSyncAgentServer extends com.chua.starter.datasync.agent.DefaultDataSyncAgentServer {

    /**
    * 端口号
     */
    private final int port;

    /**
    * 创建 r套接字数据同步智能体服务端 实例
    * @param port 端口
     */
    public RSocketDataSyncAgentServer(int port) {
        super("rsocket");
        this.port = port;
    }

    @Override
    /** 开始 */
    public void start() {
        if (running) {
            return;
        }
        RSocketServer.create((setup, sendingSocket) -> Mono.just(new io.rsocket.RSocket() {
            @Override
            /** 请求响应 */
            public Mono<io.rsocket.Payload> requestResponse(io.rsocket.Payload payload) {
                return handleRequest(payload);
            }

            @Override
            /** 请求流 */
            public Flux<io.rsocket.Payload> requestStream(io.rsocket.Payload payload) {
                return handleRequestStream(payload);
            }

            @Override
            /** fire和forget */
            public Mono<Void> fireAndForget(io.rsocket.Payload payload) {
                handleFireAndForget(payload);
                return Mono.empty();
            }
        }))
        .bind(TcpServerTransport.create("0.0.0.0", port))
        .doOnSuccess(d -> log.info("[RSocketDataSyncAgentServer] 已启动，监听端口: {}", port))
        .doOnError(e -> log.error("[RSocketDataSyncAgentServer] 启动失败", e))
        .block();
        running = true;
    }

    @Override
    /** 停止 */
    public void stop() {
        running = false;
        log.info("[RSocketDataSyncAgentServer] 已停止");
    }

    /**
    * extract智能体id
    *
    * @param payload payload
    * @return extract智能体id的结果
     */
    private String extractAgentId(io.rsocket.Payload payload) {
        try {
            String data = payload.getDataUtf8();
            Map<String, Object> map = Json.fromJson(data, Map.class);
            return (String) map.get("agentId");
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 处理请求
    *
    * @param payload payload
    * @return 处理请求的结果
     */
    private Mono<io.rsocket.Payload> handleRequest(io.rsocket.Payload payload) {
        try {
            String data = payload.getDataUtf8();
            Map<String, Object> map = Json.fromJson(data, Map.class);
            String type = (String) map.get("type");
            if ("register".equals(type)) {
                return Mono.just(DefaultPayload.create(Json.toJson(Map.of("status", "ok"))));
            }
            if ("pull".equals(type)) {
                DataSyncSource source = findSource((String) map.get("agentId"), (String) map.get("sourceId"));
                if (source == null) {
                    return Mono.just(DefaultPayload.create(Json.toJson(Map.of("error", "Source not found"))));
                }
                String offsetValue = (String) map.get("offset");
                SyncDataOffset offset = offsetValue != null ? new SyncDataOffset("", offsetValue, System.currentTimeMillis(), null) : null;
                List<Map<String, Object>> rows = source.read(offset, Map.of()).collectList().block();
                return Mono.just(DefaultPayload.create(Json.toJson(Map.of(
                        "type", "pull_result",
                        "data", rows != null ? rows : List.of(),
                        "offset", source.currentOffset()
                ))));
            }
            return Mono.empty();
        } catch (Exception e) {
            return Mono.just(DefaultPayload.create(Json.toJson(Map.of("error", e.getMessage()))));
        }
    }

    /**
    * 处理请求流
    *
    * @param payload payload
    * @return 处理请求流的结果
     */
    private Flux<io.rsocket.Payload> handleRequestStream(io.rsocket.Payload payload) {
        try {
            String data = payload.getDataUtf8();
            Map<String, Object> map = Json.fromJson(data, Map.class);
            DataSyncSource source = findSource((String) map.get("agentId"), (String) map.get("sourceId"));
            if (source == null) {
                return Flux.empty();
            }
            String offsetValue = (String) map.get("offset");
            SyncDataOffset offset = offsetValue != null ? new SyncDataOffset("", offsetValue, System.currentTimeMillis(), null) : null;
            return source.read(offset, Map.of())
                    .map(row -> DefaultPayload.create(Json.toJson(row)));
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    /**
    * 处理fire和forget
    *
    * @param payload payload
     */
    private void handleFireAndForget(io.rsocket.Payload payload) {
        try {
            String data = payload.getDataUtf8();
            Map<String, Object> map = Json.fromJson(data, Map.class);
            DataSyncSource source = findSource((String) map.get("agentId"), (String) map.get("sourceId"));
            if (source != null && "push".equals(map.get("type"))) {
                Object rowsData = map.get("data");
                if (rowsData instanceof List<?> list) {
                    List<Map<String, Object>> rows = list.stream()
                            .filter(e -> e instanceof Map)
                            .map(e -> (Map<String, Object>) e)
                            .toList();
                    source.write(Flux.fromIterable(rows));
                }
            }
        } catch (Exception e) {
            log.warn("[RSocketDataSyncAgentServer] fireAndForget 异常: {}", e.getMessage());
        }
    }

    /**
    * 查找源
    *
    * @param agentId 智能体标识
    * @param sourceId 源标识
    * @return find源的结果
    * @author CH
    * @since 4.0.0
     */
    private DataSyncSource findSource(String agentId, String sourceId) {
        DataSyncAgent agent = getConnectedAgents().stream()
                .filter(a -> agentId.equals(a.agentId()))
                .findFirst()
                .orElse(null);
        if (agent == null) {
            return null;
        }
        DataSyncSource source = agent.toSource();
        if (source != null && sourceId.equals(source.sourceId())) {
            return source;
        }
        return new RSocketAgentDataSyncSource(agentId, sourceId);
    }

    private static class SimpleDataSyncAgent implements DataSyncAgent {
        /**
        * 智能体 标识
         */
        private final String agentId;
        SimpleDataSyncAgent(String agentId) { this.agentId = agentId; }
        @Override public String agentId() { return agentId; }
        @Override public DataSyncSource toSource() { return null; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return false; }
        @Override public String dataUrl() { return ""; }
    }
}
