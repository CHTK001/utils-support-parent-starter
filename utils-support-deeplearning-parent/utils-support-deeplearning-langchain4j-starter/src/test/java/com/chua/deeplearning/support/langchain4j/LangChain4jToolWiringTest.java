package com.chua.deeplearning.support.langchain4j;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LangChain4jAgent} 进程内工具面测试。
 *
 * <p>用假模型驱动完整的 ReAct 循环，验证三件事：注册的工具真的被下发给模型、
 * 执行结果真的回灌给模型、{@code disableTool} 真的让工具从下发列表里消失且不再执行。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class LangChain4jToolWiringTest {

    /**
     * 构造回显工具。
     *
     * @param calls 调用计数
     * @return 工具定义
     */
    private static AgentTool echoTool(AtomicInteger calls) {
        return AgentTool.builder()
                .name("echo")
                .description("回显输入文本")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")))
                .executor(args -> {
                    calls.incrementAndGet();
                    return "ok:" + args.get("text");
                })
                .build();
    }

    @Test
    void registeredToolIsOfferedAndItsResultIsFedBack() {
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
        try {
            FakeChatModel model = new FakeChatModel();
            AtomicInteger calls = new AtomicInteger();
            agent.chatModel(model).maxToolIterations(3);
            agent.tool(echoTool(calls));

            String output = agent.run("hi").getOutput();

            assertEquals(1, calls.get(), "工具执行器应被调用一次");
            assertEquals("final:ok:x", output, "工具结果应回灌给模型");
            assertTrue(model.offeredSpecs.contains(1), "首次调用应下发 1 个工具，实际: " + model.offeredSpecs);
        } finally {
            agent.close();
        }
    }

    @Test
    void disabledToolIsNeitherOfferedNorExecuted() {
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
        try {
            FakeChatModel model = new FakeChatModel();
            AtomicInteger calls = new AtomicInteger();
            agent.chatModel(model);
            agent.tool(echoTool(calls)).disableTool("echo");

            String output = agent.run("hi").getOutput();

            assertEquals(0, calls.get(), "被关闭的工具不应执行");
            assertEquals("final:none", output);
            assertTrue(model.offeredSpecs.stream().allMatch(n -> n == 0),
                    "被关闭的工具不应出现在工具列表，实际: " + model.offeredSpecs);
        } finally {
            agent.close();
        }
    }

    @Test
    void toolsExposesRegisteredDefinitionsAndBatchRegistrationWorks() {        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
        try {
            AtomicInteger calls = new AtomicInteger();
            AgentTool echo = echoTool(calls);
            AgentTool ping = AgentTool.builder().name("ping").description("探活").executor(args -> "pong").build();

            assertTrue(agent.tools().isEmpty());
            agent.tools(List.of(echo, ping));

            assertEquals(2, agent.tools().size());
            assertEquals("echo", agent.tools().get(0).getName());
            assertEquals("ping", agent.tools().get(1).getName());
            assertFalse(agent.tools().isEmpty());
        } finally {
            agent.close();
        }
    }

    /**
     * leader 的系统提示词必须把运行期追加的子 Agent 补进路由表，
     * 且与 {@code runStream} 真正下发的提示词一致。
     */
    @Test
    void systemPromptIncludesRuntimeSubAgentRouting() {
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
        try {
            agent.definition(AgentDefinition.builder()
                    .id("leader")
                    .instruction("你是主编排 agent")
                    .leader(true)
                    .build());
            agent.subAgent(AgentDefinition.builder()
                    .id("writer")
                    .name("写手")
                    .description("负责写长文")
                    .instruction("只写文章")
                    .build());

            String prompt = agent.getSystemPrompt();

            assertNotNull(prompt);
            assertTrue(prompt.contains("你是主编排 agent"), "应保留主 Agent 指令: " + prompt);
            assertTrue(prompt.contains("writer"), "leader 的提示词应含运行期子 Agent 路由表: " + prompt);
        } finally {
            agent.close();
        }
    }

    /**
     * 非 leader 不应凭空拼路由表，直接使用 definition 的提示词。
     */
    @Test
    void systemPromptEqualsDefinitionPromptForSingleAgent() {
        LangChain4jAgent agent = (LangChain4jAgent) Agent.create("langchain4j");
        try {
            agent.definition(AgentDefinition.builder()
                    .id("solo")
                    .instruction("只做一件事")
                    .build());

            assertEquals("只做一件事", agent.getSystemPrompt());
        } finally {
            agent.close();
        }
    }

    /**
     * 假模型：第一次看到工具时发起 echo 调用，之后把回灌的工具结果包进最终回答。
     */
    private static final class FakeChatModel implements ChatModel {

        /**
         * 每次请求下发的工具数量
         */
        private final List<Integer> offeredSpecs = new ArrayList<>();

        @Override
        public ChatResponse chat(ChatRequest request) {
            List<dev.langchain4j.agent.tool.ToolSpecification> specs = request.toolSpecifications();
            int size = specs == null ? 0 : specs.size();
            offeredSpecs.add(size);
            if (size > 0 && lastToolResult(request) == null) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("echo")
                                .arguments("{\"text\":\"x\"}")
                                .build()))
                        .build();
            }
            String toolResult = lastToolResult(request);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(toolResult == null ? "final:none" : "final:" + toolResult))
                    .build();
        }

        /**
         * 取最后一条工具执行结果。
         *
         * @param request 模型请求
         * @return 工具结果文本；没有时返回 null
         */
        private static String lastToolResult(ChatRequest request) {
            String text = null;
            for (ChatMessage message : request.messages()) {
                if (message instanceof ToolExecutionResultMessage result) {
                    text = result.text();
                }
            }
            return text;
        }
    }
}
