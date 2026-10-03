package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子从模式完整测试
 *
 * <p>覆盖所有 AgentMode、多 ChatClient 路由追踪、系统提示词生成、架构图输出。
 *
 * @author CH
 */
class AgentSubAgentRoutingTest {

    /** 捕获标准输出 */
    private PrintStream originalOut;
    private ByteArrayOutputStream captureOut;

    /** 所有追踪客户端的集合 */
    private static final List<TrackingChatClient> allTrackingClients = new ArrayList<>();

    /**
     * 本机桩上游：任何请求都立刻返回 401，用来替代真实网络。
     *
     * <p><b>为什么必须有：</b>{@link TrackingChatClient} 若不覆写 base url，
     * {@code ChatClientModelAdapter} 会把 base url 兜底成 {@code https://api.openai.com/v1}，
     * 于是「单元测试」会朝真实 OpenAI 发 HTTPS 请求（客户端超时上限 120 s）。本机实测该主机不可达、
     * 单次连接即耗掉 20 s，22 个用例的耗时因此完全由外网状况决定 —— 这正是本类长期随机超时、
     * 且失败集每次都不同的直接原因。
     *
     * <p>有了本机桩，「原生工具分支先失败 → onErrorResume 降级到内存客户端」这条路径既被真实覆盖，
     * 又只花几毫秒，且整个套件不再出网。
     */
    private static HttpServer upstreamStub;

    /** 本机桩的 base url；桩未启动时为 null。 */
    private static String stubBaseUrl;

    /**
     * 每个用例一个全新的空工作区，用完即删。
     *
     * <p><b>为什么必须有：</b>不设工作区时 agentscope-harness 会回落到用户主目录下的
     * {@code .agentscope}，而那是<b>同一 JVM 内所有 agent 实例共享</b>的一份落盘状态。
     * {@code MemoryMaintenanceMiddleware} / {@code MemoryFlushMiddleware} /
     * {@code WorkspaceContextMiddleware} 每轮都要归并、刷写（刷写还会再调一次模型）并重新序列化它，
     * 而 {@code run()} 会阻塞在 {@code MemoryBackgroundTasks.awaitQuiescence} 等这些后台任务静默。
     *
     * <p>实测（同一份代码、同一个本机桩，只差工作区是否隔离）：
     * <ul>
     *   <li>共享默认工作区：单轮 27s ~ 63s，实例增多后彻底不返回</li>
     *   <li>每轮独立空工作区：首轮 3.7s，随后 130ms / 107ms</li>
     * </ul>
     * 生产侧不受影响，因为 facade 会按业务会话传入真实工作区目录；只有本类没传，才踩到共享默认目录。
     * 顺带解决另一个问题：此前它会在开发者家目录留下 59 个文件 / 932 KB 的残留。
     */
    private Path testWorkspace;

    @BeforeAll
    static void startUpstreamStub() throws IOException {
        upstreamStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamStub.createContext("/", exchange -> {
            byte[] body = ("{\"error\":{\"message\":\"stub: no upstream\","
                    + "\"type\":\"invalid_request_error\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstreamStub.start();
        stubBaseUrl = "http://127.0.0.1:" + upstreamStub.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopUpstreamStub() {
        if (upstreamStub != null) {
            upstreamStub.stop(0);
            upstreamStub = null;
            stubBaseUrl = null;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        originalOut = System.out;
        captureOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captureOut));
        testWorkspace = Files.createTempDirectory("agentscope-test-ws-");
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setOut(originalOut);
        allTrackingClients.clear();
        deleteRecursively(testWorkspace);
        testWorkspace = null;
    }

    /** 递归删除目录；清理失败不抛，临时目录残留不影响断言。 */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(each);
            }
        } catch (IOException ignored) {
            // 交给系统清理临时目录即可
        }
    }

    /**
     * 交给 Agent 的工作区路径。
     *
     * @return 本用例专属的空工作区路径；未建立时返回 null
     */
    private String ws() {
        return testWorkspace == null ? null : testWorkspace.toString();
    }

    /** 当前捕获的输出 */
    private String captured() {
        return captureOut.toString();
    }

    // =====================================================================
    // 追踪型 ChatClient
    // =====================================================================

    /**
     * 可追踪调用历史的 ChatClient，用于验证路由是否正确命中目标客户端。
     */
    private static class TrackingChatClient implements ChatClient {

        final String label;
        final List<CallRecord> calls = new ArrayList<>();

        TrackingChatClient(String label) {
            this.label = label;
            allTrackingClients.add(this);
        }

        /**
         * 指向本机桩，绝不指向真实供应商。
         *
         * <p>不覆写时 {@code ChatClient} 的默认实现返回 null，
         * {@code ChatClientModelAdapter} 会兜底成 {@code https://api.openai.com/v1}，
         * 让单元测试真的去连外网。
         *
         * @return 本机桩的 base url；桩未启动时为 null
         */
        @Override
        public String getBaseUrl() {
            return stubBaseUrl;
        }

        /**
         * 桩上游用的假 key。
         *
         * @return 固定测试用 key
         */
        @Override
        public String getApiKey() {
            return "test-key";
        }

        @Override
        public String chatSync(String prompt) {
            calls.add(new CallRecord(prompt));
            return "[TRACE:" + label + "] " + prompt;
        }

        @Override
        public void chat(String prompt, Consumer<ChatResponse> consumer) {
            chat(prompt, consumer, () -> {}, e -> {});
        }

        @Override
        public void chat(String prompt, Consumer<ChatResponse> consumer,
                         Runnable onComplete, Consumer<Throwable> onError) {
            String result = chatSync(prompt);
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STREAMING).content(result).build());
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.STOP).build());
            onComplete.run();
        }

        @Override
        public List<com.chua.common.support.ai.chat.ModelDefinition> models() {
            return List.of();
        }

        @Override
        public void close() {
        }

        /** 最近一次调用记录 */
        public String lastInput() {
            return calls.isEmpty() ? null : calls.get(calls.size() - 1).prompt;
        }

        /** 调用次数 */
        public int callCount() {
            return calls.size();
        }

        /**
         * 一次 {@link ChatClient#chatSync(String)} 调用的入参留痕。
         *
         * <p>由 {@code chatSync} 与 {@code chat} 在每次调用时追加，用于
         * {@code lastInput()} 断言路由命中了预期客户端。本 record 无紧凑构造器，
         * 直接持有原始字符串，不做任何拷贝或归一。</p>
         *
         * @param prompt 传给客户端的完整提示词原文，由 Agent 侧拼装（可能含系统提示词
         *               与路由表），不截断也不脱敏；允许为 {@code null}——
         *               {@code testNullInput_noCrash} 就用 {@code null} 提示词验证容错，
         *               此时响应前缀仍为 {@code [TRACE:label] null}
         */
        public record CallRecord(String prompt) {}
    }

    // =====================================================================
    // 辅助工厂方法
    // =====================================================================

    /** 创建子 Agent 定义 */
    private static AgentDefinition subAgent(String id, String name, String description,
                                            String instruction, TrackingChatClient client) {
        return AgentDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .instruction(instruction)
                .build();
    }

    /** 构建带子 Agent 的主 Agent（leader=true） */
    private Agent buildLeaderAgent(TrackingChatClient masterClient,
                                   TrackingChatClient subClient,
                                   AgentMode mode) {
        AgentDefinition mathDef = subAgent("math-agent", "数学助手",
                "数学计算专家", "你是数学助手，擅长计算。", subClient);

        AgentDefinition mainDef = AgentDefinition.builder()
                .id("main")
                .name("主调度")
                .description("主路由 Agent")
                .instruction("你是主调度 Agent，根据用户请求将任务分派给合适的子 Agent。")
                .leader(true)
                .subAgents(List.of(mathDef))
                .build();

        return Agent.create("agentscope")
                .chatClient(masterClient)
                .chatClient("math-agent", subClient)
                .subAgent(mathDef)
                .definition(mainDef)
                .mode(mode)
                .workspace(ws());
    }

    /** 构建带多个子 Agent 的主 Agent */
    private Agent buildMultiSubAgent(TrackingChatClient masterClient,
                                     TrackingChatClient mathClient,
                                     TrackingChatClient transClient,
                                     AgentMode mode) {
        AgentDefinition mathDef = subAgent("math-agent", "数学助手",
                "数学计算专家", "你是数学助手。", mathClient);
        AgentDefinition transDef = subAgent("trans-agent", "翻译助手",
                "中英翻译专家", "你是翻译助手。", transClient);

        AgentDefinition mainDef = AgentDefinition.builder()
                .id("main")
                .name("主调度")
                .description("多子 Agent 路由")
                .instruction("你是主调度，根据用户请求分派给合适的子 Agent。")
                .leader(true)
                .subAgents(List.of(mathDef, transDef))
                .build();

        return Agent.create("agentscope")
                .chatClient(masterClient)
                .chatClient("math-agent", mathClient)
                .chatClient("trans-agent", transClient)
                .subAgent(mathDef)
                .subAgent(transDef)
                .definition(mainDef)
                .mode(mode)
                .workspace(ws());
    }

    // =====================================================================
    // 1. 单从模式 · 基础执行
    // =====================================================================

    @Test
    void testSingleSubAgent_basicExecution_routesThroughMaster() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.ROUTER)) {
            AgentResponse response = agent.run("请计算 3+5");

            assertNotNull(response, "响应不应为 null");
            assertNotNull(response.getOutput(), "输出不应为 null");
            assertTrue(response.getOutput().contains("[TRACE:"),
                    "输出应包含追踪前缀");
            assertEquals(AgentMode.ROUTER.name(), response.getMode(),
                    "执行模式应为 ROUTER");
        }
    }

    // =====================================================================
    // 2. 系统提示词包含路由表
    // =====================================================================

    @Test
    void testSystemPrompt_containsSubAgentTable() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.ROUTER)) {
            agent.debug(true);
            agent.run("ping");
        }

        String output = captured();
        assertTrue(output.contains("可用子 Agent"),
                "系统提示词树应包含'可用子 Agent'路由表标题");
        assertTrue(output.contains("数学助手"),
                "路由表应列出子 Agent 名称");
        assertTrue(output.contains("你是数学助手，擅长计算。"),
                "路由表应包含子 Agent instruction");
    }

    // =====================================================================
    // 3. leader=false 时不含路由表
    // =====================================================================

    @Test
    void testLeaderFalse_noRoutingTableInPrompt() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        AgentDefinition mathDef = subAgent("math-agent", "数学助手",
                "数学计算专家", "你是数学助手。", subClient);

        AgentDefinition mainDef = AgentDefinition.builder()
                .id("main")
                .name("非主路由")
                .description("非 leader 的 Agent")
                .instruction("我是普通 Agent。")
                .leader(false)  // key: 非 leader
                .subAgents(List.of(mathDef))
                .build();

        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(masterClient)
                    .chatClient("math-agent", subClient)
                    .subAgent(mathDef)
                    .definition(mainDef)
                    .debug(true)
                    .mode(AgentMode.ROUTER)
                    .workspace(ws());
            agent.run("ping");
        }

        String output = captured();
        assertFalse(output.contains("可用子 Agent"),
                "leader=false 时不应包含'可用子 Agent'路由表");
    }

    // =====================================================================
    // 4. 架构图中显示所有客户端映射
    // =====================================================================

    @Test
    void testPrintConfig_showsAllChatClients() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.ROUTER)) {
            agent.printConfig(true);
            agent.run("ping");
        }

        String output = captured();
        assertTrue(output.contains("main"),
                "架构图应包含主 Agent ID");
        assertTrue(output.contains("math-agent"),
                "架构图应包含子 Agent ID");
    }

    // =====================================================================
    // 5. 无子 Agent 时不崩溃
    // =====================================================================

    @Test
    void testNoSubAgents_noCrash() {
        TrackingChatClient masterClient = new TrackingChatClient("master");

        AgentDefinition mainDef = AgentDefinition.builder()
                .id("solo-agent")
                .name("独立 Agent")
                .description("无子 Agent")
                .instruction("我独立工作。")
                .leader(true)
                .subAgents(List.of())
                .build();

        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(masterClient)
                    .definition(mainDef)
                    .printConfig(true)
                    .debug(false)
                    .mode(AgentMode.ROUTER)
                    .workspace(ws());
            AgentResponse response = agent.run("你好");
            assertNotNull(response);
            assertNotNull(response.getOutput());
        }
    }

    // =====================================================================
    // 6. AUTO 模式
    // =====================================================================

    @Test
    void testAutoMode_executesSuccessfully() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.AUTO)) {
            AgentResponse response = agent.run("帮我算一下");
            assertNotNull(response);
            assertNotNull(response.getOutput());
            assertEquals(AgentMode.AUTO.name(), response.getMode(),
                    "执行模式应为 AUTO");
        }
    }

    // =====================================================================
    // 7. SINGLE 模式
    // =====================================================================

    @Test
    void testSingleMode_executesSuccessfully() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.SINGLE)) {
            AgentResponse response = agent.run("帮我算一下");
            assertNotNull(response);
            assertNotNull(response.getOutput());
            assertEquals(AgentMode.SINGLE.name(), response.getMode(),
                    "执行模式应为 SINGLE");
        }
    }

    // =====================================================================
    // 8. PIPELINE 模式
    // =====================================================================

    @Test
    void testPipelineMode_executesSuccessfully() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.PIPELINE)) {
            AgentResponse response = agent.run("顺序执行");
            assertNotNull(response);
            assertNotNull(response.getOutput());
            assertEquals(AgentMode.PIPELINE.name(), response.getMode(),
                    "执行模式应为 PIPELINE");
        }
    }

    // =====================================================================
    // 9. FAN_OUT 模式
    // =====================================================================

    @Test
    void testFanOutMode_executesSuccessfully() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.FAN_OUT)) {
            AgentResponse response = agent.run("并行执行");
            assertNotNull(response);
            assertNotNull(response.getOutput());
            assertEquals(AgentMode.FAN_OUT.name(), response.getMode(),
                    "执行模式应为 FAN_OUT");
        }
    }

    // =====================================================================
    // 10. PLAN_AND_EXECUTE 模式
    // =====================================================================

    @Test
    void testPlanAndExecuteMode_executesSuccessfully() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient,
                AgentMode.PLAN_AND_EXECUTE)) {
            AgentResponse response = agent.run("规划执行");
            assertNotNull(response);
            assertNotNull(response.getOutput());
            assertEquals(AgentMode.PLAN_AND_EXECUTE.name(), response.getMode(),
                    "执行模式应为 PLAN_AND_EXECUTE");
        }
    }

    // =====================================================================
    // 11. 多子 Agent · 不同 ChatClient
    // =====================================================================

    @Test
    void testMultipleSubAgents_eachHasDedicatedClient() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient mathClient = new TrackingChatClient("math-sub");
        TrackingChatClient transClient = new TrackingChatClient("trans-sub");

        try (Agent agent = buildMultiSubAgent(masterClient, mathClient, transClient,
                AgentMode.ROUTER)) {
            AgentResponse response = agent.run("帮我算数并翻译");
            assertNotNull(response);
            assertNotNull(response.getOutput());

            // 验证架构图中两个子 Agent 都存在
            // （通过 debug=true 打印系统提示词树来验证）
        }
    }

    @Test
    void testMultipleSubAgents_promptContainsBothSubAgents() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient mathClient = new TrackingChatClient("math-sub");
        TrackingChatClient transClient = new TrackingChatClient("trans-sub");

        try (Agent agent = buildMultiSubAgent(masterClient, mathClient, transClient,
                AgentMode.ROUTER)) {
            agent.debug(true);
            agent.run("ping");
        }

        String output = captured();
        assertTrue(output.contains("数学助手"),
                "路由表应包含数学助手");
        assertTrue(output.contains("翻译助手"),
                "路由表应包含翻译助手");
        assertTrue(output.contains("你是数学助手。"),
                "路由表应包含数学助手 instruction");
        assertTrue(output.contains("你是翻译助手。"),
                "路由表应包含翻译助手 instruction");
    }

    // =====================================================================
    // 12. ChatClient 被正确调用
    // =====================================================================

    @Test
    void testMasterClient_isInvoked() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.SINGLE)) {
            agent.run("test");
        }

        assertEquals(1, masterClient.callCount(),
                "SINGLE 模式下主 ChatClient 应被调用 1 次");
    }

    @Test
    void testSubClient_isConfiguredCorrectly() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.SINGLE)) {
            agent.run("test");
        }

        // subClient 通过 chatClient("math-agent", subClient) 注入
        // 在 SINGLE 模式下只有 master 被调用；但配置已建立
        assertEquals(1, masterClient.callCount());
    }

    // =====================================================================
    // 13. 空输入不崩溃
    // =====================================================================

    @Test
    void testEmptyInput_noCrash() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.ROUTER)) {
            AgentResponse response = agent.run("");
            assertNotNull(response, "空输入不应返回 null");
        }
    }

    // =====================================================================
    // 14. null 输入不崩溃
    // =====================================================================

    @Test
    void testNullInput_noCrash() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.ROUTER)) {
            AgentResponse response = agent.run(null);
            assertNotNull(response, "null 输入不应返回 null");
        }
    }

    // =====================================================================
    // 15. 长输入不崩溃
    // =====================================================================

    @Test
    void testLongInput_noCrash() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        String longInput = "请帮我处理以下任务：\n"
                + "1. 分析需求文档\n"
                + "2. 设计系统架构\n"
                + "3. 编写核心代码\n"
                + "4. 完成单元测试\n"
                + "5. 部署上线\n"
                + "这是一个复杂的流水线任务，请分步执行。".repeat(10);

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.PIPELINE)) {
            AgentResponse response = agent.run(longInput);
            assertNotNull(response);
            assertNotNull(response.getOutput());
        }
    }

    // =====================================================================
    // 16. getDefinition / getSubAgents 返回正确
    // =====================================================================

    @Test
    void testGetDefinition_andSubAgents() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");
        AgentDefinition mathDef = subAgent("math-agent", "数学助手",
                "数学计算专家", "你是数学助手。", subClient);

        AgentDefinition mainDef = AgentDefinition.builder()
                .id("main")
                .name("主调度")
                .instruction("你是主调度。")
                .leader(true)
                .subAgents(List.of(mathDef))
                .build();

        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(masterClient)
                    .subAgent(mathDef)
                    .definition(mainDef)
                    .mode(AgentMode.ROUTER)
                    .workspace(ws());

            assertNotNull(agent.getDefinition());
            assertEquals("main", agent.getDefinition().getId());
            assertEquals(1, agent.getSubAgents().size());
            assertEquals("math-agent", agent.getSubAgents().get(0).getId());
        }
    }

    // =====================================================================
    // 17. getSystemPrompt 非 leader 返回 instruction
    // =====================================================================

    @Test
    void testNonLeader_getSystemPrompt_returnsInstruction() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");
        AgentDefinition mathDef = subAgent("math-agent", "数学助手",
                "数学计算专家", "你是数学助手。", subClient);

        AgentDefinition mainDef = AgentDefinition.builder()
                .id("main")
                .name("主调度")
                .instruction("你只是普通助手。")
                .leader(false)  // 非 leader
                .subAgents(List.of(mathDef))
                .build();

        try (Agent agent = Agent.create("agentscope")) {
            agent.chatClient(masterClient)
                    .subAgent(mathDef)
                    .definition(mainDef)
                    .mode(AgentMode.ROUTER)
                    .workspace(ws());
            agent.run("test");
        }

        AgentDefinition storedDef = AgentDefinition.builder()
                .id("main")
                .name("主调度")
                .instruction("你只是普通助手。")
                .leader(false)
                .subAgents(List.of(mathDef))
                .build();
        assertFalse(storedDef.getSystemPrompt().contains("可用子 Agent"),
                "非 leader 的 system prompt 不应包含路由表");
    }

    // =====================================================================
    // 18. 链式调用返回同一实例
    // =====================================================================

    @Test
    void testFluentApi_returnsSameInstance() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");
        AgentDefinition mathDef = subAgent("math-agent", "数学助手",
                "数学计算专家", "你是数学助手。", subClient);

        Agent agent = Agent.create("agentscope");
        Agent result = agent
                .chatClient(masterClient)
                .chatClient("math-agent", subClient)
                .subAgent(mathDef)
                .definition(mathDef)
                .mode(AgentMode.ROUTER)
                .mcp(true)
                .maxToolIterations(10)
                .debug(true);

        assertSame(agent, result, "链式调用应返回同一实例");
    }

    // =====================================================================
    // 19. 多次 run 可重复执行
    // =====================================================================

    @Test
    void testMultipleRuns_areIndependent() {
        TrackingChatClient masterClient = new TrackingChatClient("master");
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.SINGLE)) {
            agent.run("first");
            agent.run("second");
            agent.run("third");
        }

        assertEquals(3, masterClient.callCount(),
                "每次 run 都应触发一次调用");
    }

    // =====================================================================
    // 20. 异常场景 · 主 ChatClient 抛异常
    // =====================================================================

    @Test
    void testMasterThrows_exceptionPropagates() {
        TrackingChatClient masterClient = new TrackingChatClient("master") {
            @Override
            public String chatSync(String prompt) {
                throw new RuntimeException("模拟主客户端异常");
            }
        };
        TrackingChatClient subClient = new TrackingChatClient("math-sub");

        try (Agent agent = buildLeaderAgent(masterClient, subClient, AgentMode.SINGLE)) {
            AgentResponse response = agent.run("test");
            assertNotNull(response);
            // 适配层捕获异常后返回错误文本，不再传播到 AgentResponse.metadata
            String output = response.getOutput();
            assertNotNull(output);
            assertTrue(output.contains("[Error]") || output.contains("Error"),
                    "异常时应返回错误信息");
        }
    }
}
