package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentHookEvent;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent + 真实 LLM (智谱 GLM-4-flash) 集成测试。
 *
 * <p>流程：查询北京天气 → 写入文件 → 验证 Hook 指标 + 文件内容
 *
 * @author CH
 * @since 4.0.0.42
 */
class AgentRealLlmTest {

    private static Path workDir;
    private static final List<AgentHookEvent> hookEvents = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setup() throws Exception {
        workDir = Path.of(System.getProperty("java.io.tmpdir"), "agent-real-" + System.currentTimeMillis());
        Files.createDirectories(workDir);
        System.out.println("[workdir] " + workDir.toAbsolutePath());
    }

    @AfterAll
    static void cleanup() {
        // Print final file content
        try {
            Path report = workDir.resolve("weather_report.txt");
            if (Files.exists(report)) {
                System.out.println("[file] " + Files.readString(report, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    /**
     * 真实 LLM 调用测试：Agent 使用智谱 GLM-4-flash 查询天气并写文件。
     * 需要环境变量 OPENAI_API_KEY 和 OPENAI_BASE_URL。
     */
    @Test
    void testAgentRealLlm_weatherAndFileWrite() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String baseUrl = System.getenv("OPENAI_BASE_URL");

        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("[SKIP] OPENAI_API_KEY not set, skipping real LLM test");
            return;
        }

        ChatClient client = ChatClient.create("openai", apiKey, baseUrl != null ? baseUrl : null)
                .model("glm-4-flash");

        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(client)
                    .debug(true)
                    .maxToolIterations(3)
                    .mode(AgentMode.SINGLE)
                    .definition(AgentDefinition.builder()
                            .id("weather-agent")
                            .name("天气助手")
                            .instruction("你是一个天气助手。用户请求时：1)查询指定城市天气；2)将结果写入报告文件。"
                                    + "使用 skill 'get_weather' 查询，使用 skill 'save_report' 写文件。")
                            .leader(false)
                            .build())
                    .skill("get_weather", "查询指定城市的实时天气（温度、湿度、天气描述）", args -> {
                        String city = (String) args.get("city");
                        String result = queryWeather(city != null ? city : "北京");
                        System.out.println("[skill] get_weather: " + result);
                        return com.chua.common.support.ai.skill.SkillResult.success(result);
                    })
                    .skill("save_report", "将文本内容写入指定路径的文件", args -> {
                        String pathStr = (String) args.get("path");
                        String content = (String) args.get("content");
                        if (pathStr == null || content == null) {
                            return com.chua.common.support.ai.skill.SkillResult.error("path/content 不能为空");
                        }
                        Path p = workDir.resolve(pathStr);
                        try {
                            Files.createDirectories(p.getParent());
                            Files.writeString(p, content, StandardCharsets.UTF_8);
                            long size = Files.size(p);
                            System.out.println("[skill] save_report: 文件已写入 " + p + " (" + size + " bytes)");
                            return com.chua.common.support.ai.skill.SkillResult.success(
                                    "文件已写入: " + p + " (" + size + " bytes)");
                        } catch (Exception e) {
                            return com.chua.common.support.ai.skill.SkillResult.error(e.getMessage());
                        }
                    })
                    .debugHook(event -> {
                        hookEvents.add(event);
                        System.out.printf("[HOOK] iteration=%s toolCalls=%s tokens=%s elapsed=%dms type=%s%n",
                                event.getIteration(),
                                event.getToolCallCount(),
                                event.getTotalTokens(),
                                event.getElapsedMillis(),
                                event.getType());
                    })
                    .run("请查询北京今天的天气情况，并将结果整理成一份报告写入 weather_report.txt 文件。");
        }

        // 验证 Hook 事件
        assertFalse(hookEvents.isEmpty(), "应收到至少一个 Hook 事件");

        // 验证文件已写入
        Path reportFile = workDir.resolve("weather_report.txt");
        assertTrue(Files.exists(reportFile), "weather_report.txt 应被写入到 " + reportFile);
        String content = Files.readString(reportFile, StandardCharsets.UTF_8);
        assertFalse(content.isBlank(), "weather_report.txt 内容不应为空");
        System.out.println("[PASS] 文件已写入: " + reportFile);
        System.out.println("[PASS] 文件内容: " + content.substring(0, Math.min(200, content.length())));

        // 验证指标
        long maxTokens = hookEvents.stream()
                .mapToLong(e -> e.getTotalTokens() != null ? e.getTotalTokens() : 0)
                .max().orElse(0);
        long maxElapsed = hookEvents.stream()
                .mapToLong(e -> e.getElapsedMillis() != null ? e.getElapsedMillis() : 0)
                .max().orElse(0);
        int distinctIterations = (int) hookEvents.stream()
                .map(AgentHookEvent::getIteration)
                .filter(v -> v != null)
                .distinct()
                .count();

        System.out.printf("[SUMMARY] iterations=%d maxTokens=%d maxElapsed=%dms events=%d%n",
                distinctIterations, maxTokens, maxElapsed, hookEvents.size());
    }

    /**
     * 纯天气查询测试（不依赖 LLM），验证真实 HTTP 调用。
     */
    @Test
    void testWeatherApi_realCall() {
        String result = queryWeather("北京");
        System.out.println("[weather] " + result);
        assertTrue(result.contains("北京"), "结果应包含城市名");
        assertTrue(result.contains("°C"), "结果应包含温度");
    }

    // ==================== 天气查询 ====================

    private static String queryWeather(String city) {
        String url = "https://wttr.in/" + city.trim() + "?format=j1";
        try {
            String json = HttpClientFactory.of(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126")
                    .get()
                    .getBodyString();
            if (json == null || json.isEmpty()) {
                return city + " -> 查询失败：无响应";
            }
            int tempC = parseJsonInt(json, "temp_C");
            int humidity = parseJsonInt(json, "humidity");
            int feelsLike = parseJsonInt(json, "FeelsLikeC");
            String desc = extractWeatherDesc(json);
            return String.format("%s 当前 %d°C（体感%s°C） 湿度%d%% %s",
                    city, tempC, feelsLike, humidity, desc);
        } catch (Exception e) {
            return city + " -> 天气查询异常: " + e.getMessage();
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
}
