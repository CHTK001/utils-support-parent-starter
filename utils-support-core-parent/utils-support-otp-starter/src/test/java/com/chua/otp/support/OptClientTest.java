// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.otp.support;

import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OTP 消息推送器单元测试。
 *
 * <p>覆盖 SPI 注册（{@link MessagePush#create}）、发送（call）、查询（get/list）
 * 以及过期清理三条链路。
 *
 * @author CH
 * @since 4.0.0
 */
@DisplayName("OTP 消息推送器")
class OptClientTest {

    /**
     * 固定接收人标识。
     */
    private static final String TO_USER = "otp_user_001";

    @Test
    @DisplayName("SPI 注册生效：MessagePush.create(\"opt\") 返回 OptClient")
    void spiCreateReturnsOptClient() {
        MessagePush push = MessagePush.create("opt");
        assertNotNull(push, "SPI 应解析到 opt 实现");
        assertTrue(push instanceof OptClient, "opt 的实现类应为 OptClient");
        assertEquals("opt", push.getProvider(), "提供者标识应为 opt");
    }

    @Test
    @DisplayName("接收人空白时，发送失败")
    void callWithoutToUserFails() {
        OptClient client = client();

        MessageResponse response = client.call("");
        assertFalse(response.isSuccess(), "空白接收人应标记失败");
        assertNotNull(response.getErrorMessage(), "失败原因不得为空");

        response = client.call(" ");
        assertFalse(response.isSuccess(), "空白接收人应标记失败");
    }

    @Test
    @DisplayName("发送验证码并获取到对应的记录")
    void callSavesAndRetrievesRecord() {
        OptClient client = client();

        MessageResponse response = client.call(TO_USER);
        assertTrue(response.isSuccess(), "正常发送应成功");
        assertNotNull(response.getMessageId(), "响应需带回记录标识");

        OptMessage message = client.get(TO_USER);
        assertNotNull(message, "记录应存在");
        assertEquals(TO_USER, message.toUser(), "接收人标识应一致");
        assertTrue(response.getMessageId().equals(message.id()), "响应的 messageId 应即记录标识");
        assertFalse(message.isExpired(), "新记录不应已过期");
    }

    @Test
    @DisplayName("验证码列表按发送时间倒序排列")
    void listIsOrderedNewestFirst() {
        OptClient client = client();

        client.call(TO_USER, "111111");
        client.call(TO_USER, "222222");
        client.call(TO_USER, "333333");

        java.util.List<OptMessage> list = client.list(TO_USER);
        assertEquals(3, list.size(), "应保留全部 3 条记录");
        assertEquals("333333", list.get(0).code(), "最近一条应排在最前");
        assertEquals("111111", list.get(2).code(), "最早一条应排在最后");
        assertTrue(list.get(0).content().contains(list.get(0).code()), "下发的内容应包含当前验证码");
    }

    @Test
    @DisplayName("验证码核验通过")
    void verifyPassesWithTheCode() {
        OptClient client = client();
        client.call(TO_USER, "123456");

        assertTrue(client.verify(TO_USER, "123456"), "应核验最新验证码");
        assertFalse(client.verify(TO_USER, "000000"), "错误的验证码应核验失败");
        assertFalse(client.verify(TO_USER, null), "空验证码应核验失败");
    }

    @Test
    @DisplayName("短有效时长的验证码发送后会自动过期")
    void expiryRemovesRecord() throws InterruptedException {
        MessageEnvironment environment = new MessageEnvironment().set("otp.duration", "100");
        OptClient client = new OptClient(environment);
        client.call(TO_USER);

        // 等待有效时长过期
        Thread.sleep(150);

        OptMessage message = client.get(TO_USER);
        assertNull(message, "过期后最近记录应为空");
        assertTrue(client.list(TO_USER).isEmpty(), "过期后列表应为空");
        assertFalse(client.verify(TO_USER, "111111"), "过期后不应再被核验");
    }

    @Test
    @DisplayName("从未发送过验证码的接收人返回空")
    void unknownUserReturnsEmpty() {
        OptClient client = client();

        assertNull(client.get("unknown_user"));
        assertTrue(client.list("unknown_user").isEmpty(), "未知用户的列表应为空");
    }

    /**
     * 创建使用默认环境的 OTP 推送器。
     *
     * @return OptClient
     */
    private static OptClient client() {
        return new OptClient();
    }
}
