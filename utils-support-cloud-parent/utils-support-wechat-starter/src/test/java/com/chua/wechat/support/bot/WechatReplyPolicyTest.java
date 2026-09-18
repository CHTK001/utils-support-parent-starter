package com.chua.wechat.support.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.wechat.support.bot.WechatReplyPolicy.DenyReason;

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

    /**
    * 构造一条单聊文本消息。
    *
    * @param fromUser 对方 wxid
    * @param content 消息正文
    * @return 单聊入站消息
    */
    private BotInboundMessage privateMsg(String fromUser, String content) {
        return BotInboundMessage.builder()
                .type(BotInboundMessage.Type.TEXT)
                .content(content)
                .fromUser(fromUser)
                .build();
    }

    /**
    * 构造一条群聊文本消息。
    *
    * @param roomWxid 群 wxid
    * @param senderWxid 群内发言人 wxid
    * @return 群聊入站消息
    */
    private BotInboundMessage groupMsg(String roomWxid, String senderWxid) {
        return BotInboundMessage.builder()
                .type(BotInboundMessage.Type.TEXT)
                .content("hi")
                .fromUser(senderWxid)
                .chatId(roomWxid)
                .fromGroup(true)
                .build();
    }

    /**
    * 测试：默认策略放行单聊文本消息。
    */
    @Test
    @DisplayName("默认策略放行单聊文本")
    void allowsPrivateTextByDefault() {
        assertNull(new WechatReplyPolicy().denyReason(privateMsg("wxid_a", "hi")));
    }

    /**
    * 测试：群聊默认关闭，显式开启后放行。
    */
    @Test
    @DisplayName("群聊默认关闭")
    void groupDisabledByDefault() {
        BotInboundMessage group = groupMsg("123@chatroom", "wxid_b");

        assertEquals(DenyReason.GROUP_DISABLED, new WechatReplyPolicy().denyReason(group));
        assertNull(new WechatReplyPolicy().allowGroups(true).denyReason(group));
    }

    /**
    * 测试：白名单同时匹配发言人 wxid 与会话（群）wxid。
    */
    @Test
    @DisplayName("白名单看对方也看群")
    void allowListMatchesUserOrConversation() {
        WechatReplyPolicy policy = new WechatReplyPolicy().allowGroups(true).allow("123@chatroom");

        assertNull(policy.denyReason(groupMsg("123@chatroom", "wxid_b")));
        assertEquals(DenyReason.NOT_ALLOWED, policy.denyReason(privateMsg("wxid_a", "hi")));
    }

    /**
    * 测试：黑名单优先级高于白名单。
    */
    @Test
    @DisplayName("黑名单优先于白名单")
    void blockListWins() {
        WechatReplyPolicy policy = new WechatReplyPolicy().allow("wxid_a").block("wxid_a");

        assertEquals(DenyReason.BLOCKED, policy.denyReason(privateMsg("wxid_a", "hi")));
    }

    /**
    * 测试：配置必需关键词后，未命中的消息不回复。
    */
    @Test
    @DisplayName("关键词未命中不回复")
    void keywordRequired() {
        WechatReplyPolicy policy = new WechatReplyPolicy().needKeyword("帮我");

        assertEquals(DenyReason.NO_KEYWORD, policy.denyReason(privateMsg("wxid_a", "在吗")));
        assertNull(policy.denyReason(privateMsg("wxid_a", "帮我看看")));
    }

    /**
    * 测试：单对象每分钟频控与当日回复总量限制。
    */
    @Test
    @DisplayName("单对象频控与当日总量")
    void rateLimits() {
        WechatReplyPolicy policy = new WechatReplyPolicy().maxPerUserPerMinute(1).maxPerDay(3);

        assertNull(policy.denyReason(privateMsg("wxid_a", "one")));
        policy.markReplied("wxid_a");
        assertEquals(DenyReason.USER_RATE_LIMIT, policy.denyReason(privateMsg("wxid_a", "two")));
        assertNull(policy.denyReason(privateMsg("wxid_b", "two")));

        policy.markReplied("wxid_b");
        policy.markReplied("wxid_c");
        assertEquals(DenyReason.DAILY_LIMIT, policy.denyReason(privateMsg("wxid_d", "four")));
    }

    /**
    * 测试：人工接管（暂停）优先于一切放行规则，恢复后重新放行。
    */
    @Test
    @DisplayName("人工接管优先于一切规则")
    void pauseOverridesEverything() {
        WechatReplyPolicy policy = new WechatReplyPolicy().pause();

        assertEquals(DenyReason.PAUSED, policy.denyReason(privateMsg("wxid_a", "hi")));
        policy.resume();
        assertNull(policy.denyReason(privateMsg("wxid_a", "hi")));
    }

    /**
    * 测试：空消息体与空白内容均被拒绝。
    */
    @Test
    @DisplayName("空消息体与空内容都不回")
    void rejectsEmptyInput() {
        WechatReplyPolicy policy = new WechatReplyPolicy();

        assertEquals(DenyReason.NO_MESSAGE, policy.denyReason(null));
        assertEquals(DenyReason.EMPTY_CONTENT, policy.denyReason(privateMsg("wxid_a", "  ")));
    }

    /**
    * 测试：回复延迟不小于最小间隔且不超过最小间隔加抖动上限。
    */
    @Test
    @DisplayName("回复延迟不小于最小间隔且不超过抖动上限")
    void delayRespectsInterval() {
        WechatReplyPolicy policy = new WechatReplyPolicy().minIntervalMillis(3000).jitterMillis(500);

        for (int i = 0; i < 20; i++) {
            long delay = policy.nextDelayMillis();
            assertTrue(delay >= 3000 && delay <= 3500, "delay=" + delay);
        }
    }
}
