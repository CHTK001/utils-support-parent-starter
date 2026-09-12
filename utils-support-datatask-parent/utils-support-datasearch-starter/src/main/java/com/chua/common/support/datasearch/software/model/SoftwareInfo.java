package com.chua.common.support.datasearch.software.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 软件信息模型
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SoftwareInfo {

    /**
     * 软件名称
     */
    private final String name;

    /**
     * 版本号
     */
    private final String version;

    /**
     * 软件来源（包管理器名称）
     */
    private final String source;

    /**
     * 软件描述
     */
    private final String description;

    /**
      * 包 标识（安装标识符）
     */
    private final String packageId;

    /**
      * 创建 software信息 实例
     * @param name 名称
     * @param name 字符串
     * @param name 字符串
     * @param name 字符串
     * @param name 字符串
     * @param version 版本
     * @param source 源
     * @param description description
     * @param packageId 包标识
     */
    public SoftwareInfo(String name, String version, String source, String description, String packageId) {
        this.name = name;
        this.version = version;
        this.source = source;
        this.description = description;
        this.packageId = packageId;
    }

    /**
     * 获取名称
     *
     * @return 获取名称的结果
     */
    public String getName() {
        return name;
    }

    /**
     * 获取版本
     *
     * @return 获取版本的结果
     */
    public String getVersion() {
        return version;
    }

    /**
     * 获取源
     *
     * @return 获取源的结果
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取Description
     *
     * @return 获取description的结果
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取包id
     *
     * @return 获取包id的结果
     */
    public String getPackageId() {
        return packageId;
    }

    /**
      * 转换为 映射 用于 JSON 序列化
     *
     * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("version", version);
        map.put("source", source);
        map.put("description", description);
        map.put("packageId", packageId);
        return map;
    }
}