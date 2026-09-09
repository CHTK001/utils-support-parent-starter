package com.chua.remote.agent.rdp;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * RDP 服务器信息自采集。
 *
 * <p>agent 部署到被控机后采集：平台类型（Linux/Mac/Windows）+ RDP 服务级检测
 * （Windows 查 {@code TermService} 服务状态与注册表 {@code fDenyTSConnections}；
 * Linux 查 {@code xrdp} 进程或 systemd 服务——<b>非端口 3389 探测</b>）+ 桌面环境。
 * 采集结果用于 {@link RdpServiceManager} 决定「转发原生 RDP」「开启/拉起 RDP 服务」还是「降级自研采集」。</p>
 *
 * <p><b>纯服务端约束</b>：Java 无 RDP 服务端框架——agent 只检测/操纵原生 RDP 服务，不实现协议。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class RdpServiceProbe {

    private RdpServiceProbe() {
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
     * RDP 服务级检测（非端口 3389 探测）。
     *
     * @param platform 平台类型
     * @return true=本机已有 RDP 服务在运行（转发模式）；false=无（需开启/拉起或降级）
     */
    public static boolean hasRdpService(Platform platform) {
        switch (platform) {
            case WINDOWS:
                return hasWindowsRdp();
            case LINUX:
                return hasLinuxXrdp();
            case MAC:
            default:
                // Mac 无原生 RDP，也无成熟第三方 RDP server——直接降级自研采集
                return false;
        }
    }

    /**
     * 当前桌面会话可用性（降级自研采集需要图形会话）。
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
     * Windows：远程桌面服务检测——TermService 运行中 且 注册表未拒绝远程连接。
     */
    private static boolean hasWindowsRdp() {
        String scOut = runOut("sc", "query", "TermService");
        if (scOut == null || !scOut.contains("RUNNING")) {
            return false;
        }
        // 服务运行但注册表 fDenyTSConnections=1 时远程桌面实际拒绝连接——不算可达
        String regOut = runOut("reg", "query",
                "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Terminal Server", "/v", "fDenyTSConnections");
        if (regOut != null && regOut.contains("0x1")) {
            return false;
        }
        return true;
    }

    /**
     * Linux：xrdp 进程检测 + systemd 服务兜底。
     */
    private static boolean hasLinuxXrdp() {
        String psOut = runOut("ps", "-ef");
        if (psOut != null && psOut.contains("xrdp")) {
            return true;
        }
        return runOk("systemctl", "is-active", "xrdp");
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
