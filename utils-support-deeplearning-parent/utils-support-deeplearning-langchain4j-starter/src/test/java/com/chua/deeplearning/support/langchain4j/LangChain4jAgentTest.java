package com.chua.deeplearning.support.langchain4j;

import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class LangChain4jAgentTest {

    @Test
    void testRunWithoutChatModel() {
        LangChain4jAgent agent = new LangChain4jAgent();
        AgentResponse response = agent.run("你好");
        assertEquals("", response.getOutput());
        assertNotNull(response.getMetadata());
        assertTrue(response.getMetadata().containsKey("error"));
    }

    @Test
    void testRunPureQa() {
        ChatModel model = new FixedChatModel("hello");
        LangChain4jAgent agent = new LangChain4jAgent();
        agent.chatModel(model);
        AgentResponse response = agent.run("test");
        assertEquals("hello", response.getOutput());
        assertEquals(AgentMode.AUTO.name(), response.getMode());
    }

    @Test
    void testRunWithToolCall() {
        String toolResultJson = "{\"weather\":\"晴天\"}";

        ChatModel model = new SequentialChatModel(Arrays.asList(
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("get_weather")
                        .arguments("{\"location\":\"北京\"}")
                        .build()),
                AiMessage.from("北京天气：晴天")
        ));

        McpManager mcpManager = new McpManager() {
            @Override public McpManager register(String name, McpClient client) { return this; }
            @Override public McpClient get(String name) { return null; }
            @Override public Map<String, McpClient> getAll() { return Map.of(); }
            @Override public List<McpToolDescriptor> listAllTools() {
                return List.of(new McpToolDescriptor("get_weather", "Get weather", Map.of()));
            }
            @Override public McpToolResult callTool(String serverName, McpToolCall toolCall) {
                return McpToolResult.success(toolResultJson);
            }
            @Override public void initAll() { }
            @Override public void closeAll() { }
        };

        LangChain4jAgent agent = new LangChain4jAgent();
        agent.chatModel(model);
        agent.mcpManager(mcpManager);
        AgentResponse response = agent.run("天气怎么样？");
        assertEquals("北京天气：晴天", response.getOutput());
    }

    @Test
    void testRunWithSkillCall() {
        ChatModel model = new SequentialChatModel(Arrays.asList(
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("translate")
                        .arguments("{\"text\":\"hello\",\"target\":\"zh\"}")
                        .build()),
                AiMessage.from("翻译结果：你好")
        ));

        SkillManager skillManager = new SkillManager() {
            @Override public SkillManager register(SkillDefinition skillDefinition) { return this; }
            @Override public SkillDefinition get(String name) { return null; }
            @Override public Map<String, SkillDefinition> getAll() { return Map.of(); }
            @Override public List<Map<String, Object>> listAllToolDescriptors() { return List.of(); }
            @Override public SkillResult execute(String name, Map<String, Object> args) {
                return SkillResult.success("你好");
            }
            @Override public boolean contains(String name) { return false; }
        };

        LangChain4jAgent agent = new LangChain4jAgent();
        agent.chatModel(model);
        agent.skillManager(skillManager);
        AgentResponse response = agent.run("翻译 hello");
        assertEquals("翻译结果：你好", response.getOutput());
    }

    @Test
    void testMaxToolIterations() {
        ChatModel model = new SequentialChatModel(Collections.nCopies(10,
                AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("tool")
                        .arguments("{}")
                        .build())));

        McpManager mcpManager = new McpManager() {
            @Override public McpManager register(String name, McpClient client) { return this; }
            @Override public McpClient get(String name) { return null; }
            @Override public Map<String, McpClient> getAll() { return Map.of(); }
            @Override public List<McpToolDescriptor> listAllTools() {
                return List.of(new McpToolDescriptor("tool", "A tool", Map.of()));
            }
            @Override public McpToolResult callTool(String serverName, McpToolCall toolCall) {
                return McpToolResult.success("result");
            }
            @Override public void initAll() { }
            @Override public void closeAll() { }
        };

        LangChain4jAgent agent = new LangChain4jAgent();
        agent.chatModel(model);
        agent.mcpManager(mcpManager);
        agent.maxToolIterations(3);
        AgentResponse response = agent.run("循环测试");
        assertEquals("", response.getOutput());
    }

    private static class FixedChatModel implements ChatModel {
        /** 文本内容 */
        /** 文本 */
        private final String text;

        FixedChatModel(String text) {
            this.text = text;
        }

        @Override
        /** Chat */
        public ChatResponse chat(ChatRequest chatRequest) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .tokenUsage(new TokenUsage())
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }

    private static class SequentialChatModel implements ChatModel {
        /** 消息列表 */
        /** Messages */
        private final List<AiMessage> messages;
        /** 索引 */
        private int index = 0;

        SequentialChatModel(List<AiMessage> messages) {
            this.messages = messages;
        }

        @Override
        /** Chat */
        public ChatResponse chat(ChatRequest chatRequest) {
            if (index >= messages.size()) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(""))
                        .tokenUsage(new TokenUsage())
                        .finishReason(FinishReason.STOP)
                        .build();
            }
            AiMessage msg = messages.get(index++);
            return ChatResponse.builder()
                    .aiMessage(msg)
                    .tokenUsage(new TokenUsage())
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }
}


