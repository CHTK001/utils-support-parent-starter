package com.chua.common.support.network.protocol.sync.http;

import com.chua.common.support.network.protocol.sync.SyncSession;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * HTTP同步会话实现
 * <p>
 * HTTP是无状态的，此会话用于兼容接口和存储临时数据。
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/12/11
 */
@Getter
public class HttpSyncSession implements SyncSession {

    /**
     * 会话ID
     */
    private final String sessionId;

    /**
     * 远程地址
     */
    private final InetSocketAddress remoteAddress;

    /**
     * 创建时间
     */
    private final Instant createTime;

    /**
     * 最后活跃时间
     */
    private volatile Instant lastActiveTime;

    /**
     * 会话属性
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    public HttpSyncSession(String sessionId, InetSocketAddress remoteAddress) {
        this.sessionId = sessionId;
        this.remoteAddress = remoteAddress;
        this.createTime = Instant.now();
        this.lastActiveTime = this.createTime;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean isConnected() {
        return !closed;
    }

    @Override
    public void send(String topic, Object data) {
        // HTTP是单向上报，不支持下发
        throw new UnsupportedOperationException("HTTP同步协议不支持下发消息");
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        } else {
            attributes.remove(key);
        }
    }

    /**
     * 更新最后活跃时间
     */
    public void updateLastActiveTime() {
        this.lastActiveTime = Instant.now();
    }

    /**
     * 获取远程地址字符串
     *
     * @return 远程地址
     */
    public String getRemoteAddressString() {
        return remoteAddress != null ? remoteAddress.toString() : "unknown";
    }
}
