package com.chua.common.support.network.sync.support;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 同步客户端抽象基类：收编各传输实现共有的「连接状态 + 订阅表 + 监听器管理 + 元数据」。
 *
 * <p>各传输实现（UDP/TCP/HTTP/WebSocket）只需提供：</p>
 * <ul>
 *     <li>{@link #connect()} / {@link #disconnect()} —— 建立与断开底层连接；</li>
 *     <li>{@link #send(String, Object)} —— 把一帧文本发往服务端；</li>
 *     <li>收到服务端帧后调用 {@link #dispatchMessage(String, String)} 完成订阅分发。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public abstract class AbstractSyncClient implements SyncClient {

    /**
     * 客户端标识
     */
    protected final String clientId;

    /**
     * 服务端地址
     */
    protected final String serverUrl;

    /**
     * 是否已连接
     */
    private volatile boolean connected;

    /**
     * 订阅的主题映射（topic -> handler）
     */
    protected final Map<String, SyncMessageHandler> subscriptions = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建同步客户端。
     *
     * @param clientId  客户端标识
     * @param serverUrl 服务端地址
     */
    protected AbstractSyncClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
    }

    /**
     * 是否Connected
     */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 更新连接状态（由传输实现在建连/断连/读异常时调用）。
     *
     * @param connected true 表示已连接
     */
    protected void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * 获取ClientId
     */
    @Override
    public String getClientId() {
        return clientId;
    }

    /**
     * 订阅
     */
    @Override
    public void subscribe(String topic, SyncMessageHandler handler) {
        subscriptions.put(topic, handler);
    }

    /**
     * 取消订阅
     */
    @Override
    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
    }

    /**
     * 添加Listener
     */
    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除Listener
     */
    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取Metadata
     */
    @Override
    public Map<String, Object> getMetadata() {
        return Map.of("clientId", clientId, "serverUrl", serverUrl, "protocol", protocol());
    }

    /**
     * 获取协议标识（用于元数据上报）。
     *
     * @return 协议名，如 udp / tcp
     */
    protected abstract String protocol();

    /**
     * 关闭
     */
    @Override
    public void close() {
        disconnect();
    }

    /**
     * 校验连接状态。
     */
    protected void checkConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("客户端未连接");
        }
    }

    /**
     * 分发一条服务端消息：先查订阅表，再通知流监听器。
     *
     * @param topic   主题
     * @param payload 消息体
     */
    protected void dispatchMessage(String topic, String payload) {
        SyncMessageHandler handler = subscriptions.get(topic);
        if (handler != null) {
            handler.handle(topic, payload);
        }
        notifyListeners(l -> l.onMessage(topic, payload));
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    protected void notifyListeners(Consumer<SyncFlowListener> action) {
        for (SyncFlowListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.debug("[sync] 监听器回调执行失败，已忽略: {}", e.getMessage());
            }
        }
    }
}
