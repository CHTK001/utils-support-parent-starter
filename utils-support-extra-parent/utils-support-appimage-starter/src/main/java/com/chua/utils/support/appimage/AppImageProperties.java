package com.chua.utils.support.appimage;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AppImage 打包配置属性
 * <p>
 * 提供打包 AppImage 所需的各项配置，包括 JRE 路径、
 * appimagetool 工具路径、JVM 参数等
 *
 * @author CH
 */
@Data
public class AppImageProperties {

    /*
     * 配置前缀
     */
    public static final String PREFIX = "appimage";

    /*
     * 是否启用 AppImage 打包，默认 true
     */
    private Boolean enabled = true;

    /*
     * appimagetool 缓存目录，类路径下未找到时使用
     */
    private String cacheDir;

    /*
     * 应用名称，用于生成 .AppImage 和 .desktop 文件名
     */
    private String appName;

    /*
     * 桌面显示名称，用于 .desktop 文件的 Name 字段
     */
    private String displayName;

    /*
     * 图标路径，支持 PNG 格式 256x256 像素，类路径下自动提取
     */
    private String iconPath;

    /*
     * 输出目录，生成的 .AppImage 存放路径，默认 ./appimage-out/
     */
    private String outputDir = "./appimage-out/";

    /*
     * JRE 路径，指定 JRE 目录会嵌入到 AppImage 中
     */
    private String jrePath;

    /*
     * jlink 模块列表，用于裁剪 JRE，适合 Spring Boot 应用
     */
    private List<String> jlinkModules = new ArrayList<>(List.of(
            "java.base", "java.logging", "java.xml", "java.sql",
            "java.management", "java.naming", "java.desktop",
            "java.security.jgss", "java.instrument", "java.scripting"
    ));

    /*
     * JVM 参数，如 -Xmx512m -Dspring.profiles.active=prod
     */
    private List<String> jvmArgs = new ArrayList<>();

    /*
     * Spring Boot 应用参数，传递给 main 方法
     */
    private List<String> appArgs = new ArrayList<>();

    /*
     * AppImage 运行时环境变量
     */
    private Map<String, String> environment;

    /*
     * appimagetool 下载 URL，用于自动获取 appimagetool
     */
    private String appimagetoolUrl =
            "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage";

    /*
     * 是否覆盖已存在的 .AppImage 文件
     */
    private Boolean overwrite = true;

    /*
     * 默认配置
     */
    private DefaultConfig defaultConfig = new DefaultConfig();

    @Data
    public static class DefaultConfig {
        /*
         * 默认 JRE 路径
         */
        private String jrePath;

        /*
         * 默认图标路径
         */
        private String iconPath;

        /*
         * 默认 JVM 参数
         */
        private List<String> jvmArgs = new ArrayList<>();
    }
}