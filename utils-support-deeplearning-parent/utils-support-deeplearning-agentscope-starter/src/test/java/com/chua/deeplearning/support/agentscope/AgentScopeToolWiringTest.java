package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentTool;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentScopeAgent} 进程内工具面测试。
 *
 * <p>覆盖两段：注册表可见性（{@code tool/tools/disableTool}）与桥接器本身
 * （{@code FunctionAgentTool} 真的调用 Java 执行器，并把返回值/异常映射成
 * agentscope 的工具结果块）。不触发模型调用。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class AgentScopeToolWiringTest {

    /**
     * 构造回显工具。
     *
     * @return 工具定义
     */
    private static AgentTool echoTool() {
        return AgentTool.builder()
                .name("echo")
                .description("回显输入文本")
                .parameters(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")))
                .executor(args -> "ok:" + args.get("text"))
                .build();
    }

    /**
     * 通过反射取得私有桥接器实例。
     *
     * @param tool 工具定义
     * @return 桥接器
     * @throws Exception 反射失败
     */
    private static Object bridge(AgentTool tool) throws Exception {
        Class<?> clazz = Class.forName(
                "com.chua.deeplearning.support.agentscope.AgentScopeAgent$FunctionAgentTool");
        Constructor<?> ctor = clazz.getDeclaredConstructor(AgentTool.class);
        ctor.setAccessible(true);
        return ctor.newInstance(tool);
    }

    @Test
    void toolRegistryIsVisibleAndDisableIsTracked() {
        Agent agent = Agent.create("agentscope");
        try {
            assertTrue(agent.tools().isEmpty());
            AgentTool echo = echoTool();
            agent.tool(echo).tool(null);

            assertEquals(1, agent.tools().size());
            assertEquals("echo", agent.tools().get(0).getName());
            assertEquals("回显输入文本", agent.tools().get(0).getDescription());
            agent.disableTool("echo");
        } finally {
            agent.close();
        }
    }

    @Test
    void disabledToolsAreNotRegisteredIntoKitchenToolkit() {
        Map<String, com.chua.common.support.ai.skill.SkillHandler> skills = Map.of(
                "skill-a", args -> com.chua.common.support.ai.skill.SkillResult.success("s"));
        Map<String, AgentTool> local = Map.of("echo", echoTool());

        io.agentscope.core.tool.Toolkit toolkit = AgentScopeAgent.buildToolkit(
                skills, Map.of(), local, List.of(), null, List.of("echo"));

        assertNotNull(toolkit);
        assertTrue(toolkit.getToolNames().contains("skill-a"), "技能应照常注册: " + toolkit.getToolNames());
        assertTrue(!toolkit.getToolNames().contains("echo"), "被关闭的工具不应注册: " + toolkit.getToolNames());
    }

    @Test
    void bridgeExecutesJavaExecutorAndReturnsTextBlock() throws Exception {
        Object bridge = bridge(echoTool());
        ToolCallParam param = ToolCallParam.builder().input(Map.of("text", "x")).build();

        @SuppressWarnings("unchecked")
        reactor.core.publisher.Mono<ToolResultBlock> mono = (reactor.core.publisher.Mono<ToolResultBlock>)
                bridge.getClass().getMethod("callAsync", ToolCallParam.class).invoke(bridge, param);
        ToolResultBlock block = mono.block();

        assertNotNull(block);
        assertEquals(ToolResultState.RUNNING, block.getState(),
                "与既有 Skill/MCP 桥接一致：结果块交由 agentscope 运行时收尾");
        assertEquals("ok:x", ((TextBlock) block.getOutput().get(0)).getText());
    }

    @Test
    void bridgeMapsExecutorFailureToErrorBlock() throws Exception {
        AgentTool failing = AgentTool.builder()
                .name("boom")
                .description("总是失败")
                .executor(args -> {
                    throw new IllegalStateException("炸了");
                })
                .build();
        Object bridge = bridge(failing);
        ToolCallParam param = ToolCallParam.builder().input(Map.of()).build();

        @SuppressWarnings("unchecked")
        reactor.core.publisher.Mono<ToolResultBlock> mono = (reactor.core.publisher.Mono<ToolResultBlock>)
                bridge.getClass().getMethod("callAsync", ToolCallParam.class).invoke(bridge, param);
        ToolResultBlock block = mono.block();

        assertNotNull(block);
        assertEquals(ToolResultState.ERROR, block.getState());
        assertTrue(((TextBlock) block.getOutput().get(0)).getText().contains("炸了"));
    }

    /**
     * 工具结果截断阈值必须真的落到实例字段（并校验非法值）。
     *
     * @throws Exception 反射失败
     */
    @Test
    void toolResultEvictionIsStored() throws Exception {
        Agent agent = Agent.create("agentscope");
        try {
            agent.toolResultEviction(5000, 800);

            java.lang.reflect.Field max = agent.getClass().getDeclaredField("toolResultMaxChars");
            java.lang.reflect.Field head = agent.getClass().getDeclaredField("toolResultHeadChars");
            max.setAccessible(true);
            head.setAccessible(true);
            assertEquals(5000, max.getInt(agent));
            assertEquals(800, head.getInt(agent));

            assertThrows(IllegalArgumentException.class, () -> agent.toolResultEviction(-1, 10));
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
        Agent agent = Agent.create("agentscope");
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
        Agent agent = Agent.create("agentscope");
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
     * 关闭待执行工具恢复后，resumeStream 必须直接返回错误事件而不是静默恢复。
     */
    @Test
    void pendingToolRecoveryDisabledTurnsResumeIntoErrorEvent() {
        Agent agent = Agent.create("agentscope");
        try {
            agent.pendingToolRecovery(false);
            java.util.List<com.chua.common.support.ai.agent.AgentStreamEvent> events =
                    agent.resumeStream("reply-1", java.util.List.of()).collectList().block();

            assertNotNull(events);
            assertEquals(1, events.size());
            assertEquals(com.chua.common.support.ai.agent.AgentStreamEvent.Type.ERROR,
                    events.get(0).getType());
            assertTrue(events.get(0).getToolOutput().contains("pendingToolRecovery(false)"),
                    "错误信息必须点明被关闭的开关: " + events.get(0).getToolOutput());
        } finally {
            agent.close();
        }
    }
}
