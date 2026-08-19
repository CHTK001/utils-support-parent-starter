package com.chua.runtime.plugin.loader;

import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 插件扫描器 — 扫描指定目录下的所有插件 JAR 并加载。
 *
 * <p>支持：</p>
 * <ul>
 *   <li>扫描顶层 JAR 文件</li>
 *   <li>扫描嵌套目录下的 JAR</li>
 *   <li>通过 SPI 配置加载 Plugin 实现</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PluginScanner {

    /**
     * LOG
     */
    private static final Logger LOG = Logger.getLogger(PluginScanner.class.getName());
    /**
     * 插件根目录
     */
    private final Path pluginRoot;

    /**
     * 父类加载器
     */
    private final ClassLoader parentLoader;

    /**
     * 已扫描的插件列表
     */
    private final List<PluginInfo> plugins;

    /**
     * 创建 PluginScanner 实例
     * @param pluginRoot pluginRoot
     */
    public PluginScanner(Path pluginRoot) {
        this(pluginRoot, ClassLoader.getSystemClassLoader());
    }

    /**
     * 创建 PluginScanner 实例
     * @param pluginRoot pluginRoot
     * @param ClassLoader ClassLoader
     */
    public PluginScanner(Path pluginRoot, ClassLoader parentLoader) {
        this.pluginRoot = pluginRoot;
        this.parentLoader = parentLoader;
        this.plugins = new ArrayList<>();
    }

    /**
     * 扫描并加载所有插件。
     *
     * @return 插件信息列表
     * @throws IOException 目录读取异常
     */
    public List<PluginInfo> scan() throws IOException {
        plugins.clear();
        if (!Files.exists(pluginRoot)) {
            Files.createDirectories(pluginRoot);
            LOG.log(Level.INFO, String.format("插件目录不存在，已创建: %s", pluginRoot));
            return plugins;
        }

        Files.list(pluginRoot)
                .filter(Files::isDirectory)
                .filter(this::isPluginDirectory)
                .forEach(this::scanPlugin);

        return plugins;
    }

    /**
     * 加载单个插件。
     *
     * @param pluginDir 插件目录
     */
    private void scanPlugin(Path pluginDir) {
        String pluginName = pluginDir.getFileName().toString();
        LOG.log(Level.INFO, String.format("正在加载插件: %s", pluginName));

        try {
            PluginClassLoader classLoader = new PluginClassLoader(pluginName, pluginDir, parentLoader);
            List<String> pluginClasses = classLoader.scanPlugins();

            if (pluginClasses.isEmpty()) {
                LOG.log(Level.WARNING, String.format("插件[%s] 未找到 SPI 配置", pluginName));
                return;
            }

            for (String className : pluginClasses) {
                className = className.trim();
                if (className.isBlank() || className.startsWith("#")) {
                    continue;
                }
                try {
                    Plugin plugin = classLoader.loadPlugin(className);
                    PluginContext context = new PluginContext(pluginDir);
                    plugin.init(context);
                    PluginInfo info = new PluginInfo(pluginName, plugin, classLoader, context);
                    plugins.add(info);
                    LOG.log(Level.INFO, String.format("插件[%s] 加载成功: %s", pluginName, plugin.name()));
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, String.format("插件[%s] 加载失败: %s", pluginName, className, e));
                }
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, String.format("插件[%s] 目录读取失败", pluginName, e));
        }
    }

    /**
     * 判断目录是否为插件目录。
     *
     * @param dir 目录路径
     * @return 是插件目录返回 true
     */
    private boolean isPluginDirectory(Path dir) {
        String name = dir.getFileName().toString();
        // 排除系统目录
        if (name.startsWith(".") || name.equals("lib") || name.equals("config")) {
            return false;
        }
        // 有 META-INF/services 则认为是插件
        Path spiPath = dir.resolve("META-INF/services/com.chua.runtime.plugin.Plugin");
        if (Files.exists(spiPath)) {
            return true;
        }
        try {
            return Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .findAny()
                    .isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取Plugins */
    public List<PluginInfo> getPlugins() {
        return Collections.unmodifiableList(plugins);
    }

    /**
     * 插件信息。
     *
 * @author CH
     * @since 4.0.0.42
     */
    public record PluginInfo(
            String name,
            Plugin plugin,
            PluginClassLoader classLoader,
            PluginContext context
    ) {
    }
}