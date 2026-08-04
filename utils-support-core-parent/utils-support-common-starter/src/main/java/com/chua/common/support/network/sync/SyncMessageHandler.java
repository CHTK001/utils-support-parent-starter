package com.chua.common.support.network.sync;

import org.jspecify.annotations.NullUnmarked;

/**
 * 同步消息处理器，用于处理客户端订阅的特定主题消息。
 *
 * @author CH
 */
@NullUnmarked
@FunctionalInterface
public interface SyncMessageHandler {

    /**
     * 处理收到的同步消息。
     *
     * @param topic   消息主题
     * @param message 消息内容
     */
    void handle(String topic, Object message);
}
