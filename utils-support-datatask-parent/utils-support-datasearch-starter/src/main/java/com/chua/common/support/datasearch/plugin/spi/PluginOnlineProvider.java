package com.chua.common.support.datasearch.plugin.spi;

import com.chua.common.support.datasearch.plugin.model.PluginDefinition;

import java.util.Collections;
import java.util.List;

/**
 * 在线插件市场提供者接口。
 *
 * <p>负责从在线插件市场（Open VSX、ClawHub、SkillHub 等）搜索插件。
 * 每个在线市场源一个实现。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public interface PluginOnlineProvider {

    /**
    * 获取提供者名称（市场源标识，如 open-vsx / clawhub）。
    *
    * @return 提供者名称
    */
    String name();

    /**
    * 获取该市场源当前展示的插件列表。
    *
    * @return 插件定义列表
    */
    default List<PluginDefinition> getPlugins() {
        return Collections.emptyList();
    }

    /**
    * 按关键词搜索在线插件。
    *
    * @param keyword 搜索关键词
    * @return 搜索结果插件定义列表
    */
    default List<PluginDefinition> search(String keyword) {
        return Collections.emptyList();
    }

    /**
    * 安装插件。
    *
    * @param clientId 客户端标识
    * @param pluginId 插件标识
    * @return 是否安装成功
    */
    default boolean install(String clientId, String pluginId) {
        return false;
    }

    /**
    * 卸载插件。
    *
    * @param clientId 客户端标识
    * @param pluginId 插件标识
    * @return 是否卸载成功
    */
    default boolean uninstall(String clientId, String pluginId) {
        return false;
    }
}
