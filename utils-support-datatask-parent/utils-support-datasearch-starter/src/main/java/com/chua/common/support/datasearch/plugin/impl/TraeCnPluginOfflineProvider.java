package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;
import java.util.List;

/**
 * Trae-CN 插件本地提供者。
 *
 * <p>扫描 {@code ~/.trae-cn/plugins/<registry>/<plugin>/<version>/}，
 * 插件清单为 {@code .mcp.json}（对齐 {@code AgentEditorProvider.scanTraeCnMcpServers}）。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("trae-cn")
public class TraeCnPluginOfflineProvider extends AbstractLocalPluginOfflineProvider {

    /**
     * Trae-CN 插件根目录。
    */
    private static final Path PLUGINS_DIR = USER_HOME.resolve(".trae-cn").resolve("plugins");

    @Override
    protected List<Path> pluginRoots() {
        return List.of(PLUGINS_DIR);
    }

    @Override
    protected String manifestFile() {
        return ".mcp.json";
    }

    @Override
    public String name() {
        return "trae-cn";
    }
}