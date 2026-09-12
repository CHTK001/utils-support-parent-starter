package com.chua.vertx.support.agent;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.common.support.lang.json.Json;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
* WebSocket 数据同步 智能体
* <p>通过 WebSocket 与 DataSyncServer 保持长连接，支持双向数据拉取和推送。</p>
*
* <pre>{@code
* WebSocketDataSyncAgent agent = new WebSocketDataSyncAgent(
*         "agent-1", "source-1", "ws://server:8080/ws/datasync");
* agent.start();
* }</pre>atasync");
* agent.start();
* }</pre>
*
* @author CH
* @since 2026-07-20
 */
public class WebSocketDataSyncAgent implements DataSyncAgent {

    /**
    * 智能体 标识
     */
    private final String agentId;

    /**
    * 数据源 标识
     */
    private final String sourceId;

    /**
    * 服务端 WebSocket 地址
     */
    private final String serverUri;

    /**
    * 数据源
     */
    private final DataSyncSource source;

    /**
    * WebSocket 连接
     */
    private WebSocket webSocket;

    /**
    * HTTP 客户端
     */
    private HttpClient httpClient;

    /**
    * 运行标志
     */
    private volatile boolean running = false;

    /**
    * 消息监听队列
     */
    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();

    /**
    * 创建 web套接字数据同步智能体 实例
    * @param agentId 智能体标识
    * @param agentId 字符串
    * @param agentId 字符串
    * @param source 数据同步源
    * @param sourceId 源标识
    * @param serverUri 服务端uri
    * @param source 源
     */
    public WebSocketDataSyncAgent(String agentId, String sourceId, String serverUri, DataSyncSource source) {
        this.agentId = agentId;
        this.sourceId = sourceId;
        this.serverUri = serverUri;
        this.source = source;
    }

    @Override
    /** 开始 */
    public void start() {
        if (running) {
            return;
        }
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        CompletableFuture<WebSocket> cf = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(serverUri), new WebSocket.Listener() {
                    @Override
                    /** On打开 */
                    public void onOpen(WebSocket webSocket) {
                        WebSocketDataSyncAgent.this.webSocket = webSocket;
                        sendRegister();
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messageQueue.offer(data.toString());
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    /** On记录错误 */
                    public void onError(WebSocket webSocket, Throwable error) {
                    }
                });

        cf.thenRun(() -> {
            running = true;
            startMessageLoop();
        });
    }

    @Override
    /** 停止 */
    public void stop() {
        running = false;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
            webSocket = null;
        }
    }

    @Override
    /** 智能体标识 */
    public String agentId() {
        return agentId;
    }

    @Override
    /** 转为源 */
    public DataSyncSource toSource() {
        return source;
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return running;
    }

    @Override
    /** 数据url */
    public String dataUrl() {
        return "";
    }

    /** 发送注册 */
    private void sendRegister() {
        sendJson(Map.of(
                "type", "register",
                "agentId", agentId,
                "sourceId", sourceId,
                "direction", source.direction().name()
        ));
    }

    /** 开始消息循环 */
    private void startMessageLoop() {
        Thread t = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    String msg = messageQueue.take();
                    handleServerMessage(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ws-agent-" + agentId);
        t.setDaemon(true);
        t.start();
    }

    /**
    * 处理服务端消息
    *
    * @param msg msg
     */
    private void handleServerMessage(String msg) {
        try {
            Map<String, Object> map = Json.fromJson(msg, Map.class);
            String type = (String) map.get("type");
            if ("pull".equals(type)) {
                String offsetValue = (String) map.get("offset");
                SyncDataOffset offset = offsetValue != null ? new SyncDataOffset(agentId, offsetValue, System.currentTimeMillis(), null) : null;
                List<Map<String, Object>> rows = source.read(offset, Map.of()).collectList().block();
                sendJson(Map.of(
                        "type", "pull_result",
                        "agentId", agentId,
                        "sourceId", sourceId,
                        "data", rows != null ? rows : List.of(),
                        "offset", source.currentOffset()
                ));
            } else if ("push".equals(type)) {
                Object data = map.get("data");
                if (data instanceof List<?> list) {
                    List<Map<String, Object>> rows = list.stream()
                            .filter(e -> e instanceof Map)
                            .map(e -> (Map<String, Object>) e)
                            .toList();
                    source.write(Flux.fromIterable(rows));
                }
            }
        } catch (Exception e) {
            sendJson(Map.of("type", "error", "agentId", agentId, "error", e.getMessage()));
        }
    }

    /**
    * 发送Json
    *
    * @param body 主体
     */
    private void sendJson(Map<String, Object> body) {
        if (webSocket == null) {
            return;
        }
        String json = Json.toJson(body);
        webSocket.sendText(json, true).join();
    }
}
