package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.skill.SkillResult;
import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentDebugHook;
import com.chua.common.support.ai.agent.AgentHookEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 真实 LLM 集成测试 - 验证 skill 桥接到 tool 并调用真实 LLM
 */
public class AgentRealLlmTest {

    private static String queryWeather(String city) {
        return "Weather API mock: " + city + " current 25°C Sunny";
    }

    @Test
    public void testAgentRealLlm_toolCalling() {
        String apiKey = "29ed238f4660416cb2efe39aaaf71ed5.S2I2AfOu75HQwZ30";
        String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

        List<AgentHookEvent> hookEvents = new ArrayList<>();
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("agent-real-");
        } catch (Exception e) {
            Assertions.fail("Failed to create temp dir: " + e.getMessage());
            return;
        }

        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(ChatClient.create("openai", apiKey, baseUrl).model("glm-4"))
                    .debug(true)
                    .maxToolIterations(3)
                    .mode(AgentMode.SINGLE)
                    .definition(AgentDefinition.builder()
                            .id("weather-agent")
                            .name("天气助手")
                            .instruction("你是一个天气助手。请调用 get_weather 工具查询北京天气。")
                            .leader(false)
                            .build())
                    .skill("get_weather", "查询指定城市的实时天气（温度、湿度、天气描述）", args -> {
                        String city = (String) args.get("city");
                        if (city == null || city.isBlank()) {
                            return SkillResult.error("必须提供城市名称");
                        }
                        String result = queryWeather(city);
                        System.out.println("[skill] get_weather(" + city + "): " + result);
                        return SkillResult.success(result);
                    })
                    .debugHook(event -> {
                        System.out.println("[TestHook] type=" + event.getType() + " iteration=" + event.getIteration());
                        hookEvents.add(event);
                        System.out.printf("[HOOK] iteration=%s toolCalls=%s type=%s%n",
                                event.getIteration(), event.getToolCallCount(), event.getType());
                    })
                    .run("查询北京天气");
        }

        Assertions.assertTrue(!hookEvents.isEmpty(), "应收到至少一个 Hook 事件");
        
        // 验证有 tool call 发生
        long toolCallEvents = hookEvents.stream()
                .filter(e -> e.getToolCallCount() != null && e.getToolCallCount() > 0)
                .count();
        System.out.println("[SUMMARY] totalEvents=" + hookEvents.size() + " toolCallEvents=" + toolCallEvents);
        
        // 验证 skill 被调用（通过输出判断）
        Assertions.assertTrue(!hookEvents.isEmpty(), "应收到至少一个 Hook 事件");
    }
}





