package com.chua.deeplearning.support.agent;

import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentRetryConfig;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.lang.json.Json;
import com.chua.deeplearning.support.engine.CliModelRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpencodeAgent} 参数适配 / 转化的离线单测。
 *
 * <p>不依赖网络与本地 opencode 二进制：只验证 descriptor 组装、CLI 参数翻译、
 * {@code definition→opencode.json} 生成（含转义与 no-clobber）、模型派生、重试判定。
 * 真实端到端（拉起 opencode 跑通流式与 agent 注入）另由联调验证。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class OpencodeAgentTest {

    // ── descriptor ────────────────────────────────────────────────────

    @Test
    void opencodeDescriptor_anomalycoUrlNoSha() {
        CliModelRunner.CliDescriptor d = CliModelRunner.opencode();
        assertEquals("opencode", d.cliId());
        assertEquals("opencode", d.binaryName());
        assertNull(d.sha256FileName(), "CLI 独立二进制无配套 .sha256");
        assertNull(d.entryPathIn(), "二进制在压缩包根目录");
        String url = d.downloadUrls().get(0);
        assertTrue(url.startsWith("https://github.com/anomalyco/opencode/releases/download/"), url);
        assertTrue(url.startsWith("opencode-", url.lastIndexOf('/') + 1), url);
    }

    // ── buildArgs：转发为真实 CLI 参数 ─────────────────────────────────

    @Test
    void buildArgs_translatesAllMappableFlags(@TempDir Path dir) throws Exception {
        OpencodeAgent ag = new OpencodeAgent()
                .model("openai/gpt-4")
                .session("sess-1")
                .workDir(dir.toString())
                .agentName("pinger");
        set(ag, "pure", Boolean.TRUE);
        set(ag, "debug", true);
        List<String> args = List.of((String[]) invoke(ag, "buildArgs", new Class[]{String.class}, "你好"));
        assertTrue(args.contains("run"));
        assertTrue(args.contains("--format") && args.contains("json"));
        assertTrue(args.contains("-m") && args.contains("openai/gpt-4"));
        assertTrue(args.contains("-s") && args.contains("sess-1"));
        assertTrue(args.contains("--agent") && args.contains("pinger"));
        assertTrue(args.contains("--dir"));
        assertTrue(args.contains("--pure"), "mcp(false) 应转 --pure");
        assertTrue(args.contains("--print-logs") && args.contains("--log-level") && args.contains("DEBUG"),
                "debug(true) 应转 --print-logs --log-level DEBUG");
    }

    @Test
    void buildArgs_omitsUnsetFlags() throws Exception {
        OpencodeAgent ag = new OpencodeAgent();
        List<String> args = List.of((String[]) invoke(ag, "buildArgs", new Class[]{String.class}, "x"));
        assertFalse(args.contains("-m"));
        assertFalse(args.contains("-s"));
        assertFalse(args.contains("--agent"));
        assertFalse(args.contains("--dir"));
        assertFalse(args.contains("--pure"));
        assertFalse(args.contains("--print-logs"));
    }

    // ── definition → opencode.json ────────────────────────────────────

    @Test
    void applyDefinition_writesAgentConfig(@TempDir Path dir) throws Exception {
        OpencodeAgent ag = new OpencodeAgent().workDir(dir.toString());
        ag.definition(AgentDefinition.builder().id("pinger").description("only pong")
                .instruction("只回复 \"PONG\"\n不要调用工具").build());
        invoke(ag, "applyDefinition", new Class[]{});

        Path cfg = dir.resolve("opencode.json");
        assertTrue(Files.exists(cfg), "应生成 opencode.json");
        assertEquals("pinger", get(ag, "agentName"), "agentName 应被回填用于 --agent");
        // 生成的是合法 JSON，且 prompt 原样可还原（转义正确）
        String prompt = Json.parse(Files.readString(cfg, StandardCharsets.UTF_8))
                .get("agent").get("pinger").get("prompt").toStringValue(null);
        assertEquals("只回复 \"PONG\"\n不要调用工具", prompt);
        assertEquals("primary", Json.parse(Files.readString(cfg, StandardCharsets.UTF_8))
                .get("agent").get("pinger").get("mode").toStringValue(null));
    }

    @Test
    void applyDefinition_neverClobbersExistingConfig(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("opencode.json");
        Files.write(cfg, "{ \"theme\": \"dark\" }".getBytes(StandardCharsets.UTF_8));
        OpencodeAgent ag = new OpencodeAgent().workDir(dir.toString());
        ag.definition(AgentDefinition.builder().id("pinger").instruction("只回 PONG").build());
        invoke(ag, "applyDefinition", new Class[]{});

        assertEquals("{ \"theme\": \"dark\" }", Files.readString(cfg, StandardCharsets.UTF_8),
                "已存在的 opencode.json 不得被覆盖");
        assertNull(get(ag, "agentName"), "no-clobber 时不应注入 --agent");
        assertTrue(ignoredOf(ag).stream().anyMatch(s -> s.startsWith("definition")));
    }

    @Test
    void applyDefinition_requiresWorkDir(@TempDir Path dir) throws Exception {
        OpencodeAgent ag = new OpencodeAgent(); // 未设 workDir
        ag.definition(AgentDefinition.builder().id("pinger").instruction("只回 PONG").build());
        invoke(ag, "applyDefinition", new Class[]{});
        assertNull(get(ag, "agentName"));
        assertTrue(ignoredOf(ag).stream().anyMatch(s -> s.contains("workDir")),
                "无 workDir 时记为忽略并说明原因");
    }

    @Test
    void buildAgentConfigJson_isParseable() throws Exception {
        String json = (String) invokeStatic("buildAgentConfigJson",
                new Class[]{String.class, String.class, String.class},
                "a-b", "desc \"q\"", "line1\nline2 \\ back");
        String prompt = Json.parse(json).get("agent").get("a-b").get("prompt").toStringValue(null);
        assertEquals("line1\nline2 \\ back", prompt);
    }

    // ── chatClient → model 派生 ───────────────────────────────────────

    @Test
    void deriveModel_prefersProviderSlashModel() throws Exception {
        ChatClientSetting s = ChatClientSetting.builder().provider("openai").model("gpt-4").build();
        assertEquals("openai/gpt-4", deriveModel("gpt-4", s));
        assertEquals("anthropic/claude", deriveModel("anthropic/claude", s)); // 已带 provider 原样
        assertNull(deriveModel(null, null));
    }

    // ── 重试判定 ──────────────────────────────────────────────────────

    @Test
    void shouldRetry_boundaries() throws Exception {
        assertFalse(shouldRetryWithoutConfig(), "retryConfig=null 不重试");
        assertFalse(shouldRetry(0, 0, 0));
        assertTrue(shouldRetry(2, 0, 0));
        assertTrue(shouldRetry(2, 1, 0));
        assertFalse(shouldRetry(2, 2, 0), "已达 maxRetries");
        assertTrue(shouldRetry(-1, 99, 0), "无限重试");
    }

    // ── reflection helpers ────────────────────────────────────────────

    private static boolean shouldRetryWithoutConfig() throws Exception {
        OpencodeAgent ag = new OpencodeAgent();
        return (Boolean) invoke(ag, "shouldRetry", new Class[]{Throwable.class, int.class},
                new RuntimeException("boom"), 0);
    }

    private static boolean shouldRetry(int maxRetries, int attempt, int unused) throws Exception {
        OpencodeAgent ag = new OpencodeAgent();
        ag.retryConfig(AgentRetryConfig.builder().maxRetries(maxRetries).build());
        return (Boolean) invoke(ag, "shouldRetry", new Class[]{Throwable.class, int.class},
                new RuntimeException("boom"), attempt);
    }

    private static String deriveModel(String model, ChatClientSetting setting) throws Exception {
        ChatClient cc = (ChatClient) Proxy.newProxyInstance(
                OpencodeAgentTest.class.getClassLoader(), new Class[]{ChatClient.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getModel" -> model;
                    case "getSetting" -> setting;
                    default -> null;
                });
        return (String) invokeStatic("deriveModel", new Class[]{ChatClient.class}, cc);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> ignoredOf(OpencodeAgent ag) throws Exception {
        return (Set<String>) get(ag, "ignored");
    }

    private static Object get(Object t, String field) throws Exception {
        Field f = OpencodeAgent.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(t);
    }

    private static void set(Object t, String field, Object value) throws Exception {
        Field f = OpencodeAgent.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(t, value);
    }

    private static Object invoke(Object t, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = OpencodeAgent.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(t, args);
    }

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = OpencodeAgent.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
}
