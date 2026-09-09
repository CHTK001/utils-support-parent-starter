package com.chua.common.support.network.protocol.sync;

import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.client.ListenerManager;
import com.chua.common.support.network.protocol.listener.ConnectionEvent;
import com.chua.common.support.network.protocol.listener.ConnectionListener;
import com.chua.common.support.network.protocol.listener.DataEvent;
import com.chua.common.support.network.protocol.listener.DataListener;
import com.chua.common.support.network.protocol.listener.DisconnectionEvent;
import com.chua.common.support.network.protocol.listener.DisconnectionListener;
import com.chua.common.support.network.protocol.listener.TopicListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同步客户端抽象基类
 * <p>
 * 提供 SyncClient 的基础实现：
 * <ul>
 *   <li>客户端ID管理</li>
 *   <li>连接状态管理</li>
 *   <li>监听器管理</li>
 *   <li>主题订阅管理</li>
 * </ul>
 * <p>
 * 子类只需实现协议特定的连接和消息发送逻辑。
 *
 * @author CH
 * @version 2.0.0
 * @since 2024/12/19
 */
@Slf4j
public abstract class AbstractSyncClient implements SyncClient {

    /**
     * 客户端设置
     */
    @Getter
    protected final ClientSetting clientSetting;

    /**
     * 客户端ID
     */
    @Getter
    protected String clientId;

    /**
     * 连接状态
     */
    protected final AtomicBoolean connected = new AtomicBoolean(false);

    /**
     * 监听器管理器
     */
    protected final ListenerManager listenerManager = createListenerManager();

    /**
     * 已订阅的主题
     */
    protected final Set<String> subscribedTopics = new CopyOnWriteArraySet<>();

    /**
     * 构造方法
     *
     * @param clientSetting 客户端设置
     */
    public AbstractSyncClient(ClientSetting clientSetting) {
        this.clientSetting = clientSetting;
        this.clientId = generateClientId();
    }

    /**
     * 生成客户端ID
     *
     * @return 客户端ID
     */
    protected String generateClientId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ==================== SyncClient 接口实现 ====================

    @Override
    public boolean connect() {
        if (connected.get()) {
            if (log.isDebugEnabled()) {
                log.debug("[SyncClient] 已经连接，无需重复连接");
            }
            return true;
        }

        try {
            boolean result = doConnect();
            if (result) {
                connected.set(true);
                log.info("[SyncClient] 连接成功: clientId={}", clientId);
            }
            return result;
        } catch (Exception e) {
            log.error("[SyncClient] 连接失败", e);
            return false;
        }
    }

    @Override
    public void disconnect() {
        if (!connected.get()) {
            return;
        }

        try {
            doDisconnect();
        } catch (Exception e) {
            log.error("[SyncClient] 断开连接异常", e);
        } finally {
            connected.set(false);
            log.info("[SyncClient] 断开连接: clientId={}", clientId);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public boolean reconnect() {
        disconnect();
        return connect();
    }

    @Override
    public SyncClient subscribe(String... topics) {
        if (topics == null || topics.length == 0) {
            return this;
        }

        for (String topic : topics) {
            if (topic != null && !topic.isEmpty()) {
                subscribedTopics.add(topic);
                doSubscribe(topic);
            }
        }
        return this;
    }

    @Override
    public SyncClient unsubscribe(String... topics) {
        if (topics == null || topics.length == 0) {
            return this;
        }

        for (String topic : topics) {
            if (topic != null && !topic.isEmpty()) {
                subscribedTopics.remove(topic);
                doUnsubscribe(topic);
            }
        }
        return this;
    }

    @Override
    public Set<String> getSubscribedTopics() {
        return new CopyOnWriteArraySet<>(subscribedTopics);
    }

    @Override
    public void publish(String topic, Object data) {
        if (!connected.get()) {
            log.warn("[SyncClient] 未连接，无法发布消息: topic={}", topic);
            return;
        }

        try {
            doPublish(topic, data);
        } catch (Exception e) {
            log.error("[SyncClient] 发布消息失败: topic={}", topic, e);
        }
    }

    @Override
    public ListenerManager getListenerManager() {
        return listenerManager;
    }

    @Override
    public void close() {
        disconnect();
    }

    // ==================== 子类需要实现的方法 ====================

    /**
     * 执行连接
     *
     * @return 连接是否成功
     * @throws Exception 连接异常
     */
    protected abstract boolean doConnect() throws Exception;

    /**
     * 执行断开连接
     *
     * @throws Exception 断开异常
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * 执行订阅主题
     *
     * @param topic 主题
     */
    protected abstract void doSubscribe(String topic);

    /**
     * 执行取消订阅
     *
     * @param topic 主题
     */
    protected abstract void doUnsubscribe(String topic);

    /**
     * 执行发布消息
     *
     * @param topic 主题
     * @param data  数据
     * @throws Exception 发布异常
     */
    protected abstract void doPublish(String topic, Object data) throws Exception;

    // ==================== 子类可调用的辅助方法 ====================

    /**
     * 通知收到消息（子类在收到消息时调用）
     *
     * @param topic 主题
     * @param data  数据
     */
    protected void notifyMessage(String topic, Object data) {
        if (listenerManager instanceof EventAwareListenerManager eventAwareListenerManager) {
            eventAwareListenerManager.fireTopicEvent(topic, data);
        }
    }

    /**
     * 通知连接状态变化
     *
     * @param isConnected 是否连接
     */
    protected void notifyConnectionState(boolean isConnected) {
        connected.set(isConnected);
        if (listenerManager instanceof EventAwareListenerManager eventAwareListenerManager) {
            if (isConnected) {
                eventAwareListenerManager.fireConnected();
            } else {
                eventAwareListenerManager.fireDisconnected("Connection lost");
            }
        }
    }

    private ListenerManager createListenerManager() {
        try {
            Class<?> type = Class.forName("com.chua.common.support.network.protocol.client.DefaultListenerManager");
            return (ListenerManager) type.getConstructor().newInstance();
        } catch (ReflectiveOperationException | NoClassDefFoundError ignored) {
            return new EventAwareListenerManager();
        }
    }

    private static class EventAwareListenerManager implements ListenerManager {
        private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
        private final List<DisconnectionListener> disconnectionListeners = new CopyOnWriteArrayList<>();
        private final List<DataListener> dataListeners = new CopyOnWriteArrayList<>();
        private final List<TopicListener> topicListeners = new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, List<TopicListener>> topicIndex = new ConcurrentHashMap<>();

        @Override
        public void addConnectionListener(ConnectionListener listener) {
            if (listener != null) {
                connectionListeners.add(listener);
            }
        }

        @Override
        public void removeConnectionListener(ConnectionListener listener) {
            connectionListeners.remove(listener);
        }

        @Override
        public List<ConnectionListener> getConnectionListeners() {
            return new ArrayList<>(connectionListeners);
        }

        @Override
        public void addDisconnectionListener(DisconnectionListener listener) {
            if (listener != null) {
                disconnectionListeners.add(listener);
            }
        }

        @Override
        public void removeDisconnectionListener(DisconnectionListener listener) {
            disconnectionListeners.remove(listener);
        }

        @Override
        public List<DisconnectionListener> getDisconnectionListeners() {
            return new ArrayList<>(disconnectionListeners);
        }

        @Override
        public void addDataListener(DataListener listener) {
            if (listener != null) {
                dataListeners.add(listener);
            }
        }

        @Override
        public void removeDataListener(DataListener listener) {
            dataListeners.remove(listener);
        }

        @Override
        public List<DataListener> getDataListeners() {
            return new ArrayList<>(dataListeners);
        }

        @Override
        public void addTopicListener(TopicListener listener) {
            if (listener == null) {
                return;
            }
            topicListeners.add(listener);
            if (listener.getTopic() != null) {
                topicIndex.computeIfAbsent(listener.getTopic(), key -> new CopyOnWriteArrayList<>()).add(listener);
            }
        }

        @Override
        public void addTopicListener(String topic, DataListener listener) {
            if (topic == null || listener == null) {
                return;
            }
            addTopicListener(new TopicListener() {
                @Override
                public String getTopic() {
                    return topic;
                }

                @Override
                public void onEvent(DataEvent event) {
                    listener.onEvent(event);
                }
            });
        }

        @Override
        public void removeTopicListener(TopicListener listener) {
            if (listener == null) {
                return;
            }
            topicListeners.remove(listener);
            if (listener.getTopic() != null) {
                List<TopicListener> listeners = topicIndex.get(listener.getTopic());
                if (listeners != null) {
                    listeners.remove(listener);
                    if (listeners.isEmpty()) {
                        topicIndex.remove(listener.getTopic());
                    }
                }
            }
        }

        @Override
        public void removeTopicListeners(String topic) {
            List<TopicListener> listeners = topicIndex.remove(topic);
            if (listeners != null) {
                topicListeners.removeAll(listeners);
            }
        }

        @Override
        public List<TopicListener> getTopicListeners() {
            return new ArrayList<>(topicListeners);
        }

        @Override
        public List<TopicListener> getTopicListeners(String topic) {
            return new ArrayList<>(topicIndex.getOrDefault(topic, List.of()));
        }

        void fireConnected() {
            ConnectionEvent event = ConnectionEvent.connected(clientId(), "sync-client", "Connected");
            for (ConnectionListener listener : connectionListeners) {
                listener.onEvent(event);
            }
        }

        void fireDisconnected(String reason) {
            DisconnectionEvent event = DisconnectionEvent.normal(clientId(), "sync-client", reason);
            for (DisconnectionListener listener : disconnectionListeners) {
                listener.onEvent(event);
            }
        }

        void fireTopicEvent(String topic, Object data) {
            DataEvent event = DataEvent.topicMessage(topic, clientId(), data);
            for (DataListener listener : dataListeners) {
                listener.onEvent(event);
            }
            for (TopicListener listener : topicIndex.getOrDefault(topic, List.of())) {
                listener.onEvent(event);
            }
        }

        private String clientId() {
            return "sync-client";
        }
    }
}
