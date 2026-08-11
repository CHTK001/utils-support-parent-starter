package com.chua.common.support.taskdistribution.scattergather;

import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncMessageHandler;

/**
 * SyncServerListener 适配器，将 SyncMessageHandler 适配为 SyncServerListener。
 *
 * <p>当收到指定 topic 的消息时，委托给 SyncMessageHandler 处理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SyncMessageListenerAdapter implements SyncServerListener {

    /**
     * 监听的 topic
     */
    private final String topic;

    /**
     * 消息处理器
     */
    private final SyncMessageHandler handler;

    /**
     * 构造适配器。
     *
     * @param topic   主题
     * @param handler 消息处理器
     */
    public SyncMessageListenerAdapter(String topic, SyncMessageHandler handler) {
        this.topic = topic;
        this.handler = handler;
    }

    @Override
    public void onMessage(String clientId, String messageTopic, Object message) {
        if (topic == null || topic.equals(messageTopic)) {
            handler.handle(topic != null ? topic : messageTopic, message);
        }
    }
}