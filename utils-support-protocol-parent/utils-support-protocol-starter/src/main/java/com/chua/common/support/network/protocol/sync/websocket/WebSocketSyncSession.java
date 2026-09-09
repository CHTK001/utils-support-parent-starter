package com.chua.common.support.network.protocol.sync.websocket;

import com.chua.common.support.text.json.Json;
import com.chua.common.support.network.protocol.sync.SyncMessage;
import com.chua.common.support.network.protocol.sync.SyncSession;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * WebSocket同步会话实现
 * <p>
 * 基于通用接口实现的WebSocket会话，不依赖特定WebSocket库。
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/12/01
 */
@Slf4j
public class WebSocketSyncSession implements SyncSession {

    /**
     * 会话ID
     */
    private final String sessionId;

    /**
     * 会话属性
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 连接状态检查器
     */
    private final Supplier<Boolean> connectedChecker;

    /**
     * 消息发送器
     */
    private final Consumer<String> messageSender;

    /**
     * 关闭处理器
     */
    private final Runnable closeHandler;

    /**
     * 构造函数
     *
     * @param sessionId        会话ID
     * @param connectedChecker 连接状态检查器
     * @param messageSender    消息发送器
     * @param closeHandler     关闭处理器
     */
    public WebSocketSyncSession(String sessionId,
                                 Supplier<Boolean> connectedChecker,
                                 Consumer<String> messageSender,
                                 Runnable closeHandler) {
        this.sessionId = sessionId;
        this.connectedChecker = connectedChecker;
        this.messageSender = messageSender;
        this.closeHandler = closeHandler;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean isConnected() {
        return connectedChecker != null && connectedChecker.get();
    }

    @Override
    public void send(String topic, Object data) {
        if (!isConnected()) {
            log.warn("会话断开，无法发送消息: sessionId={}", sessionId);
            return;
        }
        try {
            SyncMessage message = SyncMessage.of(topic, data);
            String json = Json.toJson(message);
            if (messageSender != null) {
                messageSender.accept(json);
            }
        } catch (Exception e) {
            log.error("发送消息失败: sessionId={}, topic={}", sessionId, topic, e);
        }
    }

    @Override
    public void close() {
        try {
            if (closeHandler != null) {
                closeHandler.run();
            }
        } catch (Exception e) {
            log.error("关闭会话失败: sessionId={}", sessionId, e);
        }
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
}
