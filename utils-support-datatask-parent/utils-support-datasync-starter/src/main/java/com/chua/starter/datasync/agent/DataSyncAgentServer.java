package com.chua.starter.datasync.agent;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.task.scheduler.Trigger;
import com.chua.starter.datasync.DataSyncServer;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据同步 Agent 服务器，统一管理本地与远程 Agent 的注册、数据推送。
 * <p>
 * 本地 Agent 通过 {@link #register(DataSyncAgent)} 直接注册；
 * 远程 Agent 通过 同步服务端 的注册消息自动注册。
 * 数据推送时优先走本地调用，远程 Agent 通过 同步服务端 的 {@code sink:{agentId}} 主题下发。
 * </p>
 * <p>
 * 每个 Agent 注册后，其持有的 源 和 Sink 会一并注册到 {@link DataSyncServer}，
 * 供调度器按 Mapping 配置查找使用。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DataSyncAgentServer implements AgentServerManager {

    /**
     * 主题前缀：Sink 注册/推送
     */
    private static final String TOPIC_SINK_PREFIX = "sink:";

    /**
     * 主题前缀：源 注册/拉取
     */
    private static final String TOPIC_SOURCE_PREFIX = "source:";

    /**
     * 主题前缀：Agent 注册
     */
    private static final String TOPIC_REGISTER_PREFIX = "register:";

    /**
     * 关联的 数据同步服务端 实例
     */
    private final DataSyncServer dataSyncServer;

    /**
     * 底层 同步服务端 网络服务
     */
    private final SyncServer syncServer;

    /**
     * 本地数据分发执行器
     */
    private final ReactorDataSyncExecutor executor;

    /**
     * Agent 注册表
     */
    private final Map<String, DataSyncAgent> agents = new ConcurrentHashMap<>();

    /**
     * 网络消息监听器
     */
    private final SyncServerListener listener = new AgentSyncListener();

    /**
     * 创建 数据同步Agent服务端 实例
     * @param dataSyncServer 数据同步服务端
     * @param syncServer 同步服务端
     * @param serverId 字符串
     * @param syncServer 同步服务端
     * @param serverId 服务端标识
     */
    public DataSyncAgentServer(DataSyncServer dataSyncServer, SyncServer syncServer, String serverId) {
        this.dataSyncServer = dataSyncServer;
        this.syncServer = syncServer;
        this.executor = new ReactorDataSyncExecutor(serverId, true);
    }

    @Override
    /**
     * 开始
    */
    public void start() {
        executor.start();
        if (syncServer != null) {
            syncServer.addListener(listener);
        } else {
            log.info("DataSync Agent 服务器启动（仅本地模式，未配置 SyncServer）");
            return;
        }
        log.info("DataSync Agent 服务器已启动");
    }

    @Override
    /**
     * 停止
    */
    public void stop() {
        if (syncServer != null) {
            syncServer.removeListener(listener);
        }
        executor.stop();
        agents.clear();
        log.info("DataSync Agent 服务器已停止");
    }

    @Override
    /**
     * 注册
    */
    public void register(DataSyncAgent agent) {
        agents.put(agent.agentId(), agent);
        registerAgentResources(agent);
        log.info("Agent 注册成功: agentId={}", agent.agentId());
    }

    @Override
    /**
     * 注销
    */
    public void unregister(String agentId) {
        DataSyncAgent removed = agents.remove(agentId);
        if (removed != null) {
            unregisterAgentResources(removed);
            log.info("Agent 注销成功: agentId={}", agentId);
        }
    }

    @Override
    /**
     * 获取Agent
    */
    public DataSyncAgent getAgent(String agentId) {
        return agents.get(agentId);
    }

    @Override
    /**
     * 获取Agent
    */
    public List<DataSyncAgent> getAgents() {
        return new ArrayList<>(agents.values());
    }

    @Override
    /**
     * 推送
    */
    public void push(String agentId, String sinkId, List<Map<String, Object>> data) {
        DataSyncAgent agent = agents.get(agentId);
        if (agent != null) {
            agent.onDataReceived(data);
            log.debug("推送数据到本地 Agent: agentId={}, sinkId={}, size={}", agentId, sinkId, data.size());
        } else {
            syncServer.publish(TOPIC_SINK_PREFIX + agentId, Map.of(TOPIC_SINK_PREFIX.substring(0, TOPIC_SINK_PREFIX.length()-1), sinkId, "data", data));
            log.debug("推送数据到远程 Agent: agentId={}, sinkId={}, size={}", agentId, sinkId, data.size());
        }
    }

    /**
     * 远程 源 读取超时时间（秒）。
     */
    private static final int REMOTE_SOURCE_READ_TIMEOUT_SECONDS = 30;

    /**
     * 注册Agentresources
     *
     * @param agent Agent
     */
    private void registerAgentResources(DataSyncAgent agent) {
        List<DataSyncAgentSource> sources = new ArrayList<>();
        sources.addAll(agent.sources());

        List<DataSyncAgentSink> sinks = new ArrayList<>();
        sinks.addAll(agent.sinks());

        for (DataSyncAgentSource source : sources) {
            dataSyncServer.registerSource(source);
            log.debug("注册Source: agentId={}, sourceId={}", agent.agentId(), source.sourceId());
        }

        for (DataSyncAgentSink sink : sinks) {
            dataSyncServer.registerSink(sink);
            log.debug("注册Sink: agentId={}, sinkId={}", agent.agentId(), sink.sinkId());
        }
    }

    /**
     * 注销Agentresources
     *
     * @param agent Agent
     */
    private void unregisterAgentResources(DataSyncAgent agent) {
 // 从 数据同步服务端 注销 Agent 持有的 源 和 Sink
        for (DataSyncAgentSource source : agent.sources()) {
            dataSyncServer.unregisterSource(source.sourceId());
        }
        for (DataSyncAgentSink sink : agent.sinks()) {
            dataSyncServer.unregisterSink(sink.sinkId());
        }
        log.info("Agent资源已注销: agentId={}", agent.agentId());
    }

    /**
     * 主题前缀：源 数据响应
     */
    private static final String TOPIC_SOURCE_DATA_PREFIX = "source-data:";
    /**
     * Agent同步监听器类。
     *
     * @author CH
     * @since 4.0.0
     */

    private class AgentSyncListener implements SyncServerListener {
        @Override
        /**
         * on消息
        */
        public void onMessage(String clientId, String topic, Object message) {
            if (topic != null && topic.startsWith(TOPIC_REGISTER_PREFIX)) {
                String agentId = topic.substring(TOPIC_REGISTER_PREFIX.length());
                if (!agents.containsKey(agentId)) {
                    List<String> sourceIds = new ArrayList<>();
                    List<String> sinkIds = new ArrayList<>();
                    if (message instanceof String body) {
                        sourceIds = parseStringArray(body, "sources");
                        sinkIds = parseStringArray(body, "sinks");
                    }
                    register(new RemoteAgentProxy(agentId, sourceIds, sinkIds, syncServer));
                }
            }
        }

        /**
         * 从 JSON 中解析字符串数组。
         *
         * @param json JSON 字符串
         * @param field 字段名
         * @return 字符串列表
         */
        private List<String> parseStringArray(String json, String field) {
            List<String> result = new ArrayList<>();
            String marker = "\"" + field + "\":";
            int start = json.indexOf(marker);
            if (start < 0) {
                return result;
            }
            start += marker.length();
            while (start < json.length() && json.charAt(start) == ' ') {
                start++;
            }
            if (start >= json.length() || json.charAt(start) != '[') {
                return result;
            }
            start++;
            int end = json.indexOf(']', start);
            if (end < 0) {
                return result;
            }
            String content = json.substring(start, end);
            String[] parts = content.split(",");
            for (String part : parts) {
                String trimmed = part.trim().replace("\"", "");
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        }
    }

    /**
     * 远程 Agent 代理，代表尚未在本地注册的远程 Agent。
     * <p>
     * 代理对象将远程 源/Sink 的 标识 映射为本地代理对象，
     * 通过 同步服务端 与远程 Agent 进行网络通信。
     * </p>
     * @param agentId Agent标识
     * @param sourceIds 源标识
     * @param sinkIds sink标识
     * @param syncServer 同步服务端
     * @return 远程Agent代理的结果
     */
    private record RemoteAgentProxy(String agentId, List<String> sourceIds, List<String> sinkIds, SyncServer syncServer) implements DataSyncAgent {

        @Override
        /**
         * Agentid
        */
        public String agentId() {
            return agentId;
        }

        @Override
        /**
         * 开始
        */
        public void start() {
        }

        @Override
        /**
         * 停止
        */
        public void stop() {
        }

        @Override
        /**
         * 源
        */
        public List<DataSyncAgentSource> sources() {
            return sourceIds.stream()
                    .<DataSyncAgentSource>map(id -> new RemoteAgentSource(agentId, id, syncServer))
                    .toList();
        }

        @Override
        /**
         * 获取源
        */
        public DataSyncAgentSource getSource(String sourceId) {
            return sourceIds.contains(sourceId) ? new RemoteAgentSource(agentId, sourceId, syncServer) : null;
        }

        @Override
        /**
         * Sinks
        */
        public List<DataSyncAgentSink> sinks() {
            return sinkIds.stream()
                    .<DataSyncAgentSink>map(id -> new RemoteAgentSink(agentId, id, syncServer))
                    .toList();
        }

        @Override
        /**
         * 获取Sink
        */
        public DataSyncAgentSink getSink(String sinkId) {
            return sinkIds.contains(sinkId) ? new RemoteAgentSink(agentId, sinkId, syncServer) : null;
        }

        @Override
        /**
         * on数据接收
        */
        public void onDataReceived(List<Map<String, Object>> data) {
        }
    }

    /**
     * 远程 源 代理，通过网络请求远程 Agent 读取数据。
     * @author CH
     * @since 4.0.0
     */
    private static class RemoteAgentSource implements DataSyncAgentSource {

        /**
         * Agent 标识
         */
        private final String agentId;

        /**
         * 源 标识
         */
        private final String sourceId;

        /**
         * 网络服务
         */
        private final SyncServer syncServer;

        /**
         * 构造远程 源 代理。
         *
         * @param agentId Agent 标识
         * @param sourceId 源 标识
         * @param syncServer 同步服务端 实例
         */
        RemoteAgentSource(String agentId, String sourceId, SyncServer syncServer) {
            this.agentId = agentId;
            this.sourceId = sourceId;
            this.syncServer = syncServer;
        }

        @Override
        /**
         * 源id
        */
        public String sourceId() {
            return sourceId;
        }

        @Override
        /**
         * 输入id
        */
        public String inputId() {
            return sourceId;
        }

        @Override
        /**
         * 读取
        */
        public Flux<Map<String, Object>> read(Map<String, Object> params) {
            String requestTopic = "source:" + agentId;
            String responseTopic = TOPIC_SOURCE_DATA_PREFIX + agentId + ":" + sourceId;

            @SuppressWarnings("rawtypes")
            SyncServerListener[] listenerHolder = new SyncServerListener[1];

            return Flux.<Map<String, Object>>create(sink -> {
                SyncServerListener listener = new SyncServerListener() {
                    @Override
                    /**
                     * on消息
                    */
                    public void onMessage(String clientId, String topic, Object message) {
                        if (topic.equals(responseTopic)) {
                            if (message instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> data = (List<Map<String, Object>>) (List<?>) message;
                                for (Map<String, Object> row : data) {
                                    sink.next(row);
                                }
                                sink.complete();
                            } else if (message instanceof String) {
                                try {
                                    List<Map<String, Object>> data = com.chua.common.support.lang.json.Json.fromJson((String) message, List.class);
                                    if (data != null) {
                                        for (Map<String, Object> row : data) {
                                            sink.next(row);
                                        }
                                    }
                                    sink.complete();
                                } catch (Exception e) {
                                    sink.error(e);
                                }
                            }
                        }
                    }
                };

                listenerHolder[0] = listener;
                syncServer.addListener(listener);

                // 发送读取请求
                syncServer.publish(requestTopic, sourceId);

                // 取消订阅时移除监听器
                sink.onCancel(() -> {
                    SyncServerListener l = listenerHolder[0];
                    if (l != null) {
                        syncServer.removeListener(l);
                    }
                });
            })
            .timeout(java.time.Duration.ofSeconds(REMOTE_SOURCE_READ_TIMEOUT_SECONDS))
            .doOnError(e -> log.warn("远程 Source 读取超时: agentId={}, sourceId={}", agentId, sourceId))
            .doFinally(signal -> {
                SyncServerListener l = listenerHolder[0];
                if (l != null) {
                    syncServer.removeListener(l);
                }
            })
            .onErrorResume(e -> Flux.fromIterable(java.util.Collections.<Map<String, Object>>emptyList()));
        }

        @Override
        /**
         * 关闭
        */
        public void close() {
        }
    }

    /**
     * 远程 Sink 代理，通过网络推送数据到远程 Agent。
     * @author CH
     * @since 4.0.0
     */
    private static class RemoteAgentSink implements DataSyncAgentSink {

        /**
         * Agent 标识
         */
        private final String agentId;

        /**
         * Sink 标识
         */
        private final String sinkId;

        /**
         * 网络服务
         */
        private final SyncServer syncServer;

        /**
         * 构造远程 Sink 代理。
         *
         * @param agentId Agent 标识
         * @param sinkId Sink 标识
         * @param syncServer 同步服务端 实例
         */
        RemoteAgentSink(String agentId, String sinkId, SyncServer syncServer) {
            this.agentId = agentId;
            this.sinkId = sinkId;
            this.syncServer = syncServer;
        }

        @Override
        /**
         * sinkid
        */
        public String sinkId() {
            return sinkId;
        }

        @Override
        /**
         * 写入
        */
        public void write(Flux<Map<String, Object>> data) {
            data.collectList().subscribe(list -> {
                Map<String, Object> message = Map.of(
                        "sinkId", sinkId,
                        "data", list
                );
                syncServer.publish("sink:" + agentId, message);
            });
        }

        @Override
        /**
         * 关闭
        */
        public void close() {
        }
    }
}
