package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.agent.ImageDefinition;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.image.ImageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class AgentScopeAgentTest {

    /** 原始输出流 */
    /** OriginalOUT */
    private PrintStream originalOut;
    /** 捕获输出流 */
    /** CaptureOUT */
    private ByteArrayOutputStream captureOut;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        captureOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captureOut));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    /** Captured */
    private String captured() {
        return captureOut.toString();
    }

    /** MathAgent */
    private static AgentDefinition mathAgent() {
        return AgentDefinition.builder()
                .id("math-agent")
                .name("数学助手")
                .description("数学计算专家")
                .instruction("你是一个数学助手。")
                .role("math_expert")
                .build();
    }

    /** ImageAgent */
    private static AgentDefinition imageAgent() {
        AgentDefinition base = AgentDefinition.builder()
                .id("image-gen")
                .name("绘图助手")
                .description("根据文本生成图片")
                .instruction("你是一个图像生成专家。")
                .role("image_generator")
                .build();
        return ImageDefinition.wrap(base, "mock-image-model", new MockImageClient());
    }

    /** MockChatClient */
    private static ChatClient mockChatClient() {
        return new MockChatClient();
    }

    private static class MockChatClient implements ChatClient {
        @Override
        /** ChatSync */
        public String chatSync(String prompt) {
            return "[Mock] 收到: " + prompt;
        }

        @Override
        /** Chat */
        public void chat(String prompt, Consumer<ChatResponse> consumer) {
            chat(prompt, consumer, () -> {}, e -> {});
        }

        @Override
        /**
         * 对话
         * @param prompt prompt
         * @param consumer consumer
         * @param onComplete onComplete
         * @param onError onError
         */
        public void chat(String prompt, Consumer<ChatResponse> consumer,
                         Runnable onComplete, Consumer<Throwable> onError) {
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());
            String out = chatSync(prompt);
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.STREAMING).content(out).build());
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.STOP).build());
            onComplete.run();
        }

        @Override
        /** Models */
        public List<com.chua.common.support.ai.chat.ModelDefinition> models() {
            return List.of();
        }

        @Override
        /** 关闭 */
        public void close() {
        }

        @Override
        /** ToString */
        public String toString() {
            return "MockChatClient";
        }
    }

    @Test
    void testPrintConfigTrue_printsArchitecture() {
        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(mockChatClient())
                    .subAgent(mathAgent())
                    .definition(AgentDefinition.builder()
                            .id("main")
                            .name("主 Agent")
                            .description("测试用主 Agent")
                            .role("main")
                            .instruction("测试")
                            .leader(true)
                            .subAgents(List.of(mathAgent()))
                            .build())
                    .printConfig(true)
                    .debug(false)
                    .mode(AgentMode.ROUTER);

            agent.run("1+1");
        }

        String output = captured();
        assertTrue(output.contains("Agent Architecture Diagram"),
                "架构图应包含 'Agent Architecture Diagram'");
        assertTrue(output.contains("Main Agent"),
                "架构图应包含 'Main Agent'");
        assertTrue(output.contains("MockChatClient"),
                "架构图应包含 MockChatClient");
        assertTrue(output.contains("math-agent"),
                "架构图应包含 math-agent");
    }

    @Test
    void testDebugTrue_printsSystemPromptsTree() {
        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(mockChatClient())
                    .subAgent(mathAgent())
                    .definition(AgentDefinition.builder()
                            .id("main")
                            .name("主 Agent")
                            .description("测试用主 Agent")
                            .role("main")
                            .instruction("测试主 Agent")
                            .leader(true)
                            .subAgents(List.of(mathAgent()))
                            .build())
                    .printConfig(false)
                    .debug(true)
                    .mode(AgentMode.ROUTER);

            agent.run("1+1");
        }

        String output = captured();
        assertTrue(output.contains("System Prompts Tree"),
                "系统提示词树应包含 'System Prompts Tree'");
        assertTrue(output.contains("Main Agent"),
                "系统提示词树应包含 'Main Agent'");
        assertTrue(output.contains("数学助手"),
                "系统提示词树应包含子 Agent 名称 '数学助手'");
        assertTrue(output.contains("可用子 Agent"),
                "主 Agent 系统提示词应包含路由表前缀 '可用子 Agent'");
    }

    @Test
    void testImageDefinition_subAgent_registersModel() {
        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(mockChatClient())
                    .subAgent(imageAgent())
                    .definition(AgentDefinition.builder()
                            .id("main")
                            .name("主 Agent")
                            .description("测试主 Agent")
                            .role("main")
                            .instruction("测试")
                            .leader(true)
                            .subAgents(List.of(imageAgent()))
                            .build())
                    .printConfig(true)
                    .debug(false)
                    .mode(AgentMode.ROUTER);

            AgentResponse response = agent.run("画一只猫");
            assertNotNull(response, "响应不应为 null");
            assertNotNull(response.getOutput(), "输出不应为 null");
            assertTrue(response.getOutput().startsWith("[Mock]"),
                    "默认应走 MockChatClient 返回");
        }

        String output = captured();
        assertTrue(output.contains("ImageGenerationModel(mock-image-model)"),
                "架构图应显示 ImageGenerationModel(mock-image-model)");
    }

    @Test
    void testRouterMode_basicExecution() {
        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(mockChatClient())
                    .subAgent(mathAgent())
                    .definition(AgentDefinition.builder()
                            .id("main")
                            .name("主 Agent")
                            .description("主 Agent")
                            .role("main")
                            .instruction("你是主 Agent。")
                            .leader(true)
                            .subAgents(List.of(mathAgent()))
                            .build())
                    .printConfig(false)
                    .debug(false)
                    .mode(AgentMode.ROUTER);

            AgentResponse response = agent.run("请计算 2+3");
            assertNotNull(response, "Agent 执行后响应不应为 null");
            assertNotNull(response.getOutput(), "响应输出不应为 null");
            assertTrue(response.getOutput().contains("[Mock]"),
                    "响应应包含 MockChatClient 输出");
            }
        }

        @Test
        void testLeaderFalse_noRoutingTable() {
            try (Agent agent = Agent.create("agentscope")) {
                agent.chatClient(mockChatClient())
                        .subAgent(mathAgent())
                        .definition(AgentDefinition.builder()
                                .id("main")
                                .name("主 Agent")
                                .description("测试主 Agent")
                                .role("main")
                                .instruction("你是主 Agent。")
                                .leader(false)  // key: leader=false
                                .subAgents(List.of(mathAgent()))
                                .build())
                        .printConfig(false)
                        .debug(true)  // must be true to trigger printSystemPromptsTree
                        .mode(AgentMode.ROUTER);

                AgentResponse response = agent.run("请计算 2+3");
                assertTrue(response.getOutput().contains("[Mock]"), "响应应包含 MockChatClient 输出");
                // 验证：当 leader=false 时，系统提示词树中不应出现"可用子 Agent"路由表
                String output = captured();
                assertFalse(output.contains("可用子 Agent"),
                        "leader=false 时系统提示词不应包含'可用子 Agent'路由表");
            }
        }

        @Test
        void testNoSubAgents_noCrash() {
            try (Agent agent = Agent.create("agentscope")) {
                agent.chatClient(mockChatClient())
                        .definition(AgentDefinition.builder()
                                .id("router-agent")
                                .name("主路由")
                                .description("无子 Agent 的路由器")
                                .role("router")
                                .instruction("我只能处理简单任务。")
                                .leader(true)
                                .subAgents(List.of())  // empty subAgents
                                .build())
                        .printConfig(true)
                        .debug(false)
                        .mode(AgentMode.ROUTER);

                AgentResponse response = agent.run("你好");
                assertNotNull(response, "响应不应为 null");
                assertNotNull(response.getOutput(), "输出不应为 null");
            }
        }

        @Test
        void testMixedSubAgents_textAndImage() {
            try (Agent agent = Agent.create("agentscope")) {
                agent.chatClient(mockChatClient())
                        .subAgent(mathAgent())           // text-only
                        .subAgent(imageAgent())          // ImageDefinition with ImageClient
                        .definition(AgentDefinition.builder()
                                .id("router-agent")
                                .name("混合路由 Agent")
                                .description("包含文本和图像子 Agent")
                                .role("mix_router")
                                .instruction("根据请求选择合适子 Agent")
                                .leader(true)
                                .subAgents(List.of(mathAgent(), imageAgent()))
                                .build())
                        .printConfig(true)
                        .debug(false)
                        .mode(AgentMode.ROUTER);

                AgentResponse response = agent.run("画只猫然后乘2*3");
                assertNotNull(response);
                String output = captured();
                // 确认路由表同时包含 text 和 image 子 Agent
                assertTrue(output.contains("数学助手"), "架构图中应出现数学助手");
                assertTrue(output.contains("绘图助手"), "架构图中应出现绘图助手");
                assertTrue(output.contains("ImageGenerationModel(mock-image-model)"),
                        "架构图中应显示 image sub-Agent 的 Dedicated Model");
            }
        }

        private static class MockImageClient implements ImageClient {
        @Override
        /** Generate */
        public BufferedImage generate(String prompt) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        /** 创建Task */
        public String createTask(String prompt) {
            throw new UnsupportedOperationException("Mock image client does not support async tasks");
        }

        @Override
        /** 查询Task */
        public com.chua.common.support.ai.image.ImageResponse queryTask(String taskId) {
            throw new UnsupportedOperationException("Mock image client does not support async tasks");
        }

        @Override
        /** ToString */
        public String toString() {
            return "MockImageClient{}";
        }
    }
}

