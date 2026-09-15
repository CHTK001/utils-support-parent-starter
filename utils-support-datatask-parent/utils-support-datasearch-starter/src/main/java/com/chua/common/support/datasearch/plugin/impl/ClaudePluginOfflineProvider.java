package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;
import java.util.List;

/**
 * Claude 插件（Claude Code Plugins）本地提供者。
 *
 * <p>扫描 {@code ~/.claude/plugins/cache/<owner>/<plugin>/<version>/}，
 * 每个插件的 {@code .claude-plugin/plugin.json} 声明 skills/agents/mcpServers。
 * 与 TokenTracker {@code listEnabledPlugins} 同源目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("claude")
public class ClaudePluginOfflineProvider extends AbstractLocalPluginOfflineProvider {

    /** 插件缓存根目录。 */
    private static final Path CACHE_DIR = USER_HOME.resolve(".claude").resolve("plugins").resolve("cache");

    @Override
    protected List<Path> pluginRoots() {
        return List.of(CACHE_DIR);
    }

    @Override
    protected String manifestFile() {
        return ".claude-plugin/plugin.json";
    }

    @Override
    public String name() {
        return "claude";
    }
}