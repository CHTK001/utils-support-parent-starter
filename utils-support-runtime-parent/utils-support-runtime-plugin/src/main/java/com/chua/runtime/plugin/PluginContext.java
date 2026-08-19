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

    /**
     * 创建 PluginContext 实例
     * @param pluginDir pluginDir
     */
    public PluginContext(Path pluginDir) {
        this.pluginDir = pluginDir;
        this.properties = new ConcurrentHashMap<>();
    }

    /** 获取PluginDir */
    public Path getPluginDir() {
        return pluginDir;
    }

    /** 获取Properties */
    public Map<String, String> getProperties() {
        return properties;
    }

    /** 设置Property */
    public void setProperty(String key, String value) {
        properties.put(key, value);
    }

    /** 获取Property */
    public String getProperty(String key) {
        return properties.get(key);
    }

    /** 获取Property */
    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    /** 获取RuntimeManager */
    public Object getRuntimeManager() {
        return runtimeManager;
    }

    /** 设置RuntimeManager */
    public void setRuntimeManager(Object runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    /** Info */
    public void info(String message) {
        LOG.log(Level.INFO, String.format("[PluginContext] %s", message));
    }

    /** 警告 */
    public void warn(String message) {
        LOG.log(Level.WARNING, String.format("[PluginContext] %s", message));
    }

    /** 记录错误 */
    public void error(String message, Throwable throwable) {
        LOG.log(Level.SEVERE, String.format("[PluginContext] %s", message, throwable));
    }
}