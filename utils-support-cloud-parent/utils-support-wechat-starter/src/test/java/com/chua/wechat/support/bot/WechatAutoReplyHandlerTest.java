package com.chua.wechat.support.bot;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chua.common.support.ai.bot.BotInboundMessage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动回复处理器的准入路径测试。
 *
 * <p>只验证被风控挡下的消息不会走到模型与发送：依赖传 {@code null}，一旦误入回复
 * 分支就会抛空指针。真实发送链路需要 hook bridge，无法在此覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("个人微信自动回复处理器测试")
class WechatAutoReplyHandlerTest {

    private BotInboundMessage message(BotInboundMessage.Type type, String content) {
        return BotInboundMessage.builder()
                .msgId("m-" + System.nanoTime())
                .type(type)
                .content(content)
                .fromUser("wxid_a")
                .build();
    }

    @Test
    @DisplayName("被拒绝的消息不触达模型与发送")
    void deniedMessageNeverReplies() {
        WechatReplyPolicy policy = new WechatReplyPolicy().pause();
        WechatAutoReplyHandler handler = new WechatAutoReplyHandler(null, null, policy);

        assertDoesNotThrow(() -> handler.onMessage(
                message(BotInboundMessage.Type.TEXT, "hi")));
    }

    @Test
    @DisplayName("非文本消息直接忽略")
    void ignoresNonText() {
        WechatAutoReplyHandler handler = new WechatAutoReplyHandler(null, null,
                new WechatReplyPolicy());

        assertDoesNotThrow(() -> handler.onMessage(
                message(BotInboundMessage.Type.IMAGE, "")));
    }

    @Test
    @DisplayName("close 可重复调用且及时返回")
    void closeIsResponsive() {
        WechatAutoReplyHandler handler = new WechatAutoReplyHandler(null, null,
                new WechatReplyPolicy());

        long start = System.currentTimeMillis();
        handler.close();
        handler.close();

        assertTrue(System.currentTimeMillis() - start < 6_000L);
    }

    @Test
    @DisplayName("接入监听器后入站解析与拒绝规则串联生效")
    void listenerSeesDeniedGroupMessage() {
        WechatPersonalBotClient client = new WechatPersonalBotClient();
        client.selfWxid("wxid_self");
        client.addMessageListener(new WechatAutoReplyHandler(client, null,
                new WechatReplyPolicy()));

        List<BotInboundMessage> accepted = client.deliver(
                "{\"type\":1,\"msg\":\"hi\",\"talker\":\"123@chatroom\","
                        + "\"sender\":\"wxid_a\",\"roomWxid\":\"123@chatroom\","
                        + "\"msgId\":\"9001\"}");

        assertEquals(1, accepted.size());
        assertEquals("group-disabled", new WechatReplyPolicy().denyReason(accepted.get(0)));
    }
}
