package com.chua.remote.agent.vnc;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * VNC 服务器信息自采集。
 *
 * <p>agent 部署到被控机后采集：平台类型（Linux/Mac/Windows）+ VNC 服务级检测
 * （{@code x11vnc} / {@code Xvnc} / {@code vncserver} / {@code tigervnc} 进程或 systemd 服务——
 * <b>非端口 5900 探测</b>）+ 桌面环境（{@code DISPLAY}）。
 * 采集结果用于 {@link VncServiceManager} 决定「转发已有 VNC」还是「自启 VNC 服务」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class VncServiceProbe {

    private VncServiceProbe() {
    }

    /** 平台类型。 */
    public enum Platform {
        LINUX, MAC, WINDOWS, OTHER
    }

    /**
     * 检测平台类型。
     *
     * @return 平台类型
     */
    public static Platform detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            return Platform.LINUX;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Platform.MAC;
        }
        if (os.contains("win")) {
            return Platform.WINDOWS;
        }
        return Platform.OTHER;
    }

    /**
     * VNC 服务级检测（非端口 5900 探测）。
     *
     * @param platform 平台类型
     * @return true=本机已有 VNC 服务在运行（转发模式）；false=无（需自启）
     */
    public static boolean hasVncService(Platform platform) {
        switch (platform) {
            case LINUX:
            case MAC:
                return hasUnixVnc();
            case WINDOWS:
                return hasWindowsVnc();
            default:
                return false;
        }
    }

    /**
     * 当前 X/桌面会话可用性（PUSH 自研采集需要图形会话）。
     *
     * @param platform 平台类型
     * @return true=有可采集的桌面会话
     */
    public static boolean hasDesktopSession(Platform platform) {
        switch (platform) {
            case LINUX:
                String display = System.getenv("DISPLAY");
                return display != null && !display.isBlank();
            case MAC:
            case WINDOWS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Linux/Mac：进程检测（x11vnc/Xvnc/tigervnc/vncserver）+ systemd 服务三路兜底。
     */
    private static boolean hasUnixVnc() {
        String psOut = runOut("ps", "-ef");
        if (psOut != null) {
            for (String marker : new String[]{"x11vnc", "Xvnc", "tigervnc", "vncserver", "vnc-agent"}) {
                if (psOut.contains(marker)) {
                    return true;
                }
            }
        }
        if (runOk("systemctl", "is-active", "vncserver") || runOk("systemctl", "is-active", "tigervncserver")) {
            return true;
        }
        return false;
    }

    /**
     * Windows：VNC server 服务/进程（UltraVNC / TightVNC / RealVNC 服务名 + 进程名兜底）。
     */
    private static boolean hasWindowsVnc() {
        for (String svc : new String[]{"VNCServer", "uvnc_service", "TightVNC"}) {
            String out = runOut("sc", "query", svc);
            if (out != null && out.contains("RUNNING")) {
                return true;
            }
        }
        String tasklist = runOut("tasklist", "/fo", "csv", "/nh");
        if (tasklist != null) {
            for (String marker : new String[]{"UltraVNC", "uVNC", "TightVNC", "RealVNC", "vncservice"}) {
                if (tasklist.contains(marker)) {
                    return true;
                }
            }
        }
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

    /**
     * 执行命令并返回输出（失败返回 null）。
     */
    private static String runOut(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            log.debug("命令执行失败: {} - {}", String.join(" ", cmd), e.getMessage());
            return null;
        }
    }
}
