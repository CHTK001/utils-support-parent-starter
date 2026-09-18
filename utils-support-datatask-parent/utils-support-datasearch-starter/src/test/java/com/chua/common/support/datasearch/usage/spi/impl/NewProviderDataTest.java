package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.datasearch.plugin.model.PluginDefinition;
import com.chua.common.support.datasearch.plugin.spi.PluginOfflineProvider;
import com.chua.common.support.datasearch.plugin.spi.PluginOnlineProvider;
import com.chua.common.support.datasearch.skill.spi.SkillOfflineProvider;
import com.chua.common.support.datasearch.usage.spi.UsageParser;
import com.chua.common.support.datasearch.video.model.VipParseResult;
import com.chua.common.support.datasearch.video.spi.VipParseService;
import com.chua.common.support.spi.ServiceProvider;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 新增 provider 关键字段数据完整性验收。
 *
 * <p>hermetic 模式通过子进程注入临时夹具与环境变量，逐 provider 端到端验收；
 * 真实数据模式对本机真实目录做关键字段不变量验收（无数据时自动跳过）。
 *
 * @author CH
 * @since 4.0.0.45
 */
public final class NewProviderDataTest {

    private static int failureCount = 0;
    private static int passCount = 0;

    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     */
    public static void main(String[] args) {
        if (args.length >= 3 && "verify".equals(args[0])) {
            int code = verifyChild(args[1], Paths.get(args[2]));
            System.exit(code);
        }
        runHermeticPhase();
        runRealDataPhase();
        System.out.println("PASS " + passCount + " checks, " + failureCount + " failures");
        if (failureCount > 0) {
            System.exit(1);
        }
    }

    // ==================== hermetic phase ====================

    /**
     * 运行HermeticPhase。
     */
    private static void runHermeticPhase() {
        hermeticUsage("acode", NewProviderDataTest::writeCodexForkFixture, Map.of("TOKENTRACKER_ACODE_HOME", ""));
        hermeticUsage("every-code", NewProviderDataTest::writeCodexForkFixture, Map.of("CODE_HOME", ""));
        hermeticUsage("claude-science", NewProviderDataTest::writeClaudeScienceFixture, Map.of("CLAUDE_SCIENCE_DB_PATH", "db"));
        hermeticUsage("kimi-code", NewProviderDataTest::writeKimiCodeFixture, Map.of("KIMI_CODE_HOME", ""));
        hermeticUsage("pi", NewProviderDataTest::writePiFixture, Map.of("TOKENTRACKER_PI_AGENT_DIR", ""));
        hermeticUsage("reasonix", NewProviderDataTest::writeReasonixFixture, Map.of("REASONIX_STATE_HOME", ""));
        hermeticUsage("qoder-cn", NewProviderDataTest::writeQoderCnFixture, Map.of("QODER_CN_PROJECTS_DIR", ""));
        hermeticUsage("dsh", NewProviderDataTest::writeDshFixture, Map.of("DSH_HOME", ""));
        for (String key : new String[]{
                "grok", "opencode", "openclaw", "hermes", "antigravity", "acode", "agents"}) {
            hermeticSkill(key);
        }
        for (String key : new String[]{
                "goose", "kilo", "kimi", "mimo", "zcode", "pi", "prime-agent", "qoder", "qwen", "zed"}) {
            hermeticSkill(key);
        }
        for (String key : new String[]{
                "anythingllm", "atomcode", "command-code", "copilot-cli", "droid", "dsh",
                "joycode", "kimi-code", "lmstudio", "omp", "reasonix", "unsloth", "workbuddy"}) {
            hermeticSkill(key);
        }
        for (String key : new String[]{
                "claude", "trae-cn", "vscode", "cursor", "windsurf", "cline", "roo-code", "kilo-code", "openclaw"}) {
            hermeticPlugin(key);
        }
        verifyMcpProviders();
        verifyVipParsers();
    }

    /**
     * hermeticUsage。
     *
     * @param key 键，不允许为 null
     * @param writer 方法入参 writer
     * @param envSuffix 环境后缀，不允许为 null
     */
    private static void hermeticUsage(String key, FixtureWriter writer, Map<String, String> envSuffix) {
        try {
            Path home = Files.createTempDirectory("newpro-" + key + "-");
            Map<String, String> env = new HashMap<>();
            for (Map.Entry<String, String> e : envSuffix.entrySet()) {
                Path p = e.getValue().isEmpty() ? home : home.resolve(e.getValue());
                env.put(e.getKey(), p.toString());
            }
            writer.write(home, env);
            int code = spawnChild("usage:" + key, home, env);
            check(code == 0, "hermetic[" + key + "] 子进程验收通过（exit=" + code + "）");
        } catch (Exception e) {
            check(false, "hermetic[" + key + "] 阶段异常: " + e);
        }
    }

    /**
     * hermeticSkill。
     *
     * @param key 键，不允许为 null
     */
    private static void hermeticSkill(String key) {
        try {
            Path home = Files.createTempDirectory("newskill-" + key + "-");
            writeSkillFixture(key, home);
            int code = spawnChild("skill:" + key, home, new HashMap<>());
            check(code == 0, "hermetic[" + key + "] 子进程验收通过（exit=" + code + "）");
        } catch (Exception e) {
            check(false, "hermetic[" + key + "] 阶段异常: " + e);
        }
    }

    /**
     * hermeticPlugin。
     *
     * @param key 键，不允许为 null
     */
    private static void hermeticPlugin(String key) {
        try {
            Path home = Files.createTempDirectory("newplugin-" + key + "-");
            writePluginFixture(key, home);
            int code = spawnChild("plugin:" + key, home, new HashMap<>());
            check(code == 0, "hermetic插件[" + key + "] 子进程验收通过（exit=" + code + "）");
        } catch (Exception e) {
            check(false, "hermetic插件[" + key + "] 阶段异常: " + e);
        }
    }

    /**
     * spawn子节点。
     *
     * @param key 键，不允许为 null
     * @param home 方法入参 home
     * @param env 环境，不允许为 null
     * @return 结果数值
     * @throws Exception 当执行过程不满足前置条件时
     */
    private static int spawnChild(String key, Path home, Map<String, String> env) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Duser.home=" + home);
        cmd.add("-cp");
        cmd.add(childClasspath());
        cmd.add(NewProviderDataTest.class.getName());
        cmd.add("verify");
        cmd.add(key);
        cmd.add(home.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> output = process.inputReader().lines().toList();
        int code = process.waitFor();
        output.forEach(line -> System.out.println("  [child:" + key + "] " + line));
        return code;
    }

    /**
     * 验证子节点。
     *
     * @param key 键，不允许为 null
     * @param home 方法入参 home
     * @return 结果数值
     */
    private static int verifyChild(String key, Path home) {
        if (key.startsWith("usage:")) {
            verifyUsageProvider(key.substring("usage:".length()));
            return failureCount > 0 ? 1 : 0;
        }
        if (key.startsWith("skill:")) {
            verifySkillProvider(key.substring("skill:".length()));
            return failureCount > 0 ? 1 : 0;
        }
        if (key.startsWith("plugin:")) {
            verifyPluginProvider(key.substring("plugin:".length()));
            return failureCount > 0 ? 1 : 0;
        }
        switch (key) {
            case "acode", "every-code", "claude-science", "kimi-code", "pi", "reasonix", "qoder-cn", "dsh" ->
                    verifyUsageProvider(key);
            case "grok", "opencode", "openclaw", "hermes", "antigravity", "agents",
                    "goose", "kilo", "kimi", "mimo", "zcode", "prime-agent", "qoder", "qwen", "zed",
                    "anythingllm", "atomcode", "command-code", "copilot-cli", "droid",
                    "joycode", "lmstudio", "omp", "unsloth", "workbuddy" ->
                    verifySkillProvider(key);
            default -> check(false, "未知 provider: " + key);
        }
        System.out.println((failureCount == 0 ? "CHILD_PASS" : "CHILD_FAIL") + " " + passCount + " checks");
        return failureCount > 0 ? 1 : 0;
    }

    /**
     * 验证Usage提供者。
     *
     * @param key 键，不允许为 null
     */
    private static void verifyUsageProvider(String key) {
        UsageParser parser = ServiceProvider.of(UsageParser.class).getExtension(key);
        check(parser != null, key + " SPI 注册存在");
        if (parser == null) {
            return;
        }
        List<AiUsage> records;
        try {
            records = parser.streamAll().collectList().block(Duration.ofMinutes(1));
        } catch (Exception e) {
            check(false, key + " 解析异常: " + e.getMessage());
            return;
        }
        check(records != null && !records.isEmpty(), key + " 解析出记录（" + (records == null ? 0 : records.size()) + " 条）");
        if (records == null || records.isEmpty()) {
            return;
        }

        check(records.stream().noneMatch(r -> r.getProvider() == null || r.getProvider().isBlank()),
                key + " provider 字段有值");
        check(records.stream().noneMatch(r -> r.getModel() == null || r.getModel().isBlank()),
                key + " model 字段有值");
        check(records.stream().noneMatch(r -> r.getRequestId() == null || r.getRequestId().isBlank()),
                key + " requestId 字段有值");
        check(records.stream().noneMatch(r -> r.getStartTime() == null || r.getStartTime() <= 0),
                key + " startTime 字段有值");

        boolean creditBased = "qoder-cn".equals(key);
        if (creditBased) {
            check(records.stream().allMatch(r -> r.getTotalCost() != null && r.getTotalCost().signum() > 0),
                    key + " totalCost 字段有值");
            check(records.stream().noneMatch(r -> r.getCurrency() == null || r.getCurrency().isBlank()),
                    key + " currency 字段有值");
        } else {
            check(records.stream().allMatch(r -> r.getTotalTokens() != null && r.getTotalTokens() > 0),
                    key + " totalTokens > 0");
            check(records.stream().noneMatch(r -> (r.getInputTokens() == null || r.getInputTokens() <= 0)
                    && (r.getOutputTokens() == null || r.getOutputTokens() <= 0)),
                    key + " inputTokens/outputTokens 至少一项 > 0");
        }
    }

    /**
     * 验证Skill提供者。
     *
     * @param key 键，不允许为 null
     */
    private static void verifySkillProvider(String key) {
        SkillOfflineProvider provider = ServiceProvider.of(SkillOfflineProvider.class).getExtension(key);
        check(provider != null, key + " SPI 注册存在");
        if (provider == null) {
            return;
        }
        try { check(provider.isInstalled(), key + " isInstalled() = true"); }
        catch (Exception e) { check(false, key + " isInstalled 异常: " + e.getMessage()); }
        List<SkillDefinition> skills;
        try { skills = provider.listAgentSkills(); }
        catch (Exception e) {
            check(false, key + " listAgentSkills 异常: " + e.getMessage());
            return;
        }
        check(skills != null && !skills.isEmpty(), key + " 扫描出技能（" + (skills == null ? 0 : skills.size()) + " 个）");
        if (skills == null || skills.isEmpty()) {
            return;
        }
        check(skills.stream().noneMatch(s -> s.getName() == null || s.getName().isBlank()), key + " 技能 name 有值");
        check(skills.stream().noneMatch(s -> s.getDescription() == null || s.getDescription().isBlank()), key + " 技能 description 有值");
        check(skills.stream().noneMatch(s -> s.getHandler() == null), key + " 技能 handler 有值");
    }

    /**
     * 验证Plugin提供者。
     *
     * @param key 键，不允许为 null
     */
    private static void verifyPluginProvider(String key) {
        PluginOfflineProvider provider = ServiceProvider.of(PluginOfflineProvider.class).getExtension(key);
        check(provider != null, key + " PluginOfflineProvider 注册存在");
        if (provider == null) {
            return;
        }
        List<PluginDefinition> plugins;
        try { plugins = provider.listPlugins(); }
        catch (Exception e) {
            check(false, key + " listPlugins 异常: " + e.getMessage());
            return;
        }
        check(plugins != null && !plugins.isEmpty(), key + " 扫描出插件（" + (plugins == null ? 0 : plugins.size()) + " 个）");
        if (plugins == null || plugins.isEmpty()) {
            return;
        }
        check(plugins.stream().noneMatch(p -> p.getId() == null || p.getId().isBlank()), key + " 插件 id 有值");
        check(plugins.stream().noneMatch(p -> p.getName() == null || p.getName().isBlank()), key + " 插件 name 有值");
        check(plugins.stream().noneMatch(p -> p.getSource() == null || p.getSource().isBlank()), key + " 插件 source 有值");
    }

    /**
     * 验证McpProviders。
     */
    private static void verifyMcpProviders() {
        String[] keys = {"agent-browser", "playwright", "puppeteer", "context7", "firecrawl",
                "fetch", "memory", "sequential-thinking", "git", "sqlite", "brave-search",
                "skills-sh", "github", "datasearch"};
        for (String key : keys) {
            McpProvider provider = ServiceProvider.of(McpProvider.class).getExtension(key);
            check(provider != null, "McpProvider[" + key + "] 注册存在");
            if (provider == null) {
                continue;
            }
            McpClient client = provider.create();
            check(client != null, "McpProvider[" + key + "] create() 非空");
            if (client == null) {
                continue;
            }
            try {
                client.init();
            } catch (Exception e) {
                check(false, "McpProvider[" + key + "] init 异常: " + e.getMessage());
                return;
            }
            check(client.isInitialized(), "McpProvider[" + key + "] isInitialized()=true");
            List<McpToolDescriptor> tools;
            try { tools = client.listTools(); }
            catch (Exception e) {
                check(false, "McpProvider[" + key + "] listTools 异常: " + e.getMessage());
                continue;
            }
            check(tools != null && !tools.isEmpty(), "McpProvider[" + key + "] toolDescriptors 非空（" + (tools == null ? 0 : tools.size()) + " 个）");
            if (tools != null && !tools.isEmpty()) {
                check(tools.stream().noneMatch(t -> t.getName() == null || t.getName().isBlank()),
                        "McpProvider[" + key + "] 工具 name 有值");
                check(tools.stream().noneMatch(t -> t.getDescription() == null || t.getDescription().isBlank()),
                        "McpProvider[" + key + "] 工具 description 有值");
            }
        }
    }

    // ==================== real-data phase ====================

    /**
     * 运行Real数据Phase。
     */
    private static void runRealDataPhase() {
        for (String key : new String[]{
                "claude-science", "kimi-code", "pi", "reasonix", "qoder-cn", "dsh"}) {
            verifyRealUsage(key);
        }
        for (String key : new String[]{
                "grok", "opencode", "openclaw", "hermes", "antigravity", "acode", "agents"}) {
            verifyRealSkill(key);
        }
        for (String key : new String[]{
                "claude", "trae-cn", "vscode", "cursor", "windsurf", "cline", "roo-code", "kilo-code", "openclaw"}) {
            verifyRealPlugin(key);
        }
        verifyMcpProviders();
        verifyVipParsers();
    }

    /**
     * VIP 解析体系 hermetic 验收：验证 VipParseService 来源路由、
     * DirectVipParser 直链正例/非直链反例、JsonApiVipParser 路由可达。
     */
    private static void verifyVipParsers() {
        VipParseService svc = new VipParseService();

        // 直链正例：.mp4 / .m3u8 / .ts 各一条
        VipParseResult mp4 = svc.parse("direct", "https://cdn.example.com/v/2026/sample.mp4");
        check(mp4.isSuccess(), "vip[direct] .mp4 直链解析成功");
        check(mp4.getPlayAddresses() != null && mp4.getPlayAddresses().size() == 1,
                "vip[direct] 返回 1 条播放地址");
        if (mp4.getPlayAddresses() != null && !mp4.getPlayAddresses().isEmpty()) {
            var addr = mp4.getPlayAddresses().get(0);
            check("direct".equals(addr.getVideoPlayAddressCode()), "vip[direct] 播放地址 code=direct");
            check(addr.getVideoPlayAddressChannels() != null && addr.getVideoPlayAddressChannels().size() == 1,
                    "vip[direct] 渠道列表含 1 条线路");
            check("https://cdn.example.com/v/2026/sample.mp4".equals(
                    addr.getVideoPlayAddressChannels().get(0).getVideoPlayAddressUrl()),
                    "vip[direct] 线路 URL 回传正确");
        }

        VipParseResult m3u8 = svc.parse("direct", "https://hls.example.com/live/index.m3u8");
        check(m3u8.isSuccess(), "vip[direct] .m3u8 直链解析成功");

        VipParseResult ts = svc.parse("direct", "https://hls.example.com/seg/001.ts");
        check(ts.isSuccess(), "vip[direct] .ts 直链解析成功");

        // 非直链反例：视频页 URL 不应被 direct 解析器接受
        VipParseResult page = svc.parse("direct", "https://www.bilibili.com/video/BV1xx411c7mD");
        check(!page.isSuccess(), "vip[direct] 视频页 URL 解析失败（非直链）");
        check(page.getErrorMessage() != null && page.getErrorMessage().contains("非直链"),
                "vip[direct] 失败信息说明非直链格式");

        // 带 query 的直链：.mp4? 仍识别
        VipParseResult query = svc.parse("direct", "https://cdn.example.com/v/a.mp4?sign=abc&expires=1700");
        check(query.isSuccess(), "vip[direct] .mp4?query 直链解析成功");

        // 空 URL 边界
        VipParseResult empty = svc.parse("direct", "");
        check(!empty.isSuccess(), "vip[direct] 空 URL 解析失败");

        // 未知来源：无匹配解析器
        VipParseResult unknown = svc.parse("no-such-source", "https://cdn.example.com/a.mp4");
        check(!unknown.isSuccess(), "vip[unknown] 未知来源解析失败");
        check(unknown.getErrorMessage() != null && unknown.getErrorMessage().contains("无匹配来源"),
                "vip[unknown] 失败信息说明无匹配来源");

        // JsonApiVipParser 注册可达（仅验证路由，不触发真实 HTTP 网络调用）
        com.chua.common.support.datasearch.video.spi.VipParser jsonParser =
                com.chua.common.support.spi.ServiceProvider
                        .of(com.chua.common.support.datasearch.video.spi.VipParser.class)
                        .getExtension("json");
        check(jsonParser != null, "vip[json] JsonApiVipParser SPI 注册存在");
        if (jsonParser != null) {
            check(jsonParser.supports("json"), "vip[json] supports(json)=true");
            check(jsonParser.supports("generic"), "vip[json] supports(generic)=true");
            check(!jsonParser.supports("direct"), "vip[json] supports(direct)=false");
        }
    }

    /**
     * 验证RealPlugin。
     *
     * @param key 键，不允许为 null
     */
    private static void verifyRealPlugin(String key) {
        PluginOfflineProvider provider = ServiceProvider.of(PluginOfflineProvider.class).getExtension(key);
        if (provider == null) {
            return;
        }
        try {
            List<PluginDefinition> plugins = provider.listPlugins();
            if (plugins == null || plugins.isEmpty()) {
                System.out.println("SKIP 真实数据[" + key + "]：本机无插件");
                return;
            }
            long bad = plugins.stream().filter(p -> p.getName() == null || p.getName().isBlank()
                    || p.getSource() == null || p.getSource().isBlank()).count();
            check(bad == 0, "真实数据[" + key + "] 插件关键字段有值（共 " + plugins.size() + " 个，异常 " + bad + " 个）");
        } catch (Exception e) {
            System.out.println("SKIP 真实数据[" + key + "]：扫描异常 " + e.getMessage());
        }
    }

    /**
     * 验证RealUsage。
     *
     * @param key 键，不允许为 null
     */
    private static void verifyRealUsage(String key) {
        UsageParser parser = ServiceProvider.of(UsageParser.class).getExtension(key);
        if (parser == null) {
            return;
        }
        List<AiUsage> records;
        try { records = parser.streamAll().collectList().block(Duration.ofMinutes(2)); }
        catch (Exception e) {
            System.out.println("SKIP 真实数据[" + key + "]：解析异常 " + e.getMessage());
            return;
        }
        if (records == null || records.isEmpty()) {
            System.out.println("SKIP 真实数据[" + key + "]：本机无数据");
            return;
        }
        boolean creditBased = "qoder-cn".equals(key);
        long bad;
        if (creditBased) {
            bad = records.stream().filter(r -> r.getProvider() == null || r.getProvider().isBlank()
                    || r.getModel() == null || r.getModel().isBlank()
                    || r.getTotalCost() == null || r.getTotalCost().signum() <= 0).count();
        } else {
            bad = records.stream().filter(r -> r.getProvider() == null || r.getProvider().isBlank()
                    || r.getModel() == null || r.getModel().isBlank()
                    || r.getTotalTokens() == null || r.getTotalTokens() <= 0).count();
        }
        check(bad == 0, "真实数据[" + key + "] 关键字段有值（共 " + records.size() + " 条，异常 " + bad + " 条）");
    }

    /**
     * 验证RealSkill。
     *
     * @param key 键，不允许为 null
     */
    private static void verifyRealSkill(String key) {
        SkillOfflineProvider provider = ServiceProvider.of(SkillOfflineProvider.class).getExtension(key);
        if (provider == null) {
            return;
        }
        try {
            if (!provider.isInstalled()) {
                System.out.println("SKIP 真实数据[" + key + "]：本机未安装");
                return;
            }
            List<SkillDefinition> skills = provider.listAgentSkills();
            if (skills == null || skills.isEmpty()) {
                System.out.println("SKIP 真实数据[" + key + "]：本机无技能");
                return;
            }
            long bad = skills.stream().filter(s -> s.getName() == null || s.getName().isBlank()
                    || s.getDescription() == null || s.getDescription().isBlank()).count();
            check(bad == 0, "真实数据[" + key + "] 技能关键字段有值（共 " + skills.size() + " 个，异常 " + bad + " 个）");
        } catch (Exception e) {
            System.out.println("SKIP 真实数据[" + key + "]：扫描异常 " + e.getMessage());
        }
    }

    // ==================== child classpath ====================

    /**
     * 子节点Classpath。
     *
     * @return 结果字符串
     */
    private static String childClasspath() {
        java.net.URL location = NewProviderDataTest.class.getProtectionDomain().getCodeSource().getLocation();
        Path testClasses;
        try { testClasses = Path.of(location.toURI()); }
        catch (java.net.URISyntaxException e) { testClasses = Path.of(location.getPath()); }
        Path classes = testClasses.getParent() == null ? testClasses : testClasses.resolveSibling("classes");
        StringBuilder cp = new StringBuilder();
        if (Files.isDirectory(classes)) { cp.append(classes).append(File.pathSeparator); }
        if (Files.isDirectory(testClasses)) { cp.append(testClasses).append(File.pathSeparator); }
        Path cpFile = Paths.get(System.getProperty("java.io.tmpdir"), "ds-cp.txt");
        if (Files.exists(cpFile)) {
            try {
                String deps = Files.readString(cpFile).trim();
                if (!deps.isEmpty()) {
                    cp.append(deps);
                }
            }
            catch (IOException ignored) {}
        } else { cp.append(System.getProperty("java.class.path")); }
        return cp.toString();
    }

    // ==================== fixtures ====================

    /**
     * 写入ClaudeScienceFixture。
     *
     * @param home 方法入参 home
     * @param env 环境，不允许为 null
     * @throws SQLException 当执行过程不满足前置条件时
     * @throws IOException 当执行过程不满足前置条件时
     */
    private static void writeClaudeScienceFixture(Path home, Map<String, String> env) throws SQLException, IOException {
        Path db = Paths.get(env.get("CLAUDE_SCIENCE_DB_PATH"));
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE frames (id TEXT PRIMARY KEY, parent_frame_id TEXT, model TEXT, "
                    + "input_tokens INTEGER, output_tokens INTEGER, cache_read_tokens INTEGER, "
                    + "cache_write_tokens INTEGER, aux_input_tokens INTEGER, aux_output_tokens INTEGER, "
                    + "aux_cache_read_tokens INTEGER, aux_cache_write_tokens INTEGER, "
                    + "created_at INTEGER, updated_at INTEGER, completed_at INTEGER)");
            stmt.execute("INSERT INTO frames VALUES ('frame-1', NULL, 'claude-sonnet-4', "
                    + "200, 50, 100, 10, 0, 0, 0, 0, 1700000000000, 1700000001000, 1700000002000)");
            stmt.execute("INSERT INTO frames VALUES ('frame-2', 'frame-1', 'claude-sonnet-4', "
                    + "0, 0, 0, 0, 0, 0, 0, 0, 1700000000000, NULL, NULL)");
        }
    }

    /**
     * 写入Kimi编码Fixture。
     *
     * @param home 方法入参 home
     * @param env 环境，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    private static void writeKimiCodeFixture(Path home, Map<String, String> env) throws IOException {
        Path wire = home.resolve("sessions").resolve("wdhash").resolve("sess-1")
                .resolve("agents").resolve("main").resolve("wire.jsonl");
        Files.createDirectories(wire.getParent());
        Files.writeString(wire, String.join("\n",
                "{\"type\":\"config.update\",\"modelAlias\":\"kimi-code/kimi-k2.6\",\"time\":1700000000000}",
                "{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"step.end\",\"uuid\":\"step-1\","
                        + "\"usage\":{\"input_tokens\":120,\"output_tokens\":45,\"cache_read_input_tokens\":3000,"
                        + "\"cache_creation_input_tokens\":0}},\"time\":1700000001000}",
                ""), StandardCharsets.UTF_8);
    }

    /**
     * 写入PiFixture。
     *
     * @param home 方法入参 home
     * @param env 环境，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    private static void writePiFixture(Path home, Map<String, String> env) throws IOException {
        Path session = home.resolve("agent").resolve("sessions").resolve("cwd").resolve("sess-1.jsonl");
        Files.createDirectories(session.getParent());
        Files.writeString(session, "{\"type\":\"message\",\"id\":\"msg-1\",\"message\":{\"role\":\"assistant\","
                + "\"model\":\"gpt-5\",\"provider\":\"anthropic\",\"timestamp\":1700000001000,"
                + "\"usage\":{\"input\":120,\"output\":45,\"cacheRead\":3000,\"cacheWrite\":0,"
                + "\"reasoningTokens\":0,\"totalTokens\":3165}}}", StandardCharsets.UTF_8);
    }

    /**
     * 写入ReasonixFixture。
     *
     * @param home 方法入参 home
     * @param env 环境，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    private static void writeReasonixFixture(Path home, Map<String, String> env) throws IOException {
        Path project = home.resolve("projects").resolve("proj-1");
        Files.createDirectories(project);
        Files.writeString(project.resolve("sess-1.jsonl.telemetry.json"),
                "{\"usage\":{\"promptTokens\":1200,\"reasoningTokens\":200,\"completionTokens\":800,"
                        + "\"cacheMissTokens\":900,\"cacheHitTokens\":300,\"cacheWriteTokens\":100,\"requestCount\":25}}\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("sess-1.jsonl.meta"),
                "{\"model\":\"deepseek-v3\",\"updated_at\":\"2026-08-20T10:00:00.000Z\"}\n",
                StandardCharsets.UTF_8);
    }

    /**
     * 写入QoderCnFixture。
     *
     * @param home 方法入参 home
     * @param env 环境，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    private static void writeQoderCnFixture(Path home, Map<String, String> env) throws IOException {
        Path session = home.resolve("proj-1").resolve("sess-1.jsonl");
        Files.createDirectories(session.getParent());
        Files.writeString(session, "{\"type\":\"assistant\",\"timestamp\":\"2026-08-24T04:10:54.011Z\","
                + "\"sessionId\":\"s1\",\"message\":{\"id\":\"chatcmpl-1\",\"model\":\"lite\","
                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0,"
                + "\"credits\":0.03285,\"original_credits\":0.03285}}}\n", StandardCharsets.UTF_8);
    }

    /**
     * 写入DshFixture。
     *
     * @param home 方法入参 home
     * @param env 环境，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    private static void writeDshFixture(Path home, Map<String, String> env) throws IOException {
        Path session = home.resolve("sessions").resolve("proj-1").resolve("sess-1").resolve("session.jsonl");
        Files.createDirectories(session.getParent());
        Files.writeString(session, String.join("\n",
                "{\"type\":\"session\",\"id\":\"sess-1\"}",
                "{\"type\":\"request/header\",\"data\":{\"header\":{\"config\":{\"model\":\"deepseek-v4-0324\"}}}}",
                "{\"type\":\"assistant/message\",\"seq\":1,\"time\":1700000001000,"
                        + "\"data\":{\"message\":{\"source\":{\"model\":\"deepseek-v4-0324\"}},"
                        + "\"usage\":{\"inputTokens\":1200,\"outputTokens\":800,\"cacheReadTokens\":0,"
                        + "\"cacheWriteTokens\":0,\"reasoningTokens\":200}}}",
                ""), StandardCharsets.UTF_8);
    }

    /**
     * 为 acode / every-code（Codex fork）写入 rollout JSONL 夹具。
     * 格式：每行 JSON 对象，payload.type=token_count 携带 total_token_usage。
     *
     * @param home home dir
     * @param env env map
     * @throws IOException IOException
     */
    private static void writeCodexForkFixture(Path home, Map<String, String> env) throws IOException {
        Path sessionsDir = home.resolve("sessions").resolve("sess-1");
        Files.createDirectories(sessionsDir);
        String jsonLine = "{\"type\":\"response_item\",\"event_id\":\"ev-1\","
                + "\"timestamp\":1787546545,"
                + "\"payload\":{\"type\":\"token_count\","
                + "\"model\":\"gpt-5\","
                + "\"info\":{\"total_token_usage\":{"
                + "\"input_tokens\":200,\"cached_input_tokens\":50,"
                + "\"cache_creation_input_tokens\":10,\"output_tokens\":45,"
                + "\"reasoning_output_tokens\":0,\"total_tokens\":205}}}}";
        Files.writeString(sessionsDir.resolve("rollout.jsonl"), jsonLine + "\n", StandardCharsets.UTF_8);
    }

    /**
     * 写入SkillFixture。
     *
     * @param key 键，不允许为 null
     * @param home 方法入参 home
     * @throws IOException 当执行过程不满足前置条件时
     */
    private static void writeSkillFixture(String key, Path home) throws IOException {
        Path configDir = switch (key) {
            case "grok" -> home.resolve(".grok");
            case "opencode" -> home.resolve(".config").resolve("opencode");
            case "openclaw" -> home.resolve(".openclaw").resolve("workspace");
            case "hermes" -> home.resolve(".hermes");
            case "antigravity" -> home.resolve(".antigravity");
            case "acode" -> home.resolve(".acode");
            case "agents" -> home.resolve(".agents");
            case "goose" -> home.resolve(".goose");
            case "kilo" -> home.resolve(".config").resolve("kilo");
            case "kimi" -> home.resolve(".kimi");
            case "mimo" -> home.resolve(".mimocode");
            case "zcode" -> home.resolve(".zcode");
            case "pi" -> home.resolve(".pi").resolve("agent");
            case "prime-agent" -> home.resolve(".prime").resolve("agent");
            case "qoder" -> home.resolve(".qoder");
            case "qwen" -> home.resolve(".qwen");
            case "zed" -> home.resolve(".local").resolve("share").resolve("zed").resolve("agent");
            case "anythingllm" -> home.resolve(".config").resolve("anythingllm");
            case "atomcode" -> home.resolve(".atomcode");
            case "command-code" -> home.resolve(".commandcode");
            case "copilot-cli" -> home.resolve(".copilot");
            case "droid" -> home.resolve(".factory");
            case "dsh" -> home.resolve(".dsh");
            case "joycode" -> home.resolve(".joycode");
            case "kimi-code" -> home.resolve(".kimi-code");
            case "lmstudio" -> home.resolve(".lmstudio");
            case "omp" -> home.resolve(".omp");
            case "reasonix" -> home.resolve(".reasonix");
            case "unsloth" -> home.resolve(".unsloth");
            case "workbuddy" -> home.resolve(".workbuddy");
            default -> home;
        };
        Path skillsDir = configDir.resolve("skills").resolve("demo-skill");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), String.join("\n",
                "---",
                "name: demo-skill",
                "description: 测试技能",
                "---",
                "# Demo",
                "这是一个测试技能。"), StandardCharsets.UTF_8);
        if ("antigravity".equals(key)) {
            Path ideSkills = home.resolve(".antigravity-ide").resolve("skills").resolve("demo-ide");
            Files.createDirectories(ideSkills);
            Files.writeString(ideSkills.resolve("SKILL.md"), String.join("\n",
                    "---",
                    "name: demo-ide",
                    "description: IDE 测试技能",
                    "---"), StandardCharsets.UTF_8);
        }
    }

    /**
     * 写入PluginFixture。
     *
     * @param key 键，不允许为 null
     * @param home 方法入参 home
     * @throws IOException 当执行过程不满足前置条件时
     */
    private static void writePluginFixture(String key, Path home) throws IOException {
        switch (key) {
            case "claude" -> {
                Path dir = home.resolve(".claude").resolve("plugins").resolve("cache")
                        .resolve("anthropic").resolve("demo-plugin").resolve("1.0")
                        .resolve(".claude-plugin");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("plugin.json"),
                        "{\"name\":\"demo-plugin\",\"description\":\"测试插件\",\"version\":\"1.0\"}",
                        StandardCharsets.UTF_8);
            }
            case "trae-cn" -> {
                Path dir = home.resolve(".trae-cn").resolve("plugins").resolve("myregistry")
                        .resolve("demo-plugin").resolve("1.0");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(".mcp.json"),
                        "{\"mcpServers\":{}}", StandardCharsets.UTF_8);
            }
            case "vscode", "cursor", "windsurf", "trae", "cline", "roo-code", "kilo-code" -> {
                Path extDir = switch (key) {
                    case "vscode" -> home.resolve(".vscode").resolve("extensions");
                    case "cursor" -> home.resolve(".cursor").resolve("extensions");
                    case "windsurf" -> home.resolve(".codeium").resolve("windsurf").resolve("extensions");
                    case "trae" -> home.resolve(".trae").resolve("extensions");
                    case "cline" -> home.resolve(".vscode").resolve("extensions");
                    case "roo-code" -> home.resolve(".vscode").resolve("extensions");
                    case "kilo-code" -> home.resolve(".vscode").resolve("extensions");
                    default -> home;
                };
                // VS Code 扩展目录为单层：{publisher}.{name}-{version}/package.json
                Path ext = extDir.resolve("test.publisher.demo-extension-1.0");
                Files.createDirectories(ext);
                Files.writeString(ext.resolve("package.json"),
                        "{\"displayName\":\"Demo Extension\",\"name\":\"demo\",\"description\":\"测试扩展\","
                                + "\"version\":\"1.0\",\"publisher\":\"test.publisher\"}",
                        StandardCharsets.UTF_8);
            }
            case "openclaw" -> {
                // OpenClaw 插件沿用三层结构 {registry}/{plugin}/{version}/plugin.json
                Path dir = home.resolve(".openclaw").resolve("workspace").resolve("plugins")
                        .resolve("myregistry").resolve("demo-plugin").resolve("1.0");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("plugin.json"),
                        "{\"name\":\"demo-plugin\",\"description\":\"测试插件\",\"version\":\"1.0\"}",
                        StandardCharsets.UTF_8);
            }
        }
    }

    private interface FixtureWriter {
        void write(Path home, Map<String, String> env) throws Exception;
    }

    // ==================== assertions ====================

    /**
     * 校验。
     *
     * @param condition condition（布尔开关）
     * @param message 消息，不允许为 null
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("  ok - " + message);
        } else {
            failureCount++;
            System.out.println("  FAIL - " + message);
        }
    }
}