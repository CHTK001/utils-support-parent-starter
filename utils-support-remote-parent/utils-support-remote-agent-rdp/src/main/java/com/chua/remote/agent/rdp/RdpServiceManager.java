package com.chua.remote.agent.rdp;

import lombok.extern.slf4j.Slf4j;

/**
 * RDP 服务供给管理器（平台分支核心）。
 *
 * <p><b>纯服务端约束</b>：Java 生态没有 RDP 服务端框架（SSH 有 MINA sshd、VNC 可拉 x11vnc——
 * RDP 协议栈无任何纯 Java 实现），agent 只做「检测 + 操纵原生服务」：</p>
 * <ul>
 *   <li><b>Windows</b>：系统内置 Remote Desktop Services——已启用 → 转发；
 *       未启用 → 系统级开启（{@link RdpServerStarter} 改注册表 + 启 TermService）</li>
 *   <li><b>Linux</b>：已有 xrdp → 转发；无 → 拉起 xrdp（第三方原生进程）；
 *       不可得 → 降级为自研桌面采集（仍需有桌面会话）</li>
 *   <li><b>Mac</b>：无原生 RDP → 直接降级自研桌面采集</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class RdpServiceManager {

    private final RdpServiceProbe.Platform platform;
    private final boolean rdpPresent;
    private final boolean desktopAvailable;

    public RdpServiceManager() {
        this.platform = RdpServiceProbe.detectPlatform();
        this.rdpPresent = RdpServiceProbe.hasRdpService(platform);
        this.desktopAvailable = RdpServiceProbe.hasDesktopSession(platform);
        log.info("RDP 服务采集: platform={}, rdpPresent={}, desktop={}（{}）",
                platform, rdpPresent, desktopAvailable,
                rdpPresent ? "转发模式——复用原生 RDP 服务" : "需开启/拉起 RDP 服务或降级自研采集");
    }

    /**
     * 确保桌面供给可达（原生 RDP 服务 / 开启系统远程桌面 / 拉起 xrdp / 降级自研采集）。
     *
     * @return true=桌面供给就绪；false=不可用
     */
    public boolean ensureRdpService() {
        if (rdpPresent) {
            return true;
        }
        boolean started = RdpServerStarter.startRdpServer(platform);
        if (started) {
            return true;
        }
        // 原生 RDP 不可得（无权限/无 xrdp/Mac）：降级自研采集——仍需有桌面会话
        return desktopAvailable;
    }

    /**
     * 平台类型。
     */
    public RdpServiceProbe.Platform getPlatform() {
        return platform;
    }

    /**
     * 是否转发模式（本机已有可用 RDP 服务——复用而非开启）。
     */
    public boolean isForwardMode() {
        return rdpPresent;
    }

    /**
     * 是否降级模式（原生 RDP 不可得，画面由自研采集提供）。
     */
    public boolean isDegradeMode() {
        return !rdpPresent && desktopAvailable;
    }

    /**
     * 是否有可自研采集的桌面会话。
     */
    public boolean isDesktopAvailable() {
        return desktopAvailable;
    }
}
