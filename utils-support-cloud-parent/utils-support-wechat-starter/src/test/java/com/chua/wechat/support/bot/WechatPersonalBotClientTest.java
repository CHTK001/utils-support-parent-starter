package com.chua.wechat.support.bot;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.BotInboundMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
* 个人微信机器人入站映射测试。
*
* <p>只覆盖 {@code deliver()} 的纯解析逻辑，不触碰 Hook 服务。</p>
*
* @author CH
* @since 4.0.0.42
*/
@DisplayName("个人微信机器人客户端测试")
class WechatPersonalBotClientTest {

    private static final String SELF = "wxid_self";

    /**
    * 构造已设置自身 wxid 并挂载消息收集监听器的真实客户端。
    *
    * @param sink 收集入站消息的列表，不允许为 null
    * @return 真实客户端实例
    */
    private WechatPersonalBotClient newClient(List<BotInboundMessage> sink) {
        WechatPersonalBotClient client = new WechatPersonalBotClient();
        client.selfWxid(SELF);
        client.addMessageListener(sink::add);
        return client;
    }

    /**
    * 测试：deliver 解析单聊文本消息并正确映射字段。
    */
    @Test
    @DisplayName("deliver-单聊文本映射")
    void deliverPrivateText() {
        List<BotInboundMessage> sink = new java.util.ArrayList<>();
        String body = "{\"type\":1,\"msg\":\"hello\",\"talker\":\"wxid_a\","
                + "\"senderName\":\"A\",\"msgId\":\"1001\",\"timestamp\":1700000000}";

        List<BotInboundMessage> accepted = newClient(sink).deliver(body);

        assertEquals(1, accepted.size());
        BotInboundMessage msg = accepted.get(0);
        assertEquals("wxid_a", msg.getFromUser());
        assertEquals("A", msg.getFromUserName());
        assertEquals("hello", msg.getContent());
        assertEquals(BotInboundMessage.Type.TEXT, msg.getType());
        assertFalse(msg.isFromGroup());
        assertNull(msg.getChatId());
        // 秒级时间戳归一化为毫秒
        assertEquals(1700000000_000L, msg.getCreateTime());
        assertEquals(1, sink.size());
    }

    /**
    * 测试：deliver 处理群聊消息时取真实发言人并保留群标识。
    */
    @Test
    @DisplayName("deliver-群聊取真实发言人并保留群标识")
    void deliverGroupMessage() {
        List<BotInboundMessage> sink = new java.util.ArrayList<>();
        String body = "{\"type\":1,\"msg\":\"hi\",\"talker\":\"12345@chatroom\","
                + "\"sender\":\"wxid_b\",\"roomWxid\":\"12345@chatroom\",\"msgId\":\"1002\"}";

        BotInboundMessage msg = newClient(sink).deliver(body).get(0);

        assertTrue(msg.isFromGroup());
        assertEquals("12345@chatroom", msg.getChatId());
        assertEquals("wxid_b", msg.getFromUser());
    }

    /**
    * 测试：deliver 过滤自发消息与重复 msgId 的消息。
    */
    @Test
    @DisplayName("deliver-过滤自发消息与重复消息")
    void deliverSkipsSelfAndDuplicate() {
        List<BotInboundMessage> sink = new java.util.ArrayList<>();
        WechatPersonalBotClient client = newClient(sink);
        String first = "{\"type\":1,\"msg\":\"hello\",\"talker\":\"wxid_a\",\"msgId\":\"1001\"}";
        String self = "{\"type\":1,\"msg\":\"mine\",\"talker\":\"" + SELF + "\",\"msgId\":\"1003\"}";

        assertEquals(1, client.deliver(first).size());
        assertTrue(client.deliver(first).isEmpty(), "同一 msgId 重推应被去重");
        assertTrue(client.deliver(self).isEmpty(), "自己发出的消息不应触发监听");
        assertEquals(1, sink.size());
    }

    /**
    * 测试：deliver 对数组回调体逐条解析为入站消息。
    */
    @Test
    @DisplayName("deliver-数组回调体逐条解析")
    void deliverArrayPayload() {
        List<BotInboundMessage> sink = new java.util.ArrayList<>();
        String body = "[{\"type\":1,\"msg\":\"a\",\"talker\":\"wxid_a\",\"msgId\":\"2001\"},"
                + "{\"type\":3,\"msg\":\"\",\"talker\":\"wxid_a\",\"msgId\":\"2002\"}]";

        List<BotInboundMessage> accepted = newClient(sink).deliver(body);

        assertEquals(2, accepted.size());
        assertEquals(BotInboundMessage.Type.IMAGE, accepted.get(1).getType());
    }

    /**
    * 测试：deliver 对无法识别的消息体返回空列表。
    */
    @Test
    @DisplayName("deliver-无法识别的消息体返回空")
    void deliverIgnoresUnparsable() {
        List<BotInboundMessage> sink = new java.util.ArrayList<>();
        WechatPersonalBotClient client = newClient(sink);

        assertTrue(client.deliver("").isEmpty());
        assertTrue(client.deliver("{}").isEmpty());
        assertTrue(sink.isEmpty());
    }

    /**
    * 测试：SPI 注册可按平台名 wechat-personal 解析到实现。
    */
    @Test
    @DisplayName("SPI-按平台名 wechat-personal 可加载")
    void spiRegistrationResolves() {
        assertTrue(BotClient.auto("wechat-personal") instanceof WechatPersonalBotClient);
        assertTrue(BotClient.builder("wechat-personal").build() instanceof WechatPersonalBotClient);
    }
}
