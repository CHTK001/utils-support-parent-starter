package com.chua.common.support.network.frp;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
     * 是否已有数据监听器注册：为 true 后数据面字节直接交付、不再进入早期缓冲
     */
    private volatile boolean listenerRegistered;

    /**
     * 注册重放进行中：重放早期字节期间，新到达字节继续入缓冲，保证按读取顺序交付
     */
    private volatile boolean replayingPending;

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
        sendBytes(data, 0, data.length);
    }

    /**
     * 向通道对端发送二进制数据切片（零拷贝：直接把 {@code data[off, off+len)} 写入数据面，不整段复制）。
     *
     * @param data 字节数据
     * @param off  起始偏移
     * @param len  数据长度
     */
    public void sendBytes(byte[] data, int off, int len) {
        FrpTunnelStream stream = dataStream;
        if (stream != null && !stream.isClosed()) {
            try {
                stream.send(data, off, len);
            } catch (Exception e) {
                close();
            }
        } else {
            log.debug("FRP sendBytes skipped: channel={}, streamActive={}", channelId,
                    stream != null && !stream.isClosed());
        }
    }

    /**
     * 注册字节数据监听器（拷贝语义：每次交付获得一份独立拷贝）。
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
            beginReplay();
        }
        drainPendingOnRegister();
        return this;
    }

    /**
     * 注册零拷贝字节数据监听器：回调直接收到数据面读缓冲的 {@code (buf, off, len)} 切片，
     * 仅在本次同步回调内有效；需要在回调外保留数据时必须在回调内自行拷贝。
     *
     * <p>注册前已到达的数据平面字节会按序重放给本监听器。重放与实时交付全程在锁外执行，
     * 监听器内的阻塞操作（如下游 socket 写）不会持 {@link #bytesLock} 卡死数据面。</p>
     *
     * @param listener 零拷贝字节数据监听器
     * @return 当前会话，支持链式调用
     */
    public FrpTunnelSession onBytes(FrpByteConsumer listener) {
        synchronized (bytesLock) {
            directBytesListeners.add(listener);
            beginReplay();
        }
        drainPendingOnRegister();
        return this;
    }

    /**
     * 标记进入注册重放窗口：期间新到达字节继续入早期缓冲，保证按读取顺序交付。
     * 须在持有 {@link #bytesLock} 时调用。
     */
    private void beginReplay() {
        listenerRegistered = true;
        replayingPending = true;
    }

    /**
     * 重放早期缓冲字节（锁外回调）。循环排空直到缓冲为空才放开实时分发，
     * 保证「注册前字节 → 注册后字节」按到达顺序交付，重放期间新到达的字节也不丢失。
     */
    private void drainPendingOnRegister() {
        List<byte[]> pending;
        while (true) {
            boolean empty;
            synchronized (bytesLock) {
                pending = new ArrayList<>(pendingBytes);
                pendingBytes.clear();
                pendingBytesSize = 0;
                empty = pending.isEmpty();
                if (empty) {
                    replayingPending = false;
                }
            }
            for (byte[] buffered : pending) {
                dispatchToListeners(buffered, 0, buffered.length);
            }
            if (empty) {
                break;
            }
        }
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
        // 流死亡（EOF/异常）联动会话拆除：否则会话不知数据面已死，
        // sendBytes 静默丢字节、双侧读取永久等待
        stream.onDeath(this::dispatchClose);
        stream.startRead(this::dispatchBytes);
    }

    /**
     * 关闭通道。
     */
    public void close() {
        log.debug("[frp] 会话主动关闭: channel={}", channelId);
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
     * 分发数据平面收到的原始字节切片（零拷贝）。
     *
     * <p>由 {@link FrpTunnelStream} 的读线程调用。监听器回调在锁外执行：
     * 锁只保护「注册前早期缓冲 ↔ 注册重放」的顺序交接，
     * 监听器内的阻塞操作不会持 {@link #bytesLock} 卡死数据面分发线程。</p>
     *
     * @param buf 底层读缓冲
     * @param off 数据起始偏移
     * @param len 数据长度
     */
    void dispatchBytes(byte[] buf, int off, int len) {
        if (!open) {
            return;
        }
        boolean buffered = false;
        boolean overflow = false;
        if (!listenerRegistered || replayingPending) {
            synchronized (bytesLock) {
                if (!listenerRegistered || replayingPending) {
                    // 监听器尚未注册或正在重放（建隧竞态窗口）：先拷贝缓冲、注册时重放，避免首包静默丢失
                    if (pendingBytesSize + len > MAX_PENDING_BYTES) {
                        overflow = true;
                    } else {
                        pendingBytes.add(Arrays.copyOfRange(buf, off, off + len));
                        pendingBytesSize += len;
                    }
                    buffered = true;
                }
            }
        }
        if (overflow) {
            log.warn("[frp] 早期字节缓冲超限且监听器未注册，关闭通道: channel={}, limitBytes={}",
                    channelId, MAX_PENDING_BYTES);
            close();
            return;
        }
        if (buffered) {
            return;
        }
        dispatchToListeners(buf, off, len);
    }

    /**
     * 把一段字节交付给全部监听器：零拷贝监听器收原始切片，拷贝监听器共享一次拷贝。
     * 全程不持锁，单个监听器异常不影响其它监听器与数据面。
     *
     * @param buf 字节缓冲
     * @param off 数据起始偏移
     * @param len 数据长度
     */
    private void dispatchToListeners(byte[] buf, int off, int len) {
        for (FrpByteConsumer listener : directBytesListeners) {
            try {
                listener.accept(buf, off, len);
            } catch (Exception e) {
                log.debug("[frp] 回调执行失败，已忽略: {}", e.getMessage());
            }
        }
        if (!bytesListeners.isEmpty()) {
            byte[] copy = Arrays.copyOfRange(buf, off, off + len);
            for (Consumer<byte[]> listener : bytesListeners) {
                try {
                    listener.accept(copy);
                } catch (Exception e) {
                    log.debug("[frp] 回调执行失败，已忽略: {}", e.getMessage());
                }
            }
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
        log.debug("[frp] 会话关闭分发: channel={}", channelId);
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