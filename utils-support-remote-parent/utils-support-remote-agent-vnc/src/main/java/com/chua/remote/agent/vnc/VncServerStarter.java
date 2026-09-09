package com.chua.remote.agent.vnc;

import lombok.extern.slf4j.Slf4j;

/**
 * VNC 服务自启器。
 *
 * <p>本机缺失 VNC 服务时由 agent 补位拉起：</p>
 * <ul>
 *   <li><b>Linux</b>：{@code x11vnc -display $DISPLAY -forever -bg}（绑定当前 X 会话）→ 兜底 {@code tigervncserver}</li>
 *   <li><b>Mac</b>：开启屏幕共享（{@code vnc-server} / System Settings）</li>
 *   <li><b>Windows</b>：启动已装 VNC server 服务（UltraVNC/TightVNC）；未装则返回 false（走自研采集）</li>
 * </ul>
 *
 * <p>注意：远程画面回传由 agent 自研采集（{@code NativeScreenCapture}）完成；
 * 自启 VNC 服务用于满足「转发/自启 VNC 服务」语义，供第三方 VNC 客户端同时接入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class VncServerStarter {

    private VncServerStarter() {
    }

    /**
     * 自启 VNC 服务。
     *
     * @param platform 平台类型
     * @return true=启动成功；false=启动失败（环境缺 VNC 工具/无权限）
     */
    public static boolean startVncServer(VncServiceProbe.Platform platform) {
        switch (platform) {
            case LINUX:
                return startLinuxVnc();
            case MAC:
                return startMacVnc();
            case WINDOWS:
                return startWindowsVnc();
            default:
                log.warn("不支持的平台，无法自启 VNC 服务: {}", platform);
                return false;
        }
    }

    /**
     * Linux：x11vnc 绑定当前 DISPLAY → 兜底 tigervncserver。
     */
    private static boolean startLinuxVnc() {
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isBlank()) {
            if (runOk("x11vnc", "-display", display, "-forever", "-noauth", "-bg")) {
                log.info("VNC 服务自启成功（x11vnc display={}）", display);
                return true;
            }
        }
        if (runOk("tigervncserver", "-new", ":1")) {
            log.info("VNC 服务自启成功（tigervncserver）");
            return true;
        }
        log.warn("VNC 服务自启失败（Linux）：x11vnc/tigervncserver 均不可用");
        return false;
    }

    /**
     * Mac：开启屏幕共享。
     */
    private static boolean startMacVnc() {
        if (runOk("sudo", "launchctl", "load", "-w", "/System/Library/LaunchDaemons/com.apple.screensharing.plist")) {
            log.info("VNC 服务自启成功（Mac 屏幕共享）");
            return true;
        }
        log.warn("VNC 服务自启失败（Mac）：屏幕共享不可用");
        return false;
    }

    /**
     * Windows：启动已装 VNC server 服务；未装返回 false（画面靠自研采集）。
     */
    private static boolean startWindowsVnc() {
        for (String svc : new String[]{"uvnc_service", "TightVNC"}) {
            if (runOk("net", "start", svc)) {
                log.info("VNC 服务自启成功（Windows {}）", svc);
                return true;
            }
        }
        log.warn("VNC 服务自启失败（Windows）：未检测到已装 VNC server——画面将由自研采集提供");
        return false;
    }

    /**
     * 执行命令并判定退出码为 0。
     */
    private static boolean runOk(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            log.debug("命令执行失败: {} - {}", String.join(" ", cmd), e.getMessage());
            return false;
        }
    }
}
