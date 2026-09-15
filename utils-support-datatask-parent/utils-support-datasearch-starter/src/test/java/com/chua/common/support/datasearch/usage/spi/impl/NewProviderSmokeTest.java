package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.datasearch.plugin.spi.PluginOfflineProvider;
import com.chua.common.support.datasearch.plugin.spi.PluginOnlineProvider;
import com.chua.common.support.datasearch.skill.spi.SkillOfflineProvider;
import com.chua.common.support.datasearch.skill.spi.SkillOnlineProvider;
import com.chua.common.support.datasearch.usage.spi.UsageParser;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;

/**
 * 新增 provider SPI 注册冒烟验证。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.common.support.datasearch.usage.spi.impl.NewProviderSmokeTest}
 * </pre>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class NewProviderSmokeTest {

    private static int failureCount = 0;
    private static int passCount = 0;

    /**
     * 验证入口。
     *
     * @param args 命令行参数（忽略）
     */
    public static void main(String[] args) {
        // UsageParser 新实现
        String[] usage = {
            "acode", "claude-science", "every-code", "kimi-code", "pi", "reasonix", "qoder-cn", "dsh"
        };
        for (String key : usage) {
            checkProvider(UsageParser.class, key, "UsageParser");
        }

        // SkillOfflineProvider 新实现（第一批）
        String[] offline = {
            "grok", "opencode", "openclaw", "hermes", "antigravity", "acode", "agents"
        };
        for (String key : offline) {
            checkProvider(SkillOfflineProvider.class, key, "SkillOfflineProvider");
        }

        // SkillOfflineProvider 补齐（第二批）
        String[] offline2 = {
            "goose", "kilo", "kimi", "mimo", "zcode", "pi", "prime-agent",
            "qoder", "qwen", "zed"
        };
        for (String key : offline2) {
            checkProvider(SkillOfflineProvider.class, key, "SkillOfflineProvider");
        }

        // SkillOfflineProvider 补齐（第三批）
        String[] offline3 = {
            "anythingllm", "atomcode", "command-code", "copilot-cli", "droid", "dsh",
            "joycode", "kimi-code", "lmstudio", "omp", "reasonix", "unsloth", "workbuddy"
        };
        for (String key : offline3) {
            checkProvider(SkillOfflineProvider.class, key, "SkillOfflineProvider");
        }

        // SkillOnlineProvider 新实现
        checkProvider(SkillOnlineProvider.class, "skills-sh", "SkillOnlineProvider");
        checkProvider(SkillOnlineProvider.class, "github", "SkillOnlineProvider");
        checkProvider(SkillOnlineProvider.class, "clawhub", "SkillOnlineProvider");

        // McpProvider 新实现
        checkProvider(McpProvider.class, "skills-sh", "McpProvider");
        checkProvider(McpProvider.class, "github", "McpProvider");
        checkProvider(McpProvider.class, "agent-browser", "McpProvider");
        checkProvider(McpProvider.class, "playwright", "McpProvider");
        checkProvider(McpProvider.class, "puppeteer", "McpProvider");
        checkProvider(McpProvider.class, "context7", "McpProvider");
        checkProvider(McpProvider.class, "firecrawl", "McpProvider");
        checkProvider(McpProvider.class, "fetch", "McpProvider");
        checkProvider(McpProvider.class, "memory", "McpProvider");
        checkProvider(McpProvider.class, "sequential-thinking", "McpProvider");
        checkProvider(McpProvider.class, "git", "McpProvider");
        checkProvider(McpProvider.class, "sqlite", "McpProvider");
        checkProvider(McpProvider.class, "brave-search", "McpProvider");

        // PluginOfflineProvider 新实现
        String[] pluginKeys = { "claude", "trae-cn", "trae", "vscode", "cursor", "windsurf", "cline", "roo-code", "kilo-code", "openclaw" };
        for (String key : pluginKeys) {
            checkProvider(PluginOfflineProvider.class, key, "PluginOfflineProvider");
        }

        // PluginOnlineProvider 新实现
        String[] onlinePluginKeys = { "open-vsx", "vscode-marketplace", "clawhub-plugins", "skillhub-plugins" };
        for (String key : onlinePluginKeys) {
            checkProvider(PluginOnlineProvider.class, key, "PluginOnlineProvider");
        }

        // 全量统计
        Map<String, UsageParser> usages = ServiceProvider.of(UsageParser.class).list();
        Map<String, SkillOfflineProvider> skills = ServiceProvider.of(SkillOfflineProvider.class).list();
        Map<String, SkillOnlineProvider> onlineSkills = ServiceProvider.of(SkillOnlineProvider.class).list();
        Map<String, McpProvider> mcps = ServiceProvider.of(McpProvider.class).list();
        Map<String, PluginOfflineProvider> plugins = ServiceProvider.of(PluginOfflineProvider.class).list();
        Map<String, PluginOnlineProvider> onlinePluginMap = ServiceProvider.of(PluginOnlineProvider.class).list();
        System.out.println("UsageParser total: " + (usages == null ? 0 : usages.size()));
        System.out.println("SkillOfflineProvider total: " + (skills == null ? 0 : skills.size()));
        System.out.println("SkillOnlineProvider total: " + (onlineSkills == null ? 0 : onlineSkills.size()));
        System.out.println("McpProvider total: " + (mcps == null ? 0 : mcps.size()));
        System.out.println("PluginOfflineProvider total: " + (plugins == null ? 0 : plugins.size()));
        System.out.println("PluginOnlineProvider total: " + (onlinePluginMap == null ? 0 : onlinePluginMap.size()));

        System.out.println("PASS " + passCount + " checks, " + failureCount + " failures");
        if (failureCount > 0) {
            System.exit(1);
        }
    }

    private static <T> void checkProvider(Class<T> spiType, String key, String label) {
        if (ServiceProvider.of(spiType).getExtension(key) == null) {
            System.err.println("FAIL " + label + " missing: " + key);
            failureCount++;
        } else {
            System.out.println("ok " + label + ": " + key);
            passCount++;
        }
    }

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