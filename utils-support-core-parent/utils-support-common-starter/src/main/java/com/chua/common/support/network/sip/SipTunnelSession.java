package com.chua.common.support.network.sip;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * SIP 隧道会话，表示访问方与服务提供方之间的一条双向数据通道。
 *
 * <p>会话建立后，两端通过 {@link #sendBytes(byte[])} 发送数据、
 * 通过 {@link #onBytes(Consumer)} 接收对端数据、
 * 通过 {@link #close()} 关闭通道。数据经 frp 数据平面裸字节流直连转发。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SipTunnelSession {

    /**
     * 关联的 SIP 客户端
     */
    private final SipClient client;

    /**
     * 通道标识
     */
    private final String channelId;

    /**
     * 服务名称
     */
    private final String serviceName;

    /**
     * 数据监听器列表
     */
    private final List<Consumer<String>> dataListeners = new CopyOnWriteArrayList<>();

    /**
     * 字节数据监听器列表
     */
    private final List<Consumer<byte[]>> bytesListeners = new CopyOnWriteArrayList<>();

    /**
     * 关闭监听器列表
     */
    private final List<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();

    /**
     * 通道是否处于打开状态
     */
    private volatile boolean open = true;

    /**
     * frp 数据平面连接（承载裸字节流）
     */
    private volatile SipTunnelStream dataStream;

    /**
     * 创建隧道会话。
     *
     * @param client      关联的 SIP 客户端
     * @param channelId   通道标识
     * @param serviceName 服务名称
     */
    SipTunnelSession(SipClient client, String channelId, String serviceName) {
        this.client = client;
        this.channelId = channelId;
        this.serviceName = serviceName;
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
     * 获取服务名称。
     *
     * @return 服务名称
     */
    public String getServiceName() {
        return serviceName;
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
        SipTunnelStream stream = dataStream;
        if (stream != null && !stream.isClosed()) {
            try {
                stream.send(data);
            } catch (Exception e) {
                close();
            }
        }
    }

    /**
     * 注册数据监听器。
     *
     * @param listener 数据监听器
     * @return 当前会话，支持链式调用
     */
    public SipTunnelSession onData(Consumer<String> listener) {
        dataListeners.add(listener);
        return this;
    }

    /**
     * 注册字节数据监听器。
     *
     * @param listener 字节数据监听器
     * @return 当前会话，支持链式调用
     */
    public SipTunnelSession onBytes(Consumer<byte[]> listener) {
        bytesListeners.add(listener);
        return this;
    }

    /**
     * 注册关闭监听器。
     *
     * @param listener 关闭监听器，参数为通道标识
     * @return 当前会话，支持链式调用
     */
    public SipTunnelSession onClose(Consumer<String> listener) {
        closeListeners.add(listener);
        return this;
    }

    /**
     * 绑定 frp 数据平面连接。
     *
     * @param stream 数据平面连接
     */
    void attachStream(SipTunnelStream stream) {
        this.dataStream = stream;
        stream.startRead(this::dispatchBytes);
    }

    /**
     * 是否已启用数据平面。
     *
     * @return true 表示已启用
     */
    public boolean isStreamActive() {
        return dataStream != null && !dataStream.isClosed();
    }

    /**
     * 关闭通道。
     */
    public void close() {
        SipTunnelStream stream = dataStream;
        if (stream != null) {
            stream.close();
        }
        client.closeTunnel(channelId);
        dispatchClose();
    }

    /**
     * 分发收到的数据给监听器。
     *
     * @param data 数据内容
     */
    void dispatchData(String data) {
        for (Consumer<String> listener : dataListeners) {
            try {
                listener.accept(data);
            } catch (Exception ignored) {
            }
        }
        for (Consumer<byte[]> listener : bytesListeners) {
            try {
                listener.accept(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 分发关闭事件给监听器。
     */
    void dispatchClose() {
        open = false;
        SipTunnelStream stream = dataStream;
        if (stream != null) {
            stream.close();
        }
        for (Consumer<String> listener : closeListeners) {
            try {
                listener.accept(channelId);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 分发数据平面收到的原始字节（跳过 Base64 解码）。
     *
     * <p>由 {@link SipTunnelStream} 的读线程调用。</p>
     *
     * @param data 原始字节数据
     */
    void dispatchBytes(byte[] data) {
        if (!open) {
            return;
        }
        for (Consumer<byte[]> listener : bytesListeners) {
            try {
                listener.accept(data);
            } catch (Exception ignored) {
            }
        }
    }
}
