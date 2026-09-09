package com.chua.remote.agent.rdp;

import lombok.extern.slf4j.Slf4j;

/**
 * RDP 服务开启/拉起器。
 *
 * <p><b>纯服务端约束</b>：本机缺失可用 RDP 服务时由 agent 操纵<b>原生服务</b>补位（不实现 RDP 协议）：</p>
 * <ul>
 *   <li><b>Windows</b>：注册表 {@code fDenyTSConnections=0} + 启动 {@code TermService}
 *       （{@code net start} / {@code sc start}）——需管理员权限</li>
 *   <li><b>Linux</b>：{@code systemctl start xrdp} → 兜底 {@code service xrdp start}</li>
 *   <li><b>Mac</b>：不支持（降级自研采集）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class RdpServerStarter {

    private RdpServerStarter() {
    }

    /**
     * 开启/拉起 RDP 服务。
     *
     * @param platform 平台类型
     * @return true=RDP 服务就绪；false=失败（环境不支持/无权限——调用方降级自研采集）
     */
    public static boolean startRdpServer(RdpServiceProbe.Platform platform) {
        switch (platform) {
            case WINDOWS:
                return startWindowsRdp();
            case LINUX:
                return startLinuxXrdp();
            default:
                log.info("平台 {} 无可拉起的原生 RDP 服务——降级自研采集", platform);
                return false;
        }
    }

    /**
     * Windows：允许远程桌面（注册表）+ 启动 TermService 服务。
     */
    private static boolean startWindowsRdp() {
        // fDenyTSConnections=0（1=拒绝远程连接）——需管理员权限
        boolean regOk = runOk("reg", "add",
                "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Terminal Server",
                "/v", "fDenyTSConnections", "/t", "REG_DWORD", "/d", "0", "/f");
        if (!regOk) {
            log.warn("Windows 远程桌面开启失败：注册表写入被拒（需管理员权限）");
            return false;
        }
        if (runOk("net", "start", "TermService") || runOk("sc", "start", "TermService")) {
            log.info("Windows 远程桌面已开启（TermService 运行中）");
            return true;
        }
        // 服务可能已在运行（仅注册表拒绝）——复查状态
        String scOut = runOut("sc", "query", "TermService");
        if (scOut != null && scOut.contains("RUNNING")) {
            log.info("Windows 远程桌面已开启（TermService 原本运行，注册表已放行）");
            return true;
        }
        log.warn("Windows TermService 启动失败");
        return false;
    }

    /**
     * Linux：systemctl 拉 xrdp → 兜底 service。
     */
    private static boolean startLinuxXrdp() {
        if (runOk("systemctl", "start", "xrdp")) {
            log.info("xrdp 拉起成功（systemctl）");
            return true;
        }
        if (runOk("service", "xrdp", "start")) {
            log.info("xrdp 拉起成功（service）");
            return true;
        }
        log.warn("xrdp 拉起失败：systemctl/service 均不可用（可能未安装 xrdp 或无权限）");
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
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            log.debug("命令执行失败: {} - {}", String.join(" ", cmd), e.getMessage());
            return null;
        }
    }
}
