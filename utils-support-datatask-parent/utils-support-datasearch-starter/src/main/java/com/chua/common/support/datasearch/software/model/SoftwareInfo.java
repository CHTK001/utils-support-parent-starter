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
     * 包 ID（安装标识符）
     */
    private final String packageId;

    public SoftwareInfo(String name, String version, String source, String description, String packageId) {
        this.name = name;
        this.version = version;
        this.source = source;
        this.description = description;
        this.packageId = packageId;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getSource() {
        return source;
    }

    public String getDescription() {
        return description;
    }

    public String getPackageId() {
        return packageId;
    }

    /**
     * 转换为 Map 用于 JSON 序列化
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