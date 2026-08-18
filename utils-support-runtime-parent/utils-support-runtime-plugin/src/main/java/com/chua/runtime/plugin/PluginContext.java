package com.chua.runtime.plugin;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件上下文 — 插件运行时配置和数据访问入口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PluginContext {

    /**
     * LOG
     */
    private static final Logger LOG = Logger.getLogger(PluginContext.class.getName());
    /**
     * 插件根目录
     */
    private final Path pluginDir;

    /**
     * 配置属性
     */
    private final Map<String, String> properties;

    /**
     * 运行时管理器
     */
    private Object runtimeManager;

    public PluginContext(Path pluginDir) {
        this.pluginDir = pluginDir;
        this.properties = new ConcurrentHashMap<>();
    }

    public Path getPluginDir() {
        return pluginDir;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperty(String key, String value) {
        properties.put(key, value);
    }

    public String getProperty(String key) {
        return properties.get(key);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    public Object getRuntimeManager() {
        return runtimeManager;
    }

    public void setRuntimeManager(Object runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    public void info(String message) {
        LOG.log(Level.INFO, String.format("[PluginContext] %s", message));
    }

    public void warn(String message) {
        LOG.log(Level.WARNING, String.format("[PluginContext] %s", message));
    }

    public void error(String message, Throwable throwable) {
        LOG.log(Level.SEVERE, String.format("[PluginContext] %s", message, throwable));
    }
}