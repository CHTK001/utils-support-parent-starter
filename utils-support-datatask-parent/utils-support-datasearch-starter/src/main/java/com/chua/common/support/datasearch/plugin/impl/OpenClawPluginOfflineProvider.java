package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenClaw 插件本地提供者。
 *
 * <p>OpenClaw 插件通过 {@code openclaw plugins install/enable} CLI 管理，
 * 插件源码/链接位于 {@code ~/.openclaw} 或活动工作区下的 {@code plugins} 目录，
 * 每个插件目录含 {@code plugin.json}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("openclaw")
public class OpenClawPluginOfflineProvider extends AbstractLocalPluginOfflineProvider {

    @Override
    protected List<Path> pluginRoots() {
        List<Path> roots = new ArrayList<>();
        String workspace = System.getenv("TOKENTRACKER_OPENCLAW_WORKSPACE");
        Path home = USER_HOME.resolve(".openclaw");
        if (workspace != null && !workspace.isBlank()) {
            roots.add(Path.of(workspace).toAbsolutePath().resolve("plugins"));
        }
        Path workspacePlugins = home.resolve("workspace").resolve("plugins");
        if (Files.isDirectory(workspacePlugins)) {
            roots.add(workspacePlugins);
        }
        Path pluginsDir = home.resolve("plugins");
        if (Files.isDirectory(pluginsDir)) {
            roots.add(pluginsDir);
        }
        return roots;
    }

    @Override
    public String name() {
        return "openclaw";
    }
}