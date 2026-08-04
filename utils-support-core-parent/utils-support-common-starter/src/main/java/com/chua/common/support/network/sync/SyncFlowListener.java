package com.chua.common.support.network.sync;

import org.jspecify.annotations.NullUnmarked;

/**
 * 同步事件监听器，接收 {@link SyncFlow} 的生命周期和数据事件。
 *
 * @author CH
 */
@NullUnmarked
public interface SyncFlowListener {

    /**
     * 同步流程启动时回调。
     */
    default void onStart() {
    }

    /**
     * 同步流程停止时回调。
     */
    default void onStop() {
    }

    /**
     * 收到同步消息时回调。
     *
     * @param topic   消息主题
     * @param message 消息内容
     */
    default void onMessage(String topic, Object message) {
    }

    /**
     * 同步发生异常时回调。
     *
     * @param topic 消息主题
     * @param cause 异常
     */
    default void onError(String topic, Throwable cause) {
    }

    /**
     * 客户端连接建立时回调。
     *
     * @param clientId 客户端标识
     */
    default void onClientConnected(String clientId) {
    }

    /**
     * 客户端连接断开时回调。
     *
     * @param clientId 客户端标识
     */
    default void onClientDisconnected(String clientId) {
    }
}
