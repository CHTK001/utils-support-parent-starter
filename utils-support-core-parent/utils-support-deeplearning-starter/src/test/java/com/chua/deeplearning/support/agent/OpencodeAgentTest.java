package com.chua.deeplearning.support.agent;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentEvent;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * 测试：opencode 描述符指向 anomalyco 仓库、二进制命名且无 sha256 配套。
     */
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

    /**
     * 测试：所有已设置的旗标都被翻译成对应的 CLI 参数。
     *
     * @param dir 临时工作目录
     * @throws Exception 反射调用失败时抛出
     */
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

    /**
     * 测试：未设置的旗标不会出现在 CLI 参数里。
     */
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

    /**
     * 测试：definition 会生成合法 opencode.json 并回填 agentName。
     *
     * @param dir 临时工作目录
     * @throws Exception 反射调用失败时抛出
     */
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

    /**
     * 测试：已存在的 opencode.json 不被覆盖（no-clobber），并记入忽略项。
     *
     * @param dir 临时工作目录
     * @throws Exception 反射调用或文件读写失败时抛出
     */
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

    /**
     * 测试：未设置 workDir 时 definition 被记为忽略并说明原因。
     *
     * @param dir 临时工作目录（本例不使用其内容）
     * @throws Exception 反射调用失败时抛出
     */
    @Test
    void applyDefinition_requiresWorkDir(@TempDir Path dir) throws Exception {
        OpencodeAgent ag = new OpencodeAgent(); // 未设 workDir
        ag.definition(AgentDefinition.builder().id("pinger").instruction("只回 PONG").build());
        invoke(ag, "applyDefinition", new Class[]{});
        assertNull(get(ag, "agentName"));
        assertTrue(ignoredOf(ag).stream().anyMatch(s -> s.contains("workDir")),
                "无 workDir 时记为忽略并说明原因");
    }

    /**
     * 测试：生成的 agent 配置 JSON 可解析且转义原样可还原。
     *
     * @throws Exception 反射调用失败时抛出
     */
    @Test
    void buildAgentConfigJson_isParseable() throws Exception {
        String json = (String) invokeStatic("buildAgentConfigJson",
                new Class[]{String.class, String.class, String.class},
                "a-b", "desc \"q\"", "line1\nline2 \\ back");
        String prompt = Json.parse(json).get("agent").get("a-b").get("prompt").toStringValue(null);
        assertEquals("line1\nline2 \\ back", prompt);
    }

    // ── chatClient → model 派生 ───────────────────────────────────────

    /**
     * 测试：模型名优先派生为 provider/model 形式，已带 provider 的原样保留。
     */
    @Test
    void deriveModel_prefersProviderSlashModel() throws Exception {
        ChatClientSetting s = ChatClientSetting.builder().provider("openai").model("gpt-4").build();
        assertEquals("openai/gpt-4", deriveModel("gpt-4", s));
        assertEquals("anthropic/claude", deriveModel("anthropic/claude", s)); // 已带 provider 原样
        assertNull(deriveModel(null, null));
    }

    // ── 重试判定 ──────────────────────────────────────────────────────

    /**
     * 测试：重试判定的各边界（无配置、未达上限、已达上限、无限重试）。
     *
     * @throws Exception 反射调用失败时抛出
     */
    @Test
    void shouldRetry_boundaries() throws Exception {
        assertFalse(shouldRetryWithoutConfig(), "retryConfig=null 不重试");
        assertFalse(shouldRetry(0, 0, 0));
        assertTrue(shouldRetry(2, 0, 0));
        assertTrue(shouldRetry(2, 1, 0));
        assertFalse(shouldRetry(2, 2, 0), "已达 maxRetries");
        assertTrue(shouldRetry(-1, 99, 0), "无限重试");
    }

    // ── stream=true：逐行 NDJSON 解析与看门狗（离线，不拉起进程）──────

    /**
     * 测试：text 事件被解析进事件列表、累加到输出，并逐事件回调 onEvent。
     */
    @Test
    void feedLine_textEventStreamsAndAccumulates() {
        OpencodeAgent ag = new OpencodeAgent();
        List<AgentEvent> seen = new ArrayList<>();
        OpencodeAgent.StreamState st = new OpencodeAgent.StreamState(seen::add);

        boolean capped = ag.feedLine(st,
                "{\"type\":\"text\",\"sessionID\":\"ses_1\",\"timestamp\":100,\"part\":{\"text\":\"PONG\"}}");

        assertFalse(capped);
        assertEquals(1, st.events.size());
        assertEquals("text", st.events.get(0).type());
        assertEquals("ses_1", st.events.get(0).agentId());
        assertEquals(100L, st.events.get(0).timestamp());
        assertEquals("PONG", st.output.toString());
        assertEquals(1, seen.size(), "onEvent 应逐事件回调（stream=true）");
    }

    /**
     * 测试：空行、非 JSON、数组行被跳过，不产生事件也不回调。
     */
    @Test
    void feedLine_skipsNonEventLines() {
        OpencodeAgent ag = new OpencodeAgent();
        List<AgentEvent> seen = new ArrayList<>();
        OpencodeAgent.StreamState st = new OpencodeAgent.StreamState(seen::add);

        ag.feedLine(st, null);
        ag.feedLine(st, "");
        ag.feedLine(st, "   ");
        ag.feedLine(st, "plain log line");
        ag.feedLine(st, "[1,2,3]");

        assertTrue(st.events.isEmpty());
        assertTrue(seen.isEmpty());
        assertEquals("", st.output.toString());
    }

    /**
     * 测试：多条 step_finish 的 token 与费用在用量累加器中求和，保留最后一次停止原因。
     */
    @Test
    void feedLine_accumulatesUsageAcrossSteps() {
        OpencodeAgent ag = new OpencodeAgent();
        OpencodeAgent.StreamState st = new OpencodeAgent.StreamState(null);

        ag.feedLine(st, stepFinish(10, 5, 15, 0.01, "tool-calls"));
        ag.feedLine(st, stepFinish(3, 2, 5, 0.02, "stop"));

        AiUsage u = st.acc.toUsage(0L, 42L);
        assertNotNull(u);
        assertEquals(13, u.getInputTokens());
        assertEquals(7, u.getOutputTokens());
        assertEquals(20, u.getTotalTokens());
        assertEquals(0, new BigDecimal("0.03").compareTo(u.getTotalCost()));
        assertEquals("stop", u.getFinishReason());
        assertEquals(42L, u.getDurationMillis());
    }

    /**
     * 测试：maxToolIterations 看门狗在达到上限的那条 step_finish 返回 true。
     */
    @Test
    void feedLine_watchdogFiresAtLimit() {
        OpencodeAgent ag = new OpencodeAgent().maxToolIterations(2);
        OpencodeAgent.StreamState st = new OpencodeAgent.StreamState(null);

        assertFalse(ag.feedLine(st, stepFinish(1, 1, 2, 0, "tool-calls")), "第 1 轮未达上限");
        assertTrue(ag.feedLine(st, stepFinish(1, 1, 2, 0, "tool-calls")), "第 2 轮达上限应触发");
        assertEquals(2, st.stepCount);
    }

    /**
     * 测试：maxToolIterations<=0 时看门狗永不触发（不限轮数）。
     */
    @Test
    void feedLine_watchdogDisabledWhenNonPositive() {
        OpencodeAgent ag = new OpencodeAgent();
        OpencodeAgent.StreamState st = new OpencodeAgent.StreamState(null);
        for (int i = 0; i < 5; i++) {
            assertFalse(ag.feedLine(st, stepFinish(1, 1, 2, 0, "tool-calls")));
        }
    }

    /**
     * 测试：无 token 数据的纯 step_finish 不产出用量（toUsage 返回 null）。
     */
    @Test
    void feedLine_noUsageWhenNoTokens() {
        OpencodeAgent ag = new OpencodeAgent();
        OpencodeAgent.StreamState st = new OpencodeAgent.StreamState(null);
        ag.feedLine(st, "{\"type\":\"step_finish\",\"part\":{\"reason\":\"stop\"}}");
        assertNull(st.acc.toUsage(0L, 1L), "无 token 应返回 null");
    }

    /**
     * 构造一行 opencode step_finish NDJSON。
     *
     * @param input 输入 token
     * @param output 输出 token
     * @param total 总 token
     * @param cost 费用
     * @param reason 停止原因
     * @return NDJSON 行
     */
    private static String stepFinish(int input, int output, int total, double cost, String reason) {
        return "{\"type\":\"step_finish\",\"sessionID\":\"ses_x\",\"timestamp\":1,"
                + "\"part\":{\"reason\":\"" + reason + "\",\"cost\":" + cost
                + ",\"tokens\":{\"input\":" + input + ",\"output\":" + output
                + ",\"total\":" + total + ",\"reasoning\":0,\"cache\":{\"read\":0}}}}";
    }

    // ── reflection helpers ────────────────────────────────────────────

    /**
     * 以无 retryConfig 的代理实例调用 shouldRetry。
     *
     * @return 是否应当重试
     * @throws Exception 反射调用失败时抛出
     */
    private static boolean shouldRetryWithoutConfig() throws Exception {
        OpencodeAgent ag = new OpencodeAgent();
        return (Boolean) invoke(ag, "shouldRetry", new Class[]{Throwable.class, int.class},
                new RuntimeException("boom"), 0);
    }

    /**
     * 按指定的最大重试次数调用 shouldRetry。
     *
     * @param maxRetries 最大重试次数
     * @param attempt 当前已尝试次数
     * @param unused 占位参数，保持签名兼容
     * @return 是否应当重试
     * @throws Exception 反射调用失败时抛出
     */
    private static boolean shouldRetry(int maxRetries, int attempt, int unused) throws Exception {
        OpencodeAgent ag = new OpencodeAgent();
        ag.retryConfig(AgentRetryConfig.builder().maxRetries(maxRetries).build());
        return (Boolean) invoke(ag, "shouldRetry", new Class[]{Throwable.class, int.class},
                new RuntimeException("boom"), attempt);
    }

    /**
     * 用动态代理伪造 ChatClient，调用 deriveModel 验证派生结果。
     *
     * @param model 客户端报告的系统模型名
     * @param setting 客户端设置（提供 provider）
     * @return 派生出的 opencode 模型名
     * @throws Exception 反射调用失败时抛出
     */
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

    /**
     * 读取代理实例的忽略项集合。
     *
     * @param ag 代理实例
     * @return 被忽略的配置项集合
     * @throws Exception 反射读取失败时抛出
     */
    @SuppressWarnings("unchecked")
    private static Set<String> ignoredOf(OpencodeAgent ag) throws Exception {
        return (Set<String>) get(ag, "ignored");
    }

    /**
     * 反射读取目标对象的字段值。
     *
     * @param t 目标对象
     * @param field 字段名
     * @return 字段值
     * @throws Exception 反射失败时抛出
     */
    private static Object get(Object t, String field) throws Exception {
        Field f = OpencodeAgent.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(t);
    }

    /**
     * 反射设置目标对象的字段值。
     *
     * @param t 目标对象
     * @param field 字段名
     * @param value 要设置的值
     * @throws Exception 反射失败时抛出
     */
    private static void set(Object t, String field, Object value) throws Exception {
        Field f = OpencodeAgent.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(t, value);
    }

    /**
     * 反射调用实例方法。
     *
     * @param t 目标对象
     * @param name 方法名
     * @param types 参数类型列表
     * @param args 实参列表
     * @return 方法返回值
     * @throws Exception 反射失败时抛出
     */
    private static Object invoke(Object t, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = OpencodeAgent.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(t, args);
    }

    /**
     * 反射调用静态方法。
     *
     * @param name 方法名
     * @param types 参数类型列表
     * @param args 实参列表
     * @return 方法返回值
     * @throws Exception 反射失败时抛出
     */
    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = OpencodeAgent.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
}
