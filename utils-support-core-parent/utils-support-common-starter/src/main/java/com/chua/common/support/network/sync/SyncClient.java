package com.chua.common.support.network.sync;

import java.util.Map;

/**
 * 同步客户端接口，基于长连接与服务端进行双向数据同步。
 * <p>
 * 客户端与服务端保持持久连接，支持主题订阅、消息发送和服务端主动推送接收。
 * </p>
 *
 * @author CH
 */
public interface SyncClient extends AutoCloseable {

    /**
     * 连接到同步服务端。
     */
    void connect();

    /**
     * 断开与服务端的连接。
     */
    void disconnect();

    /**
     * 判断客户端是否已连接。
     *
     * @return true 表示已连接
     */
    boolean isConnected();

    /**
     * 获取客户端标识。
     *
     * @return 客户端标识
     */
    String getClientId();

    /**
     * 向指定主题发送消息。
     *
     * @param topic   主题
     * @param message 消息内容
     */
    void send(String topic, Object message);

    /**
     * 订阅指定主题的消息。
     *
     * @param topic   主题
     * @param handler 消息处理器
     */
    void subscribe(String topic, SyncMessageHandler handler);

    /**
     * 取消订阅指定主题。
     *
     * @param topic 主题
     */
    void unsubscribe(String topic);

    /**
     * 添加同步事件监听器。
     *
     * @param listener 监听器
     */
    void addListener(SyncFlowListener listener);

    /**
     * 移除同步事件监听器。
     *
     * @param listener 监听器
     */
    void removeListener(SyncFlowListener listener);

    /**
     * 获取客户端元数据。
     *
     * @return 元数据映射
     */
    Map<String, Object> getMetadata();

    @Override
    void close();
}
