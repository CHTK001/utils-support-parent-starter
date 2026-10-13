package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.chat.ChatMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompressionAwareModel} 消息转换的回归测试。
 *
 * <p>锁定的是一条曾被静默破坏的契约：Harness 把 {@code sysPrompt} 作为 SYSTEM 消息
 * 放进 messages，而压缩路径的消息转换<b>必须</b>保留它。此前该转换显式跳过 SYSTEM，
 * 导致无工具轮次下 agent 的人设与约束被丢弃——且因为走 delegate 的路径不受影响，
 * 表现为「时好时坏」，极难定位。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class CompressionAwareModelTest {

    /**
     * 构造一条指定角色的文本消息。
     *
     * @param role 角色
     * @param text 文本
     * @return AgentScope 消息
     */
    private static Msg msg(MsgRole role, String text) {
        return Msg.builder()
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Test
    @DisplayName("system 消息不得在压缩路径被丢弃")
    void keepsSystemMessage() {
        List<ChatMessage> converted = CompressionAwareModel.convertToChatMessages(List.of(
                msg(MsgRole.SYSTEM, "你是严谨的助手，禁止臆测。"),
                msg(MsgRole.USER, "帮我看看这段代码")));

        assertEquals(2, converted.size(), "system 与 user 都应保留");
        assertEquals("system", converted.getFirst().getRole());
        assertEquals("你是严谨的助手，禁止臆测。", converted.getFirst().getContent());
        assertEquals("user", converted.get(1).getRole());
    }

    @Test
    @DisplayName("回程把 system 角色还原为 SYSTEM 而非降级为 user")
    void restoresSystemRole() {
        List<Msg> restored = CompressionAwareModel.convertToMsg(List.of(
                ChatMessage.builder().role("system").content("人设").build(),
                ChatMessage.builder().role("user").content("问题").build(),
                ChatMessage.builder().role("assistant").content("回答").build()));

        assertEquals(3, restored.size());
        assertEquals(MsgRole.SYSTEM, restored.get(0).getRole(), "system 必须还原为 SYSTEM");
        assertEquals(MsgRole.USER, restored.get(1).getRole());
        assertEquals(MsgRole.ASSISTANT, restored.get(2).getRole());
    }

    @Test
    @DisplayName("system 消息往返转换后内容与角色均不丢失")
    void systemSurvivesRoundTrip() {
        String prompt = "只能使用中文回答。";
        List<Msg> original = List.of(msg(MsgRole.SYSTEM, prompt));

        List<Msg> roundTripped = CompressionAwareModel.convertToMsg(
                CompressionAwareModel.convertToChatMessages(original));

        assertEquals(1, roundTripped.size(), "往返后 system 消息不应消失");
        assertEquals(MsgRole.SYSTEM, roundTripped.getFirst().getRole());
        assertEquals(prompt, roundTripped.getFirst().getTextContent());
    }

    @Test
    @DisplayName("空文本消息被跳过，不产生空壳轮次")
    void skipsBlankMessages() {
        List<ChatMessage> converted = CompressionAwareModel.convertToChatMessages(List.of(
                msg(MsgRole.SYSTEM, "  "),
                msg(MsgRole.USER, "有效内容")));

        assertEquals(1, converted.size());
        assertEquals("有效内容", converted.getFirst().getContent());
    }

    @Test
    @DisplayName("空列表与 null 输入均返回空列表")
    void handlesEmptyInput() {
        assertTrue(CompressionAwareModel.convertToChatMessages(List.of()).isEmpty());
        assertTrue(CompressionAwareModel.convertToMsg(List.of()).isEmpty());
    }

    @Test
    @DisplayName("压缩路径产出的消息不含 tool 相关块（工具轮次走 delegate）")
    void compressedMessagesArePlainText() {
        List<Msg> restored = CompressionAwareModel.convertToMsg(List.of(
                ChatMessage.builder().role("user").content("问题").build()));

        assertFalse(restored.getFirst().hasContentBlocks(
                        io.agentscope.core.message.ToolUseBlock.class),
                "压缩以纯文本轮次建模，不应引入工具块");
        assertFalse(restored.getFirst().hasContentBlocks(
                        io.agentscope.core.message.ToolResultBlock.class),
                "压缩以纯文本轮次建模，不应引入工具结果块");
    }
}
