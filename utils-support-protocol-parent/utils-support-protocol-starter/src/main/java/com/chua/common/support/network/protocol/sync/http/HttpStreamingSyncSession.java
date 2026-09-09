package com.chua.common.support.network.protocol.sync.http;

import com.chua.common.support.network.protocol.sync.SyncMessage;
import com.chua.common.support.network.protocol.sync.SyncSession;
import com.chua.common.support.text.json.Json;
import lombok.Getter;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class HttpStreamingSyncSession implements SyncSession {

    private final String sessionId;
    private final InetSocketAddress remoteAddress;
    private final Instant createTime;
    private volatile Instant lastActiveTime;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final OutputStream outputStream;
    private final Object writeLock = new Object();
    private volatile boolean closed = false;

    public HttpStreamingSyncSession(String sessionId, InetSocketAddress remoteAddress, OutputStream outputStream) {
        this.sessionId = sessionId;
        this.remoteAddress = remoteAddress;
        this.outputStream = outputStream;
        this.createTime = Instant.now();
        this.lastActiveTime = this.createTime;
    }

    @Override
    public boolean isConnected() {
        return !closed && outputStream != null;
    }

    @Override
    public void send(String topic, Object data) {
        SyncMessage message = SyncMessage.of(topic, data);
        sendRaw(Json.toJson(message));
    }

    public void sendRaw(String json) {
        if (closed || outputStream == null) {
            throw new IllegalStateException("会话尚未建立连接");
        }
        String payload = "data: " + json + "\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                outputStream.write(bytes);
                outputStream.flush();
                lastActiveTime = Instant.now();
            } catch (Exception e) {
                closed = true;
                throw new RuntimeException(e);
            }
        }
    }

    public void sendKeepAlive() {
        if (closed || outputStream == null) {
            return;
        }
        synchronized (writeLock) {
            try {
                outputStream.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                lastActiveTime = Instant.now();
            } catch (Exception e) {
                closed = true;
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (Exception ignored) {
            }
        }
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

    public String getRemoteAddressString() {
        return remoteAddress != null ? remoteAddress.toString() : "unknown";
    }
}
