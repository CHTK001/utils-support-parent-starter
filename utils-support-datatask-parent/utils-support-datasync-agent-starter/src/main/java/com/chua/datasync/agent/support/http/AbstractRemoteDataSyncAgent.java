package com.chua.datasync.agent.support.http;

import com.chua.datasync.agent.support.AbstractDataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgentException;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import com.chua.common.support.network.sync.SyncClient;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class AbstractRemoteDataSyncAgent extends AbstractDataSyncAgent {

    protected final SyncClient syncClient;
    protected ReactorDataSyncExecutor executor;

    protected AbstractRemoteDataSyncAgent(String agentId, SyncClient syncClient) {
        super(agentId);
        this.syncClient = syncClient;
    }

    @Override
    public void start() {
        super.start();
        try {
            syncClient.connect();
            sendRegister();

            syncClient.subscribe("source:" + agentId(), this::handleSourceRequest);
            syncClient.subscribe("sink:" + agentId(), this::handleSinkPush);

            executor = new ReactorDataSyncExecutor(agentId(), false);
            executor.start();

            for (DataSyncAgentSink sink : sinks()) {
                executor.subscribe(sink.sinkId(), data -> sink.write(reactor.core.publisher.Flux.fromIterable(data)));
            }

            log.info("Remote Sync Agent 启动成功: agentId={}", agentId());
        } catch (Exception e) {
            throw new DataSyncAgentException("启动 Remote Sync Agent 失败", e);
        }
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.stop();
        }
        try {
            if (syncClient != null) {
                syncClient.disconnect();
            }
        } catch (Exception e) {
            log.warn("停止 Remote Sync Agent 异常", e);
        }
        super.stop();
    }

    private void sendRegister() {
        try {
            String body = "{\"agentId\":\"" + agentId() + "\",\"sources\":" + getSourceIds() + ",\"sinks\":" + getSinkIds() + "}";
            syncClient.send("register:" + agentId(), body);
            log.info("Agent 注册成功: agentId={}", agentId());
        } catch (Exception e) {
            throw new DataSyncAgentException("注册到 DataSync Server 失败", e);
        }
    }

    private void handleSourceRequest(String topic, Object message) {
        String sourceId = message.toString();
        log.info("收到 Source 拉取请求: sourceId={}", sourceId);

        DataSyncAgentSource source = findSource(sourceId);
        if (source == null) {
            log.warn("Source 不存在: sourceId={}", sourceId);
            return;
        }

        try {
            source.read(Map.of())
                    .buffer(100)
                    .subscribe(batch -> {
                        onDataReceived(batch);
                        try {
                            syncClient.send("source:" + agentId() + ":" + sourceId, batch.toString());
                        } catch (Exception e) {
                            log.error("推送 Source 数据失败: sourceId={}", sourceId, e);
                        }
                    });
        } catch (Exception e) {
            log.error("读取 Source 数据失败: sourceId={}", sourceId, e);
        }
    }

    private void handleSinkPush(String topic, Object message) {
        log.info("收到 Sink 推送");

        String sinkId;
        List<Map<String, Object>> data;

        if (message instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<?, ?> msgMap = (Map<?, ?>) message;
            sinkId = String.valueOf(msgMap.get("sinkId"));
            Object dataObj = msgMap.get("data");
            if (dataObj instanceof List) {
                data = (List<Map<String, Object>>) dataObj;
            } else {
                log.warn("Sink 推送消息缺少 data 字段");
                return;
            }
        } else {
            log.warn("不支持的 Sink 推送消息类型: {}", message.getClass().getName());
            return;
        }

        if (sinkId == null) {
            log.warn("无效的 sink 推送消息: {}", message);
            return;
        }

        onDataReceived(data);
        executor.publish(sinkId, data);
    }

    private DataSyncAgentSource findSource(String sourceId) {
        for (DataSyncAgentSource source : sources()) {
            if (source.sourceId().equals(sourceId)) {
                return source;
            }
        }
        return null;
    }

    private DataSyncAgentSink findSink(String sinkId) {
        for (DataSyncAgentSink sink : sinks()) {
            if (sink.sinkId().equals(sinkId)) {
                return sink;
            }
        }
        return null;
    }

    private List<String> getSourceIds() {
        List<String> ids = new ArrayList<>();
        for (DataSyncAgentSource source : sources()) {
            ids.add(source.sourceId());
        }
        return ids;
    }

    private List<String> getSinkIds() {
        List<String> ids = new ArrayList<>();
        for (DataSyncAgentSink sink : sinks()) {
            ids.add(sink.sinkId());
        }
        return ids;
    }
}
