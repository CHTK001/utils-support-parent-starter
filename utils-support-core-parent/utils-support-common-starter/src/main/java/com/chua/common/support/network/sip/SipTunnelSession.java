package com.chua.common.support.network.sip;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * SIP 隧道会话，表示访问方与服务提供方之间的一条双向数据通道。
 *
 * <p>会话建立后，两端可通过 {@link #send(String)} 发送文本、
 * {@link #sendBytes(byte[])} 发送二进制数据（内部 Base64 编码），
 * 通过 {@link #onData(Consumer)} / {@link #onBytes(Consumer)} 接收对端数据，
 * 通过 {@link #close()} 关闭通道。数据帧经 SipServer 按通道标识路由转发。</p>
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
     * 向通道对端发送数据。
     *
     * @param data 数据内容
     */
    public void send(String data) {
        client.sendTunnelData(channelId, data);
    }

    /**
     * 向通道对端发送二进制数据（内部 Base64 编码）。
     *
     * @param data 字节数据
     */
    public void sendBytes(byte[] data) {
        client.sendTunnelData(channelId, Base64.getEncoder().encodeToString(data));
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
     * 关闭通道。
     */
    public void close() {
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
        byte[] bytes = null;
        for (Consumer<byte[]> listener : bytesListeners) {
            if (bytes == null) {
                bytes = Base64.getDecoder().decode(data);
            }
            try {
                listener.accept(bytes);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 分发关闭事件给监听器。
     */
    void dispatchClose() {
        open = false;
        for (Consumer<String> listener : closeListeners) {
            try {
                listener.accept(channelId);
            } catch (Exception ignored) {
            }
        }
    }
}
