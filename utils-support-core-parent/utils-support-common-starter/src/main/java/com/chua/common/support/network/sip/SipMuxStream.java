package com.chua.common.support.network.sip;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * SIP 多路复用虚拟流：单条共享连接上的单通道视图，
 * API 语义与 {@link SipTunnelStream} 对齐（send/startRead/close）。
 *
 * @author CH
 * @since 4.0.0.42
*/
@Slf4j
class SipMuxStream {

    /**
    * 通道标识
    */
    private final String channelId;

    /**
    * 所属共享连接
    */
    private final SipMuxConnection connection;

    /**
    * 数据消费者
    */
    private volatile Consumer<byte[]> dataConsumer;

    /**
    * 对端关闭回调
    */
    private volatile Runnable peerCloseCallback;

    /**
    * 消费者挂载前到达的数据缓冲
    */
    private final java.util.List<byte[]> pendingData = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
    * 是否已关闭
    */
    private volatile boolean closed;

    SipMuxStream(String channelId, SipMuxConnection connection) {
        this.channelId = channelId;
        this.connection = connection;
    }

    /**
    * 获取通道标识。
    *
    * @return 通道标识
    */
    String channelId() {
        return channelId;
    }

    /**
    * 挂载数据与关闭回调。
    *
    * @param data    数据消费者
    * @param onClose 对端关闭/通道关闭回调
    */
    void attach(Consumer<byte[]> data, Runnable onClose) {
        this.dataConsumer = data;
        this.peerCloseCallback = onClose;
    }

    /**
    * 发送数据。
    *
    * @param payload 负载
    */
    void send(byte[] payload) {
       
        if (!closed) {
            try {
                connection.sendFrame(channelId, payload);
            } catch (Exception e) {
                log.debug("SIP mux 发送失败: {}", e.getMessage());
                peerClosed();
            }
        }
    }

    /**
    * 启动读取（挂载消费者并重放缓冲）。
    *
    * @param consumer 数据消费者
    */
    void startRead(Consumer<byte[]> consumer) {
        this.dataConsumer = consumer;
        java.util.List<byte[]> early;
        synchronized (pendingData) {
            early = new java.util.ArrayList<>(pendingData);
            pendingData.clear();
        }
        for (byte[] data : early) {
            consumer.accept(data);
        }
    }

    /**
    * 关闭本通道。
    */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        connection.closeChannel(channelId);
    }

    /**
    * 是否已关闭。
    *
    * @return true 表示已关闭
    */
    boolean isClosed() {
        return closed;
    }

    /**
    * 分发对端数据（由共享连接读循环调用；消费者未挂载时缓冲）。
    *
    * @param data 数据
    */
    void dispatch(byte[] data) {
       
        Consumer<byte[]> consumer = dataConsumer;
        if (consumer == null) {
            synchronized (pendingData) {
                if (dataConsumer == null) {
                    if (pendingData.size() < 256) {
                        pendingData.add(data);
                    }
                    return;
                }
            }
        }
        if (!closed && consumer != null) {
            consumer.accept(data);
        }
    }

    /**
    * 对端/通道已关闭。
    */
    void peerClosed() {
        if (closed) {
            return;
        }
        closed = true;
        Runnable callback = peerCloseCallback;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception ignored) {
            }
        }
    }

    /**
    * 标记关闭（不发送帧，由 closeChannel 统一发送）。
    */
    void markClosed() {
        closed = true;
    }
}
