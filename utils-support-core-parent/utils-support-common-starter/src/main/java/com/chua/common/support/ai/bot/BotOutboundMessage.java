package com.chua.common.support.ai.bot;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

/**
* Bot 出站消息
* <p>
* 封装要发送给 Bot 平台的消息，支持按类型构建文本、图片、语音等。
* </p>
* <pre>{@code
* // 快捷构造文本消息
* BotOutboundMessage msg = BotOutboundMessage.text("openid_xxx", "你好");
*
* // 构造群组 @ 提及消息
* BotOutboundMessage groupMsg = BotOutboundMessage.groupTextMention(
*     "groupid_xxx", "通知内容", List.of("user1", "user2"));
* }</pre>
*
* @author CH
* @since 2026/07/18
 */
@Data
@Builder
@SuppressWarnings("unchecked")
public class BotOutboundMessage {

    /**
    * 创建文本消息
    *
    * @param toUser  目标用户 ID
    * @param content 消息内容
    * @return 出站消息
     */
    public static BotOutboundMessage text(String toUser, String content) {
        return BotOutboundMessage.builder()
                .toUser(toUser)
                .type(BotInboundMessage.Type.TEXT)
                .content(content)
                .build();
    }

    /**
    * 创建群组文本消息
    *
    * @param groupId 群组 ID
    * @param content 消息内容
    * @return 出站消息
     */
    public static BotOutboundMessage groupText(String groupId, String content) {
        return BotOutboundMessage.builder()
                .toUser(groupId)
                .type(BotInboundMessage.Type.TEXT)
                .content(content)
                .toGroup(true)
                .build();
    }

    /**
    * 创建群组 @ 提及消息
    *
    * @param groupId      群组 ID
    * @param content      消息内容
    * @param mentionedUsers @ 提及的用户 ID 列表
    * @return 出站消息
     */
    public static BotOutboundMessage groupTextMention(
            String groupId,
            String content,
            List<String> mentionedUsers) {
        return BotOutboundMessage.builder()
                .toUser(groupId)
                .type(BotInboundMessage.Type.TEXT)
                .content(content)
                .toGroup(true)
                .mentionedUsers(mentionedUsers)
                .build();
    }

    /**
    * 创建图片消息
    *
    * @param toUser    目标用户 ID
    * @param mediaPath 图片本地路径
    * @return 出站消息
     */
    public static BotOutboundMessage image(String toUser, String mediaPath) {
        return BotOutboundMessage.builder()
                .toUser(toUser)
                .type(BotInboundMessage.Type.IMAGE)
                .mediaPath(mediaPath)
                .build();
    }

    /**
    * 创建语音消息
    *
    * @param toUser    目标用户 ID
    * @param mediaPath 语音文件路径
    * @return 出站消息
     */
    public static BotOutboundMessage voice(String toUser, String mediaPath) {
        return BotOutboundMessage.builder()
                .toUser(toUser)
                .type(BotInboundMessage.Type.VOICE)
                .mediaPath(mediaPath)
                .build();
    }

    /** 消息类型 */
    /**
    * 类型
     */
    private BotInboundMessage.Type type;

    /** 目标用户 ID */
    private String toUser;

    /**
    * 消息内容
    * <p>TEXT 类型时包含文本</p>
     */
    private String content;

    /**
    * 媒体文件路径
    * <p>IMAGE/VOICE/VIDEO/FILE 类型时</p>
     */
    private String mediaPath;

    /** 视频标题 */
    private String title;

    /** 视频描述 */
    /**
    * 描述
     */
    private String description;

    /**
    * 是否发送到群组
    * <p>true 时 {@code toUser} 为群组 ID</p>
     */
    private boolean toGroup;

    /** @ 提及的用户 ID 列表 */
    private List<String> mentionedUsers;

    /**
    * 扩展字段
    * <p>如 "markdown" 等自定义参数</p>
     */
    @Singular("extension")
    private Map<String, Object> extensions;

    /**
    * 获取扩展字段值
    *
    * @param key 字段名
    * @return 字段值
     */
    public <T> T extension(String key) {
        if (extensions == null) {
            return null;
        }
        return (T) extensions.get(key);
    }
}