package com.chua.common.support.ai.bot;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;
import org.jspecify.annotations.NullUnmarked;

/**
 * Bot 入站消息
 * <p>
 * 封装从平台接收到的用户消息，支持文本、图片、语音、视频、文件等类型。
 * </p>
 *
 * @author CH
 * @since 2026/07/18
 */
@Data
@Builder
@SuppressWarnings({"NullAway", "unchecked"})
@NullUnmarked
public class BotInboundMessage {

    /** 消息类型枚举 */
    public enum Type {

        /** 文本类型 */
        TEXT,

        /** 图片类型 */
        IMAGE,

        /** 语音类型 */
        VOICE,

        /** 视频类型 */
        VIDEO,

        /** 文件类型 */
        FILE,

        /** 位置类型 */
        LOCATION,

        /** 事件类型（订阅、点击等）*/
        EVENT,

        /** 未知类型 */
        UNKNOWN
    }

    /** 消息唯一标识 ID */
    private String msgId;

    /** 消息类型 */
    /**
     * 类型
     */
    private Type type;

    /**
     * 消息内容
     * <p>TEXT 类型时包含文本</p>
     */
    private String content;

    /**
     * 发送者 ID
     * <p>openid / uid / open_id</p>
     */
    private String fromUser;

    /**
     * 发送者名称
     * <p>username / name</p>
     */
    private String fromUserName;

    /** 目标用户 ID（Bot 回复对象）*/
    private String toUser;

    /** 消息创建时间戳（毫秒）*/
    private long createTime;

    /**
     * 媒体文件 URL
     * <p>IMAGE/VOICE/VIDEO/FILE 类型时</p>
     */
    private String mediaUrl;

    /**
     * 媒体 ID
     * <p>平台返回的 MediaId</p>
     */
    private String mediaId;

    /**
     * 事件类型
     * <p>EVENT 类型时为 "subscribe"、"unsubscribe"、"click" 等</p>
     */
    private String eventType;

    /** 事件 Key */
    private String eventKey;

    /**
     * 会话 ID
     * <p>群聊为 groupid / chatid</p>
     */
    private String chatId;

    /** 是否来自群组 */
    private boolean fromGroup;

    /** @ 提及的用户 ID 列表 */
    @Singular("mentionedItem")
    private List<String> mentionedList;

    /**
     * 原始字段
     * <p>支持扩展</p>
     */
    @Singular("rawField")
    private Map<String, Object> rawFields;

    /**
     * 获取原始字段值
     *
     * @param key 字段名
     * @return 字段值，不存在则 null
     */
    public <T> T rawField(String key) {
        if (rawFields == null) {
            return null;
        }
        return (T) rawFields.get(key);
    }
}