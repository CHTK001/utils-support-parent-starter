package com.chua.common.support.network.frp;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * FRP 隧道会话，表示访问方与服务提供方之间的一条双向数据通道。
 *
 * <p>会话建立后，两端通过 {@link #sendBytes(byte[])} 发送数据、
 * 通过 {@link #onBytes(Consumer)} 接收对端数据、
 * 通过 {@link #close()} 关闭通道。数据经 frp 数据平面裸字节流直连转发。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class FrpTunnelSession {

    /**
     * 关联的 FRP 客户端
     */
    private final FrpClient client;

    /**
     * 通道标识
     */
    private final String channelId;

    /**
     * 目标描述（targetHost:targetPort）
     */
    private final String target;

    /**
     * 字节数据监听器列表
     */
    private final List<Consumer<byte[]>> bytesListeners = new CopyOnWriteArrayList<>();

    /**
     * 零拷贝字节数据监听器列表：回调直接交付底层读缓冲切片（仅回调期内有效）
     */
    private final List<FrpByteConsumer> directBytesListeners = new CopyOnWriteArrayList<>();

    /**
     * 关闭监听器列表
     */
    private final List<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();

    /**
     * 字节分发锁：保证「监听器注册前的早期字节」与「注册后的实时字节」按到达顺序交付
     */
    private final Object bytesLock = new Object();

    /**
     * 监听器注册前到达的字节缓冲（修复建隧竞态期首包丢失）
     */
    private final ArrayDeque<byte[]> pendingBytes = new ArrayDeque<>();

    /**
     * 缓冲中的字节总量
     */
    private int pendingBytesSize = 0;

    /**
     * 早期字节缓冲上限：超过说明消费侧始终未注册监听，关闭会话以暴露问题而非静默丢弃
     */
    private static final int MAX_PENDING_BYTES = 512 * 1024;

    /**
     * 通道是否处于打开状态
     */
    private volatile boolean open = true;

    /**
     * frp 数据平面连接（承载裸字节流）
     */
    private volatile FrpTunnelStream dataStream;

    /**
     * 创建隧道会话。
     *
     * @param client    关联的 FRP 客户端
     * @param channelId 通道标识
     * @param target    目标描述
     */
    FrpTunnelSession(FrpClient client, String channelId, String target) {
        this.client = client;
        this.channelId = channelId;
        this.target = target;
    }

    /**
     * 获取通道标识。
     *
     * @return 通道标识
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * 获取目标描述。
     *
     * @return 目标描述
     */
    public String getTarget() {
        return target;
    }

    /**
     * 判断通道是否处于打开状态。
     *
     * @return true 表示通道已打开
     */
    public boolean isOpen() {
        return open;
    }

    /**
     * 向通道对端发送二进制数据（走 frp 数据平面裸字节流）。
     *
     * @param data 字节数据
     */
    public void sendBytes(byte[] data) {
        FrpTunnelStream stream = dataStream;
        if (stream != null && !stream.isClosed()) {
            try {
                stream.send(data);
            } catch (Exception e) {
                close();
            }
        } else {
            log.debug("FRP sendBytes skipped: channel={}, streamActive={}", channelId,
                    stream != null && !stream.isClosed());
        }
    }

    /**
     * 注册字节数据监听器。
     *
     * <p>注册前已到达的数据平面字节会按序重放给本监听器，避免建隧竞态期首包丢失
     * （突发建隧时，对端数据可能先于消费方完成本地桥接与监听器注册到达）。</p>
     *
     * @param listener 字节数据监听器
     * @return 当前会话，支持链式调用
     */
    public FrpTunnelSession onBytes(Consumer<byte[]> listener) {
        synchronized (bytesLock) {
            bytesListeners.add(listener);
            while (!pendingBytes.isEmpty()) {
                byte[] buffered = pendingBytes.poll();
                pendingBytesSize -= buffered.length;
                try {
                    listener.accept(buffered);
                } catch (Exception e) {
                    log.debug("[frp] 回调执行失败，已忽略: {}", e.getMessage());
                }
            }
        }
        return this;
    }

    /**
     * 注册关闭监听器。
     *
     * <p>若会话在注册前已关闭（如对端秒拒场景），立即补偿回调，避免监听器永远收不到关闭事件。</p>
     *
     * @param listener 关闭监听器，参数为通道标识
     * @return 当前会话，支持链式调用
     */
    public FrpTunnelSession onClose(Consumer<String> listener) {
        closeListeners.add(listener);
        if (!open) {
            listener.accept(channelId);
        }
        return this;
    }

    /**
     * 绑定 frp 数据平面连接。
     *
     * @param stream 数据平面连接
     */
    void attachStream(FrpTunnelStream stream) {
        this.dataStream = stream;
        stream.startRead(this::dispatchBytes);
    }

    /**
     * 关闭通道。
     */
    public void close() {
        synchronized (bytesLock) {
            pendingBytes.clear();
            pendingBytesSize = 0;
        }
        FrpTunnelStream stream = dataStream;
        if (stream != null) {
            stream.close();
        }
        client.closeTunnel(channelId);
        dispatchClose();
    }

    /**
     * 分发数据平面收到的原始字节。
     *
     * <p>由 {@link FrpTunnelStream} 的读线程调用。</p>
     *
     * @param data 原始字节数据
     */
    void dispatchBytes(byte[] data) {
        if (!open) {
            return;
        }
        boolean overflow = false;
        synchronized (bytesLock) {
            if (bytesListeners.isEmpty()) {
                // 监听器尚未注册（建隧竞态窗口）：先缓冲、注册时重放，避免首包静默丢失
                if (pendingBytesSize + data.length > MAX_PENDING_BYTES) {
                    overflow = true;
                } else {
                    pendingBytes.add(data);
                    pendingBytesSize += data.length;
                }
            } else {
                for (Consumer<byte[]> listener : bytesListeners) {
                    try {
                        listener.accept(data);
                    } catch (Exception e) {
                        log.debug("[frp] 回调执行失败，已忽略: {}", e.getMessage());
                    }
                }
            }
        }
        if (overflow) {
            log.warn("[frp] 早期字节缓冲超限且监听器未注册，关闭通道: channel={}, limitBytes={}",
                    channelId, MAX_PENDING_BYTES);
            close();
        }
    }

    /**
     * 分发关闭事件给监听器。
     */
    void dispatchClose() {
        if (!open) {
            return;
        }
        open = false;
        FrpTunnelStream stream = dataStream;
        if (stream != null) {
            stream.close();
        }
        for (Consumer<String> listener : closeListeners) {
            try {
                listener.accept(channelId);
            } catch (Exception e) {
                log.debug("[frp] 操作失败，已忽略: {}", e.getMessage());
            }
        }
    }
}