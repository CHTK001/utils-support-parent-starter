package com.chua.common.support.datasearch.plugin.model;

import java.util.Collections;
import java.util.Map;

/**
 * 插件定义。
 *
 * <p>统一描述 AI 编辑器/Agent的插件（含 VS Code 扩展、Claude 插件、Trae-CN 插件、
 * 市场包等）元信息，供「从插件市场导入」与「本地插件扫描」统一收集。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class PluginDefinition {

    /**
     * 插件唯一标识（市场/仓库 + 名称）。
    */
    private final String id;
    /**
     * 插件名称。
    */
    private final String name;
    /**
     * 插件描述。
    */
    private final String description;
    /**
     * 作者/发布者。
    */
    private final String author;
    /**
     * 版本。
    */
    private final String version;
    /**
     * 插件落盘位置（本地）或市场主页（在线）。
    */
    private final String location;
    /**
     * 来源标识（如 claude / trae-cn / open-vsx / clawhub）。
     */
    private final String source;
    /**
     * 插件清单路径（如 plugin.json / package.json）。
    */
    private final String manifestPath;
    /**
     * 额外元信息。
    */
    private final Map<String, Object> extra;

    /**
     * 全参构造器。
     *
     * @param id           插件唯一标识
     * @param name         插件名称
     * @param description  插件描述
     * @param author       作者
     * @param version      版本
     * @param location     落盘位置或市场主页
     * @param source       来源标识
     * @param manifestPath 插件清单路径
     * @param extra        额外元信息
     */
    public PluginDefinition(String id, String name, String description, String author,
                            String version, String location, String source,
                            String manifestPath, Map<String, Object> extra) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.author = author;
        this.version = version;
        this.location = location;
        this.source = source;
        this.manifestPath = manifestPath;
        this.extra = extra != null ? extra : Collections.emptyMap();
    }

    /**
     * 含清单路径构造器。
     *
     * @param id           插件唯一标识
     * @param name         插件名称
     * @param description  插件描述
     * @param author       作者
     * @param version      版本
     * @param location     落盘位置或市场主页
     * @param source       来源标识
     * @param manifestPath 插件清单路径
     */
    public PluginDefinition(String id, String name, String description, String author,
                            String version, String location, String source, String manifestPath) {
        this(id, name, description, author, version, location, source, manifestPath, Collections.emptyMap());
    }

    /**
     * 简配构造器。
     *
     * @param id          插件唯一标识
     * @param name        插件名称
     * @param description 插件描述
     * @param author      作者
     * @param version     版本
     * @param location    落盘位置或市场主页
     * @param source      来源标识
     */
    public PluginDefinition(String id, String name, String description, String author,
                            String version, String location, String source) {
        this(id, name, description, author, version, location, source, null, Collections.emptyMap());
    }

    /**
     * 获取插件唯一标识。
     *
     * @return 插件标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取插件名称。
     *
     * @return 插件名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取插件描述。
     *
     * @return 插件描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取作者。
     *
     * @return 作者
     */
    public String getAuthor() {
        return author;
    }

    /**
     * 获取版本。
     *
     * @return 版本
     */
    public String getVersion() {
        return version;
    }

    /**
     * 获取落盘位置或市场主页。
     *
     * @return 位置
     */
    public String getLocation() {
        return location;
    }

    /**
     * 获取来源标识。
     *
     * @return 来源
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取插件清单路径。
     *
     * @return 清单路径，可为 null
     */
    public String getManifestPath() {
        return manifestPath;
    }

    /**
     * 获取额外元信息。
     *
     * @return 额外元信息
     */
    public Map<String, Object> getExtra() {
        return extra;
    }
}
