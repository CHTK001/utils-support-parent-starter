package com.chua.remote.support.agent.rustdesk;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * RustDesk 集成配置属性。
 *
 * <p>从 capabilities Map 中解析 RustDesk 相关配置项，
 * 支持通过 {@code --agent.capability.rustdesk.<key>=<value>} 传入。</p>
 *
 * <h3>可用配置项</h3>
 * <table>
 *   <tr><th>capability key</th><th>说明</th><th>默认值</th></tr>
 *   <tr><td>rustdesk.appimage-path</td><td>本地 AppImage 绝对路径</td><td>无（将从 URL 下载）</td></tr>
 *   <tr><td>rustdesk.appimage-url</td><td>AppImage 下载 URL</td><td>GitHub 1.4.7 x86_64</td></tr>
 *   <tr><td>rustdesk.appimage-dir</td><td>AppImage 下载缓存目录</td><td>~/.cache/utils-remote-agent</td></tr>
 *   <tr><td>rustdesk.exe-path</td><td>通用可执行文件路径（跨平台）</td><td>无</td></tr>
 *   <tr><td>rustdesk.exe-url</td><td>通用可执行文件下载 URL</td><td>无</td></tr>
 *   <tr><td>rustdesk.server-host</td><td>hbbs rendezvous 服务器地址</td><td>无（使用公网服务器）</td></tr>
 *   <tr><td>rustdesk.server-key</td><td>hbbs 公钥</td><td>空</td></tr>
 *   <tr><td>rustdesk.password-rotation</td><td>密码轮换间隔（秒），0=不轮换</td><td>1800（30分钟）</td></tr>
 *   <tr><td>rustdesk.work-dir</td><td>RustDesk 工作目录</td><td>~/.rustdesk-agent</td></tr>
 * </table>
 *
 * @author CH
 */
@Getter
@Setter
public class RustDeskProperties {

    /**
     * 本地 AppImage 绝对路径（优先级最高，Linux）
     */
    private String appImagePath;

    /**
     * AppImage 下载 URL（appImagePath 不可用时使用，Linux）
     */
    private String appImageUrl;

    /**
     * AppImage 下载缓存目录（默认 ~/.cache/utils-remote-agent）
     */
    private String appImageDir;

    /**
     * 通用可执行文件路径（跨平台，优先级高于 appImagePath）
     */
    private String exePath;

    /**
     * 通用可执行文件下载 URL（跨平台，优先级高于 appImageUrl）
     */
    private String exeUrl;

    /**
     * hbbs rendezvous 服务器地址
     */
    private String serverHost;

    /**
     * hbbs 服务器公钥(id_ed25519.pub 内容)
     */
    private String serverKey;

    /**
     * 密码轮换间隔（秒），0 表示不轮换
     */
    private int passwordRotationSeconds = 1800;

    /**
     * RustDesk 工作目录（存放配置、日志等）
     */
    private String workDir;

    /**
     * 是否跳过 SSL 证书验证下载（仅用于内网/测试环境）
     */
    private boolean insecureDownload;

    // ===== 跨平台可执行文件路径解析 =====

    /**
     * 获取可执行文件路径：exePath > appImagePath
     */
    public String getExePath() {
        if (exePath != null && !exePath.isBlank()) { return exePath; }
        return appImagePath;
    }

    /**
     * 获取可执行文件下载 URL：exeUrl > appImageUrl
     */
    public String getExeUrl() {
        if (exeUrl != null && !exeUrl.isBlank()) { return exeUrl; }
        return appImageUrl;
    }

    /**
     * 获取 AppImage 缓存目录，默认 ~/.cache/utils-remote-agent
     */
    public String getAppImageDir() {
        if (appImageDir != null && !appImageDir.isBlank()) { return appImageDir; }
        return System.getProperty("user.home", ".") + "/.cache/utils-remote-agent";
    }

    /**
     * 从 capabilities Map 中解析 RustDesk 配置。
     *
     * @param caps capabilities Map（通常来自 AgentProperties.getCapabilities()）
     * @return RustDeskProperties 实例
     */
    public static RustDeskProperties fromCapabilities(Map<String, String> caps) {
        RustDeskProperties props = new RustDeskProperties();
        if (caps == null) { return props; }

        props.appImagePath = caps.get("rustdesk.appimage-path");
        props.appImageUrl = caps.get("rustdesk.appimage-url");
        props.appImageDir = caps.get("rustdesk.appimage-dir");
        props.exePath = caps.get("rustdesk.exe-path");
        props.exeUrl = caps.get("rustdesk.exe-url");
        props.serverHost = caps.get("rustdesk.server-host");
        props.serverKey = caps.get("rustdesk.server-key");

        String rotationStr = caps.get("rustdesk.password-rotation");
        if (rotationStr != null && !rotationStr.isBlank()) {
            try { props.passwordRotationSeconds = Integer.parseInt(rotationStr); }
            catch (NumberFormatException ignored) {}
        }

        props.workDir = caps.get("rustdesk.work-dir");

        String insecureStr = caps.get("rustdesk.insecure-download");
        if (insecureStr != null && !insecureStr.isBlank()) {
            props.insecureDownload = "true".equalsIgnoreCase(insecureStr) || "1".equals(insecureStr);
        }

        return props;
    }

    @Override
    public String toString() {
        return "RustDeskProperties{appImagePath='" + maskPath(appImagePath)
                + "', appImageDir='" + appImageDir
                + "', serverHost='" + serverHost + "', rotation=" + passwordRotationSeconds + "s}";
    }

    private static String maskPath(String path) {
        if (path == null) { return "null"; }
        if (path.length() <= 30) { return path; }
        return path.substring(0, 15) + "..." + path.substring(path.length() - 15);
    }
}
