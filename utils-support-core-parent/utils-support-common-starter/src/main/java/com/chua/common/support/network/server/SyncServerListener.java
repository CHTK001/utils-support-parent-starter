package com.chua.common.support.network.server;


/**
 * 同步服务端事件监听器，接收客户端连接、断开和消息事件。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SyncServerListener {

    /**
     * 客户端连接建立时回调。
     *
     * @param clientId 客户端标识
     * @param metadata 客户端元数据
     */
    default void onClientConnected(String clientId, java.util.Map<String, Object> metadata) {
    }

    /**
     * 客户端连接断开时回调。
     *
     * @param clientId 客户端标识
     */
    default void onClientDisconnected(String clientId) {
    }

    /**
     * 收到客户端消息时回调。
     *
     * @param clientId 客户端标识
     * @param topic    消息主题
     * @param message  消息内容
     */
    default void onMessage(String clientId, String topic, Object message) {
    }

    /**
     * 服务端发生异常时回调。
     *
     * @param clientId 客户端标识
     * @param cause    异常
     */
    default void onError(String clientId, Throwable cause) {
    }
}
