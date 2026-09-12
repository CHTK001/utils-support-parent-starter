package com.chua.runtime.plugin.loader;

import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.plugin.loader.PluginScanner.PluginInfo;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
* 插件管理器 — 组合扫描器和注册表，提供统一的插件生命周期管理。
*
* @author CH
* @since 4.0.0.42
 */
public class PluginManager {

    /**
    * 日志
     */
    private static final Logger LOG = Logger.getLogger(PluginManager.class.getName());
    /**
    * 插件根目录
     */
    private final Path pluginRoot;

    /**
    * 插件注册表
     */
    private final PluginRegistry registry;

    /**
    * 插件扫描器
     */
    private final PluginScanner scanner;

    /**
    * 运行时管理器引用
     */
    private Object runtimeManager;

    /**
    * 创建 plugin管理器 实例
    * @param pluginRoot plugin根
     */
    public PluginManager(Path pluginRoot) {
        this(pluginRoot, ClassLoader.getSystemClassLoader());
    }

    /**
    * 创建 plugin管理器 实例
    * @param pluginRoot plugin根
    * @param parentLoader 类加载
    * @param parentLoader 父加载
     */
    public PluginManager(Path pluginRoot, ClassLoader parentLoader) {
        this.pluginRoot = pluginRoot;
        this.registry = new PluginRegistry();
        this.scanner = new PluginScanner(pluginRoot, parentLoader);
    }

    /**
    * 扫描并注册所有插件。
    *
    * @return 加载的插件数量
     */
    public int load() {
        try {
            List<PluginInfo> plugins = scanner.scan();
            for (PluginInfo info : plugins) {
                registry.register(info);
            }
            LOG.log(Level.INFO, String.format("加载了 %s 个插件", registry.size()));
            return registry.size();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, String.format("插件加载失败", e));
            return 0;
        }
    }

    /**
    * 启动所有已注册的插件。
     */
    public void start() {
        registry.startAll();
    }

    /**
    * 停止所有插件。
     */
    public void stop() {
        registry.stopAll();
    }

    /**
    * 按名称获取插件。
    *
    * @param name 插件名称
    * @return 插件实例
     */
    public Plugin get(String name) {
        return registry.get(name);
    }

    /**
    * 获取插件信息。
    *
    * @param name 插件名称
    * @return 插件信息
     */
    public PluginInfo getInfo(String name) {
        return registry.getInfo(name);
    }

    /**
    * 所有插件名称。
    *
    * @return 名称列表
     */
    public List<String> getNames() {
        return registry.getNames();
    }

    /**
    * 所有运行中的插件。
    *
    * @return 运行中的插件列表
     */
    public List<PluginInfo> getRunning() {
        return registry.getRunning();
    }

    /**
    * 设置运行时管理器引用。
    *
    * @param runtimeManager 运行时管理器
     */
    public void setRuntimeManager(Object runtimeManager) {
        this.runtimeManager = runtimeManager;
        for (PluginInfo info : registry.list()) {
            info.context().setRuntimeManager(runtimeManager);
        }
    }

    /**
    * 获取plugin根
    *
    * @return 获取plugin根的结果
     */
    public Path getPluginRoot() {
        return pluginRoot;
    }

    /**
    * 获取Registry
    *
    * @return 获取registry的结果
     */
    public PluginRegistry getRegistry() {
        return registry;
    }

    /**
    * 获取大小
    *
    * @return 大小的结果
     */
    public int size() {
        return registry.size();
    }
}