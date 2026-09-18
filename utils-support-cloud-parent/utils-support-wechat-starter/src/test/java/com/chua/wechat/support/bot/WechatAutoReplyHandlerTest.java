package com.chua.wechat.support.bot;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.wechat.support.bot.WechatReplyPolicy.DenyReason;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
* 自动回复处理器的准入路径测试。
*
* <p>只验证被风控挡下的消息不会走到模型与发送：依赖传 {@code null}，一旦误入回复
* 分支就会抛空指针。真实发送链路需要本机 Hook 服务，无法在此覆盖。</p>
*
* @author CH
* @since 4.0.0.42
*/
@DisplayName("个人微信自动回复处理器测试")
class WechatAutoReplyHandlerTest {

    /**
    * 构造一条指定类型的入站消息。
    *
    * @param type 消息类型
    * @param content 消息正文
    * @return 入站消息
    */
    private BotInboundMessage message(BotInboundMessage.Type type, String content) {
        return BotInboundMessage.builder()
                .msgId("m-" + System.nanoTime())
                .type(type)
                .content(content)
                .fromUser("wxid_a")
                .build();
    }

    /**
    * 测试：被风控拒绝的消息不会触达模型调用与发送链路。
    */
    @Test
    @DisplayName("被拒绝的消息不触达模型与发送")
    void deniedMessageNeverReplies() {
        WechatReplyPolicy policy = new WechatReplyPolicy().pause();
        WechatAutoReplyHandler handler = new WechatAutoReplyHandler(null, null, policy);

        assertDoesNotThrow(() -> handler.onMessage(
                message(BotInboundMessage.Type.TEXT, "hi")));
    }

    /**
    * 测试：非文本消息（如图片）被直接忽略，不进入回复分支。
    */
    @Test
    @DisplayName("非文本消息直接忽略")
    void ignoresNonText() {
        WechatAutoReplyHandler handler = new WechatAutoReplyHandler(null, null,
                new WechatReplyPolicy());

        assertDoesNotThrow(() -> handler.onMessage(
                message(BotInboundMessage.Type.IMAGE, "")));
    }

    /**
    * 测试：close 可重复调用且及时返回，不阻塞调用线程。
    */
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

    /**
    * 测试：接入监听器后，入站解析与拒绝规则串联生效。
    */
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
        assertEquals(DenyReason.GROUP_DISABLED, new WechatReplyPolicy().denyReason(accepted.get(0)));
    }

    /**
    * 测试：长回复按标点拆分为多条且不产生空段。
    */
    @Test
    @DisplayName("长回复按标点拆条且不留空段")
    void splitLongReply() {
        String text = "第一句话。\n".repeat(40);

        List<String> parts = WechatAutoReplyHandler.split(text, 50);

        assertTrue(parts.size() > 1);
        for (String part : parts) {
            assertTrue(part.length() <= 50, "part=" + part);
            assertTrue(!part.isBlank());
        }
        assertEquals(text.replaceAll("\\s", ""), String.join("", parts).replaceAll("\\s", ""));
    }

    /**
    * 测试：短文本原样以单条返回。
    */
    @Test
    @DisplayName("短文本原样单条返回")
    void splitKeepsShortText() {
        assertEquals(List.of("很短"), WechatAutoReplyHandler.split("很短", 100));
    }
}
