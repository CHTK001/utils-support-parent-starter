package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentHookEvent;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent + Skill 集成测试：
 * <ul>
 *   <li>testWeatherSkill_only_queriesRealWttrIn — 直连 wttr.in 查天气</li>
 *   <li>testFileWriteSkill_writesToTempDir — 写入文件验证</li>
 *   <li>testAgentHook_iteration_toolCallCount_elapsed — 验证 Hook 事件中的 iteration/toolCallCount/elapsed 指标</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
class AgentMcpSkillTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream captureOut;

    /** Hook 事件收集 */
    private final List<AgentHookEvent> hookEvents = new CopyOnWriteArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        captureOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captureOut));
        hookEvents.clear();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    // ==================== 真实天气查询 ====================

    private String queryWeather(String city) {
        String url = "https://wttr.in/" + city.trim() + "?format=j1";
        try {
            String json = HttpClientFactory.of(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .getBodyString();
            if (json == null || json.isEmpty()) {
                return "天气查询失败：无响应";
            }
            int tempC = parseJsonInt(json, "temp_C");
            int humidity = parseJsonInt(json, "humidity");
            String feelsLike = String.valueOf(parseJsonInt(json, "FeelsLikeC"));
            // weatherDesc 是数组 {"value":"Smoky haze"}，取第一个 value
            String weatherDesc = extractWeatherDesc(json);
            return String.format("%s 当前 %d°C（体感%s°C） 湿度%d%% %s",
                    city, tempC, feelsLike, humidity, weatherDesc);
        } catch (Exception e) {
            return "天气查询异常: " + e.getMessage();
        }
    }

    /** 从 current_condition 中提取 weatherDesc 数组的第一个 value */
    private static String extractWeatherDesc(String json) {
        try {
            int wi = json.indexOf("\"weatherDesc\"");
            if (wi < 0) return "?";
            int ai = json.indexOf('[', wi);
            if (ai < 0) return "?";
            int objStart = json.indexOf('{', ai);
            if (objStart < 0) return "?";
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) return "?";
            String obj = json.substring(objStart, objEnd + 1);
            int vi = obj.indexOf("\"value\"");
            if (vi < 0) return "?";
            int ci2 = obj.indexOf(':', vi);
            int si = obj.indexOf('"', ci2 + 1);
            int ei = obj.indexOf('"', si + 1);
            return obj.substring(si + 1, ei);
        } catch (Exception e) {
            return "?";
        }
    }

    private static int parseJsonInt(String json, String key) {
        try {
            int ki = json.indexOf("\"" + key + "\"");
            if (ki < 0) return -1;
            int ci = json.indexOf(':', ki);
            if (ci < 0) return -1;
            int si = json.indexOf('"', ci + 1);
            if (si < 0) return -1;
            int ei = json.indexOf('"', si + 1);
            if (ei < 0) return -1;
            String val = json.substring(si + 1, ei).trim();
            return Integer.parseInt(val);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String extractJsonValue(String json, String key) {
        try {
            int ki = json.indexOf("\"" + key + "\"");
            if (ki < 0) return "?";
            int ci = json.indexOf(':', ki);
            if (ci < 0) return "?";
            int si = json.indexOf('"', ci + 1);
            int ei = json.indexOf('"', si + 1);
            return json.substring(si + 1, ei);
        } catch (Exception e) {
            return "?";
        }
    }

    // ==================== 测试用例 ====================

    /**
     * 验证 wttr.in 天气查询可用。
     */
    @Test
    void testWeatherSkill_only_queriesRealWttrIn() throws Exception {
        String result = queryWeather("北京");
        System.out.println("[weather] " + result);
        // 写入文件供查看
        java.nio.file.Files.writeString(
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "weather-result.txt"),
            result, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(result.contains("北京"), "结果应包含城市名");
        assertTrue(result.contains("°C"), "结果应包含温度");
    }

    /**
     * 验证文件写入到临时目录。
     */
    @Test
    void testFileWrite_writesToTempDir() throws Exception {
        Path target = tempDir.resolve("test/hello.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "hello agentscope", StandardCharsets.UTF_8);
        assertTrue(Files.exists(target));
        assertEquals("hello agentscope", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * 验证 Agent Hook 事件中的 iteration / toolCallCount / elapsedMillis 指标。
     *
     * <p>使用简易 ChatClient，不依赖真实 LLM，直接验证 Hook 适配器的事件结构。
     */
    @Test
    void testAgentHook_iteration_toolCallCount_elapsed() throws Exception {
        ChatClient client = new ChatClient() {
            @Override
            public String chatSync(String prompt) {
                return "任务完成，天气已查询，文件已写入";
            }

            @Override
            public void chat(String prompt, Consumer<ChatResponse> consumer) {
                chat(prompt, consumer, () -> {}, e -> {});
            }

            @Override
            public void chat(String prompt, Consumer<ChatResponse> consumer,
                             Runnable onComplete, Consumer<Throwable> onError) {
                consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());
                String out = chatSync(prompt);
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STREAMING).content(out).build());
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP).fullContent(out).build());
                onComplete.run();
            }

            @Override
            public List<com.chua.common.support.ai.chat.ModelDefinition> models() {
                return List.of();
            }

            @Override
            public void close() {
            }
        };

        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(client)
                    .debug(true)
                    .definition(AgentDefinition.builder()
                            .id("hook-test-agent")
                            .name("Hook测试Agent")
                            .instruction("你是一个测试用的助手。")
                            .leader(false)
                            .build())
                    .mode(AgentMode.SINGLE)
                    .debugHook(event -> {
                        hookEvents.add(event);
                        System.out.printf("[HOOK] type=%s iteration=%s tools=%s tokens=%s elapsed=%dms%n",
                                event.getType(), event.getIteration(),
                                event.getToolCallCount(), event.getTotalTokens(),
                                event.getElapsedMillis());
                    })
                    .run("你好，请总结一下北京今天的天气并写入报告");
        }

        assertFalse(hookEvents.isEmpty(), "应至少收到一个 Hook 事件");

        // 验证 iteration 有值
        List<Integer> iterations = hookEvents.stream()
                .map(AgentHookEvent::getIteration)
                .filter(v -> v != null)
                .distinct()
                .toList();
        assertTrue(iterations.size() >= 1,
                "至少应有 1 个 iteration 值，实际: " + iterations);

        // 验证 elapsedMillis 非负且递增
        long lastElapsed = 0;
        for (AgentHookEvent ev : hookEvents) {
            Long el = ev.getElapsedMillis();
            assertNotNull(el, "elapsedMillis 不应为 null");
            assertTrue(el >= 0, "elapsedMillis 不应为负: " + el);
            assertTrue(el >= lastElapsed,
                    "elapsedMillis 应递增: " + lastElapsed + " -> " + el);
            lastElapsed = el;
        }

        // 验证 toolCallCount 不为负（SINGLE 模式下可能为 0）
        hookEvents.stream()
                .map(AgentHookEvent::getToolCallCount)
                .filter(v -> v != null)
                .forEach(tc -> assertTrue(tc >= 0, "toolCallCount 不应为负: " + tc));

        System.out.println("执行轮次: " + iterations);
        System.out.println("最大耗时: " + lastElapsed + "ms");
        System.out.println("事件总数: " + hookEvents.size());

        // 验证输出中包含事件信息
        String captured = captureOut.toString();
        assertTrue(captured.contains("[HOOK]"), "输出应包含 HOOK 事件日志");
        assertTrue(captured.contains("iteration="), "输出应包含 iteration 字段");
        assertTrue(captured.contains("elapsed="), "输出应包含 elapsed 字段");
    }

    /**
     * 验证 Agent 架构图表打印正常。
     */
    @Test
    void testPrintConfig_printsArchitecture() {
        ChatClient client = new ChatClient() {
            @Override
            public String chatSync(String prompt) { return "done"; }
            @Override
            public void chat(String prompt, Consumer<ChatResponse> consumer) {
                chat(prompt, consumer, () -> {}, e -> {});
            }
            @Override
            public void chat(String prompt, Consumer<ChatResponse> consumer,
                             Runnable onComplete, Consumer<Throwable> onError) {
                consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());
                consumer.accept(ChatResponse.builder().state(ChatResponse.State.STOP).build());
                onComplete.run();
            }
            @Override
            public List<com.chua.common.support.ai.chat.ModelDefinition> models() { return List.of(); }
            @Override
            public void close() {}
        };

        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(client)
                    .definition(AgentDefinition.builder()
                            .id("arch-test")
                            .name("架构测试")
                            .instruction("测试用")
                            .leader(true)
                            .build())
                    .printConfig(true)
                    .run("测试");
        }

        String captured = captureOut.toString();
        assertTrue(captured.contains("Agent Architecture Diagram"),
                "架构图应输出 'Agent Architecture Diagram'，实际:\n" + captured);
        assertTrue(captured.contains("arch-test"), "架构图应包含 Agent id");
    }
}
