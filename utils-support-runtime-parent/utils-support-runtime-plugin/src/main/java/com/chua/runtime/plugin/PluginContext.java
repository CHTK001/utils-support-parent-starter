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
     * 日志
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
     * 创建 plugin上下文 实例
     * @param pluginDir plugindir
     */
    public PluginContext(Path pluginDir) {
        this.pluginDir = pluginDir;
        this.properties = new ConcurrentHashMap<>();
    }

    /**
     * 获取plugindir
     *
     * @return 获取plugindir的结果
     */
    public Path getPluginDir() {
        return pluginDir;
    }

    /**
     * 获取属性
     *
     * @return 获取属性的结果
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * 设置财产
     *
     * @param key 键
     * @param value 值
     */
    public void setProperty(String key, String value) {
        properties.put(key, value);
    }

    /**
     * 获取财产
     *
     * @param key 键
     * @return 获取财产的结果
     */
    public String getProperty(String key) {
        return properties.get(key);
    }

    /**
     * 获取财产
     *
     * @param key 键
     * @param defaultValue 默认值
     * @return 获取财产的结果
     */
    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    /**
     * 获取runtime管理器
     *
     * @return 获取runtime管理器的结果
     */
    public Object getRuntimeManager() {
        return runtimeManager;
    }

    /**
     * 设置runtime管理器
     *
     * @param runtimeManager runtime管理器
     */
    public void setRuntimeManager(Object runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    /**
     * 信息
     *
     * @param message 消息
     */
    public void info(String message) {
        LOG.log(Level.INFO, String.format("[PluginContext] %s", message));
    }

    /**
     * 警告
     *
     * @param message 消息
     */
    public void warn(String message) {
        LOG.log(Level.WARNING, String.format("[PluginContext] %s", message));
    }

    /**
     * 记录错误
     *
     * @param message 消息
     * @param throwable 抛出
     */
    public void error(String message, Throwable throwable) {
        LOG.log(Level.SEVERE, String.format("[PluginContext] %s", message, throwable));
    }
}