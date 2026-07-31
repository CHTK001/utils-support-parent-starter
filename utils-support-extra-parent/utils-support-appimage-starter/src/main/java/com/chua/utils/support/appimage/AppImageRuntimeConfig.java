package com.chua.utils.support.appimage;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * AppImage 运行时配置
 *
 * @author CH
 */
@Data
public class AppImageRuntimeConfig {

    /*
     * AppImage 实例标识
     */
    private String appImageId;

    /*
     * AppImage 显示名称
     */
    private String appImageName;

    /*
     * AppImage 文件路径
     */
    private String appImagePath;

    /*
     * AppImage 打包类型，默认为 JAVA_APPLICATION
     */
    private AppImageType appImageType = AppImageType.JAVA_APPLICATION;

    /*
     * AppImage 工作目录
     */
    private String workDir;

    /*
     * 日志文件路径
     */
    private String logFile;

    /*
     * 环境变量
     */
    private Map<String, String> environment = new HashMap<>();

    /*
     * 启动超时时间，默认 30000 毫秒
     */
    private long startupTimeout = 30000;

    /*
     * 关闭超时时间，默认 10000 毫秒
     */
    private long shutdownTimeout = 10000;

    /*
     * 重启延迟时间，默认 5000 毫秒
     */
    private long restartDelay = 5000;

    /*
     * 健康检查 URL
     */
    private String healthCheckUrl;

    /*
     * 健康检查间隔，默认 5000 毫秒
     */
    private long healthCheckInterval = 5000;

    /*
     * 是否自动启动，默认 false
     */
    private boolean autoStart = false;

    /*
     * 是否自动重启
     */
    private boolean autoRestart = false;

    /*
     * 最大重启次数，默认 3 次
     */
    private int maxRestartAttempts = 3;
}