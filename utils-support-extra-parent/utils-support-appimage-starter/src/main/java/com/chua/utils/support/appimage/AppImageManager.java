package com.chua.utils.support.appimage;

import com.chua.utils.support.appimage.exception.AppImageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AppImage 管理器 - 统一入口
 * <p>
 * 集成 appimagetool 下载、JRE 管理、打包等功能。
 * 调用 {@link #packageAppImage(File)} 将 fat jar 打包为 .AppImage 文件
 * <p>
 * 使用示例：
 * <pre>
 * AppImageProperties props = new AppImageProperties();
 * props.setOutputDir("./dist");
 * props.setJvmArgs(List.of("-Xmx256m", "-Dspring.profiles.active=prod"));
 *
 * AppImageManager manager = new AppImageManager(props);
 * File appImage = manager.packageAppImage(new File("app.jar"));
 * System.out.println("打包完成: " + appImage.getAbsolutePath());
 * </pre>
 *
 * @author CH
 */
public class AppImageManager {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AppImageManager.class);
    /** 单例实例 */
    private static volatile AppImageManager INSTANCE;

    /** 应用配置属性 */
    private final AppImageProperties properties;
    /** 安装器 */
    private final AppImageInstaller installer;
    /** 打包器 */
    private final AppImagePackager packager;
    /** 生命周期管理器 */
    private final AppImageLifecycleManager lifecycleManager;

    /*
     * 已注册的 AppImage 实例映射
     */
    private final Map<String, AppImageInstance> instances = new ConcurrentHashMap<>();

    /**
     * 创建 AppImageManager 实例
     * @param properties properties
     */
    public AppImageManager(AppImageProperties properties) {
        this.properties = properties;
        this.installer = new AppImageInstaller(properties);
        this.packager = new AppImagePackager(properties, installer);
        this.lifecycleManager = new AppImageLifecycleManager();
        INSTANCE = this;
    }

    /**
     * 获取全局管理器实例
     */
    public static AppImageManager getInstance() {
        if (INSTANCE == null) {
            throw new AppImageException("AppImageManager 尚未初始化，请先创建实例");
        }
        return INSTANCE;
    }

    /**
     * 将 Spring Boot fat jar 打包为 AppImage
     * 自动处理 JRE 下载、AppDir 构建、appimagetool 执行等步骤
     *
     * @param fatJar Spring Boot 打包后的 jar 文件
     * @return 生成的 .AppImage 文件
     */
    public File packageAppImage(File fatJar) throws IOException, InterruptedException {
        if (!properties.getEnabled()) {
            throw new IllegalStateException("AppImage 打包未启用");
        }

        log.info("开始打包: {}", fatJar.getName());

        String toolPath = installer.getAppimagetoolPath();
        log.info("appimagetool 路径: {}", toolPath);

        File appImageFile = packager.packageAppImage(fatJar);

        AppImageInstance instance = new AppImageInstance();
        instance.setId(fatJar.getName().replace(".jar", ""));
        instance.setAppName(properties.getAppName());
        instance.setAppImageFile(appImageFile);
        instance.setStatus(AppImageStatus.STOPPED);
        instances.put(instance.getId(), instance);

        log.info("打包完成: {} -> {}", fatJar.getName(), appImageFile.getAbsolutePath());
        return appImageFile;
    }

    /**
     * 获取指定 ID 的实例
     */
    public AppImageInstance getInstance(String id) {
        return instances.get(id);
    }

    /**
     * 获取所有已注册的实例
     */
    public Map<String, AppImageInstance> getAllInstances() {
        return instances;
    }

    /**
     * 确保 appimagetool 已安装
     * 工具将自动从 URL 下载并缓存到 cache-dir 目录，
     * 如果 JAR 中已内置该工具则直接使用
     */
    public boolean ensureInstalled() throws IOException {
        String bin = installer.getAppimagetoolPath();
        log.info("appimagetool 已就绪: {}", bin);
        return true;
    }

    /**
     * 获取配置
     */
    public AppImageProperties getProperties() {
        return properties;
    }

    /**
     * 获取安装器
     */
    public AppImageInstaller getInstaller() {
        return installer;
    }

    /**
     * 获取打包器
     */
    public AppImagePackager getPackager() {
        return packager;
    }

    // ========== 生命周期管理 ==========

    /**
     * 启动 AppImage 进程
     *
     * @param config 运行时配置
     * @return 操作结果
     */
    public AppImageManagementResponse startAppImage(AppImageRuntimeConfig config) {
        log.info("启动 AppImage: {}", config.getAppImageId());
        return lifecycleManager.startAppImage(config);
    }

    /**
     * 停止 AppImage 进程
     *
     * @param appImageId AppImage 实例 ID
     * @return 操作结果
     */
    public AppImageManagementResponse stopAppImage(String appImageId) {
        log.info("停止 AppImage: {}", appImageId);
        return lifecycleManager.stopAppImage(appImageId);
    }

    /**
     * 重启 AppImage 进程
     *
     * @param appImageId AppImage 实例 ID
     * @return 操作结果
     */
    public AppImageManagementResponse restartAppImage(String appImageId) {
        log.info("重启 AppImage: {}", appImageId);
        return lifecycleManager.restartAppImage(appImageId);
    }

    /**
     * 查看 AppImage 运行状态
     *
     * @param appImageId AppImage 实例 ID
     * @return 操作结果
     */
    public AppImageManagementResponse getAppImageStatus(String appImageId) {
        return lifecycleManager.getStatus(appImageId);
    }

    /**
     * 获取所有正在运行的 AppImage 实例
     *
     * @return 实例映射
     */
    public Map<String, AppImageInstance> getAllRunningInstances() {
        return lifecycleManager.getAllInstances();
    }

    /**
     * 安装软件包
     *
     * @param softwareFile 软件包文件（AppImage、tar.gz、zip 等）
     * @param installDir   安装目录
     * @return 安装后主执行文件路径
     */
    public String installSoftware(File softwareFile, String installDir) throws IOException {
        log.info("安装软件: {} -> {}", softwareFile.getName(), installDir);
        return installer.installSoftware(softwareFile, installDir);
    }

    /**
     * 列出已安装的软件
     *
     * @param installDir 安装目录
     * @return 软件名称列表
     */
    public List<String> getInstalledSoftware(String installDir) {
        return installer.getInstalledSoftware(installDir);
    }

    /**
     * 卸载软件
     *
     * @param softwareName 软件名称
     * @param installDir   安装目录
     */
    public void uninstallSoftware(String softwareName, String installDir) throws IOException {
        log.info("卸载软件: {}", softwareName);
        installer.uninstallSoftware(softwareName, installDir);
    }

    /**
     * 关闭管理器，停止所有实例
     */
    public void shutdown() {
        log.info("关闭 AppImage 管理器");
        lifecycleManager.shutdown();
    }
}
