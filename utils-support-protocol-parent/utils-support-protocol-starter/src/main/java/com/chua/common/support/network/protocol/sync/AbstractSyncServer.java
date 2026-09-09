package com.chua.common.support.network.protocol.sync;

import com.chua.common.support.text.json.Json;
import com.chua.common.support.network.protocol.ServerSetting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同步服务端抽象基类
 * <p>
 * 提供 SyncServer 的基础实现：
 * <ul>
 *   <li>会话管理（增删查）</li>
 *   <li>连接监听器管理</li>
 *   <li>消息监听器管理</li>
 *   <li>消息发送和广播</li>
 * </ul>
 * <p>
 * 子类只需实现协议特定的启动、停止和底层发送逻辑。
 *
 * @author CH
 * @version 2.0.0
 * @since 2024/12/19
 */
@Slf4j
public abstract class AbstractSyncServer implements SyncServer {

    /**
     * 服务端设置
     */
    @Getter
    protected final ServerSetting serverSetting;

    /**
     * 运行状态
     */
    protected final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 会话管理：sessionId -> SyncSession
     */
    protected final Map<String, SyncSession> sessions = new ConcurrentHashMap<>();

    /**
     * 连接监听器列表
     */
    protected final List<SyncConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    /**
     * 消息监听器列表
     */
    protected final List<SyncMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    /**
     * 构造方法
     *
     * @param serverSetting 服务端设置
     */
    public AbstractSyncServer(ServerSetting serverSetting) {
        this.serverSetting = serverSetting;
    }

    // ==================== SyncServer 接口实现 ====================

    @Override
    public void start() throws Exception {
        if (running.get()) {
            if (log.isDebugEnabled()) {
                log.debug("[SyncServer] 服务启动，无需重复启动");
            }
            return;
        }

        boolean result = doStart();
        if (result) {
            running.set(true);
            log.info("[SyncServer] 服务启动成功，端口: {}", getPort());
        } else {
            throw new Exception("[SyncServer] 服务启动失败");
        }
    }

    @Override
    public void stop() throws Exception {
        if (!running.get()) {
            return;
        }

        try {
            // 关闭所有会话
            for (SyncSession session : sessions.values()) {
                try {
                    session.close();
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SyncServer] 关闭会话异常: {}", session.getSessionId(), e);
                    }
                }
            }
            sessions.clear();

            doStop();
        } finally {
            running.set(false);
            log.info("[SyncServer] 服务停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPort() {
        return serverSetting.getPort();
    }

    @Override
    public SyncServer addConnectionListener(SyncConnectionListener listener) {
        if (listener != null) {
            connectionListeners.add(listener);
        }
        return this;
    }

    @Override
    public SyncServer addMessageListener(SyncMessageListener listener) {
        if (listener != null) {
            messageListeners.add(listener);
        }
        return this;
    }

    @Override
    public void send(String sessionId, String topic, Object data) {
        SyncSession session = sessions.get(sessionId);
        if (session != null && session.isConnected()) {
            try {
                session.send(topic, data);
            } catch (Exception e) {
                log.error("[SyncServer] 发送消息失败: sessionId={}, topic={}", sessionId, topic, e);
            }
        } else {
            log.warn("[SyncServer] 会话不存在或断开: sessionId={}", sessionId);
        }
    }

    @Override
    public void broadcast(String topic, Object data) {
        SyncMessage message = SyncMessage.of(topic, data);
        String json = Json.toJson(message);

        for (SyncSession session : sessions.values()) {
            if (session.isConnected()) {
                try {
                    doSendRaw(session, json);
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SyncServer] 广播消息到会话失败: sessionId={}", session.getSessionId(), e);
                    }
                }
            }
        }
    }

    @Override
    public void broadcastExclude(String excludeSessionId, String topic, Object data) {
        SyncMessage message = SyncMessage.of(topic, data);
        String json = Json.toJson(message);

        for (SyncSession session : sessions.values()) {
            if (session.isConnected() && !session.getSessionId().equals(excludeSessionId)) {
                try {
                    doSendRaw(session, json);
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SyncServer] 广播消息到会话失败: sessionId={}", session.getSessionId(), e);
                    }
                }
            }
        }
    }

    @Override
    public SyncSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public Collection<SyncSession> getAllSessions() {
        return sessions.values();
    }

    @Override
    public int getConnectionCount() {
        return sessions.size();
    }

    @Override
    public void closeSession(String sessionId) {
        SyncSession session = sessions.remove(sessionId);
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[SyncServer] 关闭会话异常: sessionId={}", sessionId, e);
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            stop();
        } catch (Exception e) {
            log.error("[SyncServer] 关闭服务异常", e);
        }
    }

    // ==================== 子类需要实现的方法 ====================

    /**
     * 执行启动
     *
     * @return 启动是否成功
     * @throws Exception 启动异常
     */
    protected abstract boolean doStart() throws Exception;

    /**
     * 执行停止
     *
     * @throws Exception 停止异常
     */
    protected abstract void doStop() throws Exception;

    /**
     * 发送原始消息（已序列化的JSON）
     * <p>
     * 子类实现底层的发送逻辑
     *
     * @param session 会话
     * @param json    JSON消息
     * @throws Exception 发送异常
     */
    protected abstract void doSendRaw(SyncSession session, String json) throws Exception;

    // ==================== 子类可调用的会话生命周期方法 ====================

    /**
     * 注册会话（子类在客户端连接时调用）
     *
     * @param session 会话
     */
    protected void registerSession(SyncSession session) {
        String sessionId = session.getSessionId();
        sessions.put(sessionId, session);
        if (log.isDebugEnabled()) {
            log.debug("[SyncServer] 会话注册: sessionId={}, 当前连接数: {}", sessionId, sessions.size());
        }

        // 通知连接监听器
        notifyConnect(session);

        // 广播连接事件给其他客户端
        broadcastExclude(sessionId, SyncTopic.CONNECT.getName(), Map.of(
                "sessionId", sessionId,
                "onlineCount", sessions.size(),
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 注销会话（子类在客户端断开时调用）
     *
     * @param sessionId 会话ID
     */
    protected void unregisterSession(String sessionId) {
        SyncSession session = sessions.remove(sessionId);
        if (session != null) {
            if (log.isDebugEnabled()) {
                log.debug("[SyncServer] 会话注销: sessionId={}, 剩余连接数: {}", sessionId, sessions.size());
            }

            // 通知断开监听器
            notifyDisconnect(session);

            // 广播断开事件
            broadcast(SyncTopic.DISCONNECT.getName(), Map.of(
                    "sessionId", sessionId,
                    "onlineCount", sessions.size(),
                    "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * 处理接收到的消息（子类在收到消息时调用）
     *
     * @param sessionId 会话ID
     * @param text      消息文本（JSON格式）
     */
    protected void handleMessage(String sessionId, String text) {
        try {
            SyncMessage message = Json.fromJson(text, SyncMessage.class);
            if (message != null) {
                SyncSession session = sessions.get(sessionId);
                if (session != null) {
                    notifyMessage(session, message);
                }
            }
        } catch (Exception e) {
            log.error("[SyncServer] 解析消息失败: sessionId={}, text={}", sessionId, text, e);
        }
    }

    // ==================== 监听器通知方法 ====================

    /**
     * 通知连接监听器（客户端连接）
     *
     * @param session 会话
     */
    protected void notifyConnect(SyncSession session) {
        for (SyncConnectionListener listener : connectionListeners) {
            try {
                listener.onConnect(session);
            } catch (Exception e) {
                log.error("[SyncServer] 连接监听器处理异常", e);
            }
        }
    }

    /**
     * 通知连接监听器（客户端断开）
     *
     * @param session 会话
     */
    protected void notifyDisconnect(SyncSession session) {
        for (SyncConnectionListener listener : connectionListeners) {
            try {
                listener.onDisconnect(session);
            } catch (Exception e) {
                log.error("[SyncServer] 断开监听器处理异常", e);
            }
        }
    }

    /**
     * 通知消息监听器
     *
     * @param session 会话
     * @param message 消息
     */
    protected void notifyMessage(SyncSession session, SyncMessage message) {
        for (SyncMessageListener listener : messageListeners) {
            try {
                listener.onMessage(session, message);
            } catch (Exception e) {
                log.error("[SyncServer] 消息监听器处理异常", e);
            }
        }
    }

    /**
     * 通知消息监听器（便捷方法，自动封装为SyncMessage）
     *
     * @param session 会话
     * @param topic   主题
     * @param data    数据
     */
    protected void notifyMessage(SyncSession session, String topic, Object data) {
        SyncMessage message = SyncMessage.of(topic, data);
        notifyMessage(session, message);
    }
}
