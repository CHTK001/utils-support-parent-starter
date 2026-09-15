package com.chua.common.support.datasearch.plugin.spi;

import com.chua.common.support.datasearch.plugin.model.PluginDefinition;

import java.util.Collections;
import java.util.List;

/**
 * 离线（本地）插件提供者接口。
 *
 * <p>负责扫描本机已安装 AI 编辑器/智能体的插件/扩展目录，将本地插件暴露给
 * 「从 智能体 导入」统一收集。每种来源（Claude、Trae-CN、VS Code、Cursor、Windsurf 等）
 * 一个实现，各自扫描自己的插件目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public interface PluginOfflineProvider {

    /**
     * 获取提供者名称（来源标识，如 claude / trae-cn / vscode）。
     *
     * @return 提供者名称
     */
    String name();

    /**
     * 扫描本机该来源的插件/扩展，返回可导入的本地插件定义。
     *
     * @return 本地插件定义列表
     */
    default List<PluginDefinition> listPlugins() {
        return Collections.emptyList();
    }

    /**
     * 该来源是否已安装（目录存在）。
     *
     * @return true 表示已安装
     */
    default boolean isInstalled() {
        return false;
    }
}