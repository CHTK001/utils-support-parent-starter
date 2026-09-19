package com.chua.runtime.plugin.loader;

import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.plugin.loader.PluginScanner.PluginInfo;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;

/**
 * 插件注册表 — 管理所有已加载的插件实例。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PluginRegistry {

    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(PluginRegistry.class.getName());
    /**
     * 插件名称到信息映射
     */
    private final Map<String, PluginInfo> pluginMap;

    /**
     * 所有插件列表
     */
    private final List<PluginInfo> pluginList;

    /** 创建 pluginregistry 实例 */
    public PluginRegistry() {
        this.pluginMap = new HashMap<>();
        this.pluginList = new ArrayList<>();
    }

    /**
    * 注册插件。
    *
    * @param info 插件信息
     */
    public void register(PluginInfo info) {
        if (pluginMap.containsKey(info.name())) {
            throw new IllegalArgumentException("插件已存在: " + info.name());
        }
        pluginMap.put(info.name(), info);
        pluginList.add(info);
        LOG.log(Level.INFO, String.format("注册插件: %s", info.name()));
    }

    /**
     * 按名称获取插件。
     *
     * @param name 插件名称
     * @return 插件实例
     */
    public Plugin get(String name) {
        PluginInfo info = pluginMap.get(name);
        return info != null ? info.plugin() : null;
    }

    /**
     * 按名称获取插件信息。
     *
     * @param name 插件名称
     * @return 插件信息
     */
    public PluginInfo getInfo(String name) {
        return pluginMap.get(name);
    }

    /**
     * 所有插件名称。
     *
     * @return 名称列表
     */
    public List<String> getNames() {
        return new ArrayList<>(pluginMap.keySet());
    }

    /**
     * 所有插件列表。
     *
     * @return 插件列表
     */
    public List<PluginInfo> list() {
        return Collections.unmodifiableList(pluginList);
    }

    /**
     * 插件数量。
     *
     * @return 数量
     */
    public int size() {
        return pluginList.size();
    }

    /**
     * 是否包含指定插件。
     *
     * @param name 插件名称
     * @return 包含返回 true
     */
    public boolean contains(String name) {
        return pluginMap.containsKey(name);
    }

    /**
     * 清除所有插件。
     */
    public void clear() {
        pluginList.clear();
        pluginMap.clear();
    }

    /**
     * 启动所有插件。
     */
    public void startAll() {
        for (PluginInfo info : pluginList) {
            try {
                info.plugin().start();
                LOG.log(Level.INFO, String.format("插件[%s] 启动成功", info.name()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("插件[%s] 启动失败", info.name(), e));
            }
        }
    }

    /**
     * 停止所有插件。
     */
    public void stopAll() {
        for (PluginInfo info : pluginList) {
            try {
                info.plugin().stop();
                LOG.log(Level.INFO, String.format("插件[%s] 停止成功", info.name()));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("插件[%s] 停止失败", info.name(), e));
            }
        }
    }

    /**
     * 获取所有运行中的插件。
     *
     * @return 运行中的插件列表
     */
    public List<PluginInfo> getRunning() {
        List<PluginInfo> running = new ArrayList<>();
        for (PluginInfo info : pluginList) {
            if (info.plugin().isRunning()) {
                running.add(info);
            }
        }
        return running;
    }
}