package com.chua.common.support.network.protocol.client;

import com.chua.common.support.network.protocol.listener.ConnectionListener;
import com.chua.common.support.network.protocol.listener.DataEvent;
import com.chua.common.support.network.protocol.listener.DataListener;
import com.chua.common.support.network.protocol.listener.DisconnectionListener;
import com.chua.common.support.network.protocol.listener.TopicListener;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认监听器管理器实现
 * <p>
 * 独立的监听器管理器实现，不依赖协议客户端类层次结构。
 * 主要用于 {@link com.chua.common.support.network.protocol.sync.SyncClient} 等轻量级客户端。
 * </p>
 *
 * <h3>功能特性</h3>
 * <ul>
 *     <li>连接监听器管理 - 监听连接建立事件</li>
 *     <li>断开连接监听器管理 - 监听连接断开事件</li>
 *     <li>数据监听器管理 - 监听数据/消息事件</li>
 *     <li>主题监听器管理 - 监听特定主题的消息</li>
 *     <li>事件触发方法 - 触发各类监听器</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * DefaultListenerManager manager = new DefaultListenerManager();
 *
 * // 添加连接监听器
 * manager.addConnectionListener(event -> {
 *     System.out.println("连接成功: " + event.getMessage());
 * });
 *
 * // 添加主题监听器
 * manager.addTopicListener("/my/topic", event -> {
 *     System.out.println("收到消息: " + event.getData());
 * });
 *
 * // 触发事件
 * manager.fireConnected();
 * manager.fireTopicEvent("/my/topic", "Hello World");
 * }</pre>
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/12/19
 * @see ListenerManager
 * @see com.chua.common.support.network.protocol.sync.AbstractSyncClient
 */
@Slf4j
public class DefaultListenerManager implements ListenerManager {

    /**
     * 连接监听器列表
     */
    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    /**
     * 断开连接监听器列表
     */
    private final List<DisconnectionListener> disconnectionListeners = new CopyOnWriteArrayList<>();

    /**
     * 数据监听器列表
     */
    private final List<DataListener> dataListeners = new CopyOnWriteArrayList<>();

    /**
     * 主题监听器列表
     */
    private final List<TopicListener> topicListeners = new CopyOnWriteArrayList<>();

    /**
     * 主题到监听器的映射（快速查找特定主题的监听器）
     */
    private final ConcurrentHashMap<String, List<TopicListener>> topicListenerIndex = new ConcurrentHashMap<>();

    // ========== 连接监听器管理 ==========

    /**
     * {@inheritDoc}
     */
    @Override
    public void addConnectionListener(ConnectionListener listener) {
        if (listener == null) {
            return;
        }
        connectionListeners.add(listener);
        if (log.isDebugEnabled()) {
            log.debug("添加连接监听器: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        if (listener == null) {
            return;
        }
        connectionListeners.remove(listener);
        if (log.isDebugEnabled()) {
            log.debug("移除连接监听器: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConnectionListener> getConnectionListeners() {
        return new ArrayList<>(connectionListeners);
    }

    // ========== 断开连接监听器管理 ==========

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDisconnectionListener(DisconnectionListener listener) {
        if (listener == null) {
            return;
        }
        disconnectionListeners.add(listener);
        if (log.isDebugEnabled()) {
            log.debug("添加断开连接监听器: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeDisconnectionListener(DisconnectionListener listener) {
        if (listener == null) {
            return;
        }
        disconnectionListeners.remove(listener);
        if (log.isDebugEnabled()) {
            log.debug("移除断开连接监听器: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<DisconnectionListener> getDisconnectionListeners() {
        return new ArrayList<>(disconnectionListeners);
    }

    // ========== 数据监听器管理 ==========

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDataListener(DataListener listener) {
        if (listener == null) {
            return;
        }
        dataListeners.add(listener);
        if (log.isDebugEnabled()) {
            log.debug("添加数据监听器: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeDataListener(DataListener listener) {
        if (listener == null) {
            return;
        }
        dataListeners.remove(listener);
        if (log.isDebugEnabled()) {
            log.debug("移除数据监听器: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<DataListener> getDataListeners() {
        return new ArrayList<>(dataListeners);
    }

    // ========== 主题监听器管理 ==========

    /**
     * {@inheritDoc}
     */
    @Override
    public void addTopicListener(TopicListener listener) {
        if (listener == null) {
            return;
        }
        topicListeners.add(listener);
        String topic = listener.getTopic();
        if (topic != null) {
            topicListenerIndex.computeIfAbsent(topic, key -> new CopyOnWriteArrayList<>()).add(listener);
        }
        if (log.isDebugEnabled()) {
            log.debug("添加主题监听器: topic={}, listener={}", topic, listener.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addTopicListener(String topic, DataListener listener) {
        if (topic == null || listener == null) {
            return;
        }
        // 使用匿名内部类包装为 TopicListener
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeTopicListener(TopicListener listener) {
        if (listener == null) {
            return;
        }
        topicListeners.remove(listener);
        String topic = listener.getTopic();
        if (topic != null) {
            List<TopicListener> listeners = topicListenerIndex.get(topic);
            if (listeners != null) {
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    topicListenerIndex.remove(topic);
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("移除主题监听器: topic={}, listener={}", topic, listener.getClass().getSimpleName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeTopicListeners(String topic) {
        if (topic == null) {
            return;
        }
        List<TopicListener> listeners = topicListenerIndex.remove(topic);
        if (listeners != null) {
            topicListeners.removeAll(listeners);
            if (log.isDebugEnabled()) {
                log.debug("移除主题 {} 的所有监听器, 数量: {}", topic, listeners.size());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TopicListener> getTopicListeners() {
        return new ArrayList<>(topicListeners);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TopicListener> getTopicListeners(String topic) {
        if (topic == null) {
            return List.of();
        }
        // 使用 Java 21 Stream toList() 方法
        return topicListeners.stream()
                .filter(listener -> listener.matches(topic))
                .toList();
    }

    // ========== 事件触发方法 ==========

    /**
     * 触发连接成功事件
     * <p>
     * 通知所有已注册的连接监听器，连接已建立成功
     * </p>
     */
    public void fireConnected() {
        connectionListeners.forEach(listener -> {
            try {
                listener.onEvent(null);
            } catch (Exception e) {
                log.error("连接监听器处理事件失败", e);
            }
        });
    }

    /**
     * 触发断开连接事件
     * <p>
     * 通知所有已注册的断开连接监听器，连接已断开
     * </p>
     *
     * @param reason 断开原因，可为null
     */
    public void fireDisconnected(String reason) {
        disconnectionListeners.forEach(listener -> {
            try {
                listener.onEvent(null);
            } catch (Exception e) {
                log.error("断开连接监听器处理事件失败, 原因: {}", reason, e);
            }
        });
    }

    /**
     * 触发主题事件
     * <p>
     * 向所有订阅了指定主题的监听器和所有数据监听器发送数据事件
     * </p>
     *
     * @param topic 主题名称，不可为null
     * @param data  事件数据
     */
    public void fireTopicEvent(String topic, Object data) {
        if (topic == null) {
            return;
        }

        DataEvent event = DataEvent.topicMessage(topic, null, data);
        
        // 1. 通知主题监听器
        List<TopicListener> topicListenerList = getTopicListeners(topic);
        topicListenerList.forEach(listener -> {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("主题监听器处理事件失败: topic={}", topic, e);
            }
        });

        // 2. 通知数据监听器（全局监听器）
        dataListeners.forEach(listener -> {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("数据监听器处理事件失败: topic={}", topic, e);
            }
        });

        if (topicListenerList.isEmpty() && dataListeners.isEmpty()) {
            log.trace("主题 {} 没有监听器", topic);
        }
    }

    /**
     * 触发数据事件
     * <p>
     * 向所有已注册的数据监听器发送数据事件
     * </p>
     *
     * @param data 事件数据
     */
    public void fireDataEvent(Object data) {
        DataEvent event = DataEvent.message(null, data);
        dataListeners.forEach(listener -> {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("数据监听器处理事件失败", e);
            }
        });
    }

    /**
     * 清除所有监听器
     * <p>
     * 移除所有类型的监听器，包括连接、断开、数据和主题监听器
     * </p>
     */
    public void clear() {
        connectionListeners.clear();
        disconnectionListeners.clear();
        dataListeners.clear();
        topicListeners.clear();
        topicListenerIndex.clear();
        if (log.isDebugEnabled()) {
            log.debug("已清除所有监听器");
        }
    }
}
