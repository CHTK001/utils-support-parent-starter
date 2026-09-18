package com.chua.wechat.support.bot;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chua.common.support.ai.bot.BotInboundMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动回复风控守卫测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("个人微信回复风控测试")
class WechatReplyPolicyTest {

    private BotInboundMessage privateMsg(String fromUser, String content) {
        return BotInboundMessage.builder()
                .msgId(fromUser + content)
                .type(BotInboundMessage.Type.TEXT)
                .content(content)
                .fromUser(fromUser)
                .build();
    }

    @Test
    @DisplayName("默认策略放行单聊文本")
    void allowsPrivateTextByDefault() {
        assertNull(new WechatReplyPolicy().denyReason(privateMsg("wxid_a", "hi")));
    }

    @Test
    @DisplayName("群聊默认关闭")
    void groupDisabledByDefault() {
        BotInboundMessage group = BotInboundMessage.builder()
                .type(BotInboundMessage.Type.TEXT)
                .content("hi")
                .fromUser("wxid_b")
                .chatId("123@chatroom")
                .fromGroup(true)
                .build();

        assertEquals("group-disabled", new WechatReplyPolicy().denyReason(group));
        assertNull(new WechatReplyPolicy().allowGroups(true).denyReason(group));
    }

    @Test
    @DisplayName("白名单看对方也看群")
    void allowListMatchesUserOrConversation() {
        WechatReplyPolicy policy = new WechatReplyPolicy().allowGroups(true).allow("123@chatroom");
        BotInboundMessage group = BotInboundMessage.builder()
                .type(BotInboundMessage.Type.TEXT)
                .content("hi")
                .fromUser("wxid_b")
                .chatId("123@chatroom")
                .fromGroup(true)
                .build();

        assertNull(policy.denyReason(group));
        assertEquals("not-allowed", policy.denyReason(privateMsg("wxid_a", "hi")));
    }

    @Test
    @DisplayName("黑名单优先于白名单")
    void blockListWins() {
        WechatReplyPolicy policy = new WechatReplyPolicy().allow("wxid_a").block("wxid_a");

        assertEquals("blocked", policy.denyReason(privateMsg("wxid_a", "hi")));
    }

    @Test
    @DisplayName("关键词未命中不回复")
    void keywordRequired() {
        WechatReplyPolicy policy = new WechatReplyPolicy().needKeyword("帮我");

        assertEquals("no-keyword", policy.denyReason(privateMsg("wxid_a", "在吗")));
        assertNull(policy.denyReason(privateMsg("wxid_a", "帮我看看")));
    }

    @Test
    @DisplayName("单对象频控与当日总量")
    void rateLimits() {
        WechatReplyPolicy policy = new WechatReplyPolicy().maxPerUserPerMinute(1).maxPerDay(3);

        assertNull(policy.denyReason(privateMsg("wxid_a", "one")));
        policy.markReplied("wxid_a");
        assertEquals("user-rate-limit", policy.denyReason(privateMsg("wxid_a", "two")));
        assertNull(policy.denyReason(privateMsg("wxid_b", "two")));

        policy.markReplied("wxid_b");
        policy.markReplied("wxid_c");
        assertEquals("daily-limit", policy.denyReason(privateMsg("wxid_d", "four")));
    }

    @Test
    @DisplayName("人工接管优先于一切规则")
    void pauseOverridesEverything() {
        WechatReplyPolicy policy = new WechatReplyPolicy().pause();

        assertEquals("paused", policy.denyReason(privateMsg("wxid_a", "hi")));
        policy.resume();
        assertNull(policy.denyReason(privateMsg("wxid_a", "hi")));
    }

    @Test
    @DisplayName("空消息体与空内容都不回")
    void rejectsEmptyInput() {
        WechatReplyPolicy policy = new WechatReplyPolicy();

        assertEquals("no-message", policy.denyReason(null));
        assertEquals("empty-content", policy.denyReason(privateMsg("wxid_a", "  ")));
    }

    @Test
    @DisplayName("nextDelayMillis 不小于最小间隔")
    void delayRespectsInterval() {
        WechatReplyPolicy policy = new WechatReplyPolicy().minIntervalMillis(3000).jitterMillis(500);

        for (int i = 0; i < 20; i++) {
            long delay = policy.nextDelayMillis();
            assertTrue(delay >= 3000 && delay <= 3500, "delay=" + delay);
        }
    }

    @Test
    @DisplayName("split-按标点拆条且不留空段")
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

    @Test
    @DisplayName("split-短文本原样单条")
    void splitKeepsShortText() {
        assertEquals(List.of("很短"), WechatAutoReplyHandler.split("很短", 100));
    }
}
