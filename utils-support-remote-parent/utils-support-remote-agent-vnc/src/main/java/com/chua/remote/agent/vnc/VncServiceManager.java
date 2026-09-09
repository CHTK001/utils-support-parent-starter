package com.chua.remote.agent.vnc;

import lombok.extern.slf4j.Slf4j;

/**
 * VNC 服务供给管理器（平台分支核心）。
 *
 * <p>按平台保证本机 VNC 桌面服务可达：</p>
 * <ul>
 *   <li><b>Linux</b>：已有 VNC 服务（运行中）→ 转发模式（复用已有，不重复起）；
 *       无 → 自启 x11vnc（{@link VncServerStarter} 拉起）</li>
 *   <li><b>Mac</b>（类 Unix）：与 Linux 相同逻辑</li>
 *   <li><b>Windows</b>：自身不具备 VNC 服务 → 尝试自启已装 VNC server；
 *       未装则降级为自研桌面采集（{@code NativeScreenCapture} 提供画面）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class VncServiceManager {

    private final VncServiceProbe.Platform platform;
    private final boolean vncPresent;
    private final boolean desktopAvailable;

    public VncServiceManager() {
        this.platform = VncServiceProbe.detectPlatform();
        this.vncPresent = VncServiceProbe.hasVncService(platform);
        this.desktopAvailable = VncServiceProbe.hasDesktopSession(platform);
        log.info("VNC 服务采集: platform={}, vncPresent={}, desktop={}（{}）",
                platform, vncPresent, desktopAvailable,
                vncPresent ? "转发模式——复用已有 VNC" : "需自启 VNC 服务");
    }

    /**
     * 确保本机 VNC 桌面服务可达。
     *
     * @return true=VNC 服务就绪（转发复用已有 / 自启成功 / 可自研采集）；false=不可用
     */
    public boolean ensureVncService() {
        if (vncPresent) {
            return true;
        }
        boolean started = VncServerStarter.startVncServer(platform);
        if (started) {
            return true;
        }
        // 自启失败：Windows 无 VNC 工具时靠自研采集提供画面（仍需有桌面会话）
        return desktopAvailable;
    }

    /**
     * 平台类型。
     */
    public VncServiceProbe.Platform getPlatform() {
        return platform;
    }

    /**
     * 是否转发模式（本机已有 VNC 服务——复用而非自启）。
     */
    public boolean isForwardMode() {
        return vncPresent;
    }

    /**
     * 是否有可自研采集的桌面会话。
     */
    public boolean isDesktopAvailable() {
        return desktopAvailable;
    }
}
