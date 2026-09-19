package com.chua.common.support.network.container;


/**
 * 可部署单元类型枚举。
 *
 * <p>定义了 Web 容器支持的部署单元类型，包括标准的 Java Web 应用归档格式
 * 以及 FAT-JAR、Spring Boot 可执行归档和 Main 类部署。
 *
 * @author CH
 * @since 1.0.0
 */
public enum DeployUnitType {

    /**
     * Web Application Archive - Java Web 应用归档
     */
    WAR("war"),

    /** Java Archive - Java 归档 */
    JAR("jar"),

    /**
     * Enterprise Archive - 企业级 Java 应用归档
     */
    EAR("ear"),

    /** 可执行 FAT-JAR（含所有依赖的独立 JAR） */
    FAT_JAR("jar"),

    /** Spring Boot 可执行 JAR */
    SPRING_BOOT("jar"),

    /** Main 类部署（将 Main 方法作为 Web 入口启动） */
    MAIN("class");

    /** Extension */
    private final String extension;

    /**
     * 构造方法，创建 DeployUnit类型 实例。
     *
     * @param extension 方法入参 extension
     */
    DeployUnitType(String extension) {
        this.extension = extension;
    }

    /**
     * 获取文件扩展名。
     *
     * @return 文件扩展名
     */
    public String getExtension() {
        return extension;
    }

    /**
     * 根据文件名后缀推断部署单元类型。
     *
     * @param fileName 文件名
     * @return 对应的部署单元类型，默认返回 JAR
     */
    public static DeployUnitType fromFileName(String fileName) {
        if (fileName == null) {
            return JAR;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".war")) {
            return WAR;
        }
        if (lower.endsWith(".ear")) {
            return EAR;
        }
        return JAR;
    }

    /**
     * 根据扩展名字符串获取部署单元类型。
     *
     * @param extension 扩展名
     * @return 对应的部署单元类型，默认返回 JAR
     */
    public static DeployUnitType fromExtension(String extension) {
        if (extension == null) {
            return JAR;
        }
        switch (extension.toLowerCase()) {
            case "war":
                return WAR;
            case "ear":
                return EAR;
            case "jar":
            default:
                return JAR;
        }
    }
}
