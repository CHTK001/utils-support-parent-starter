package com.chua.remote.agent.ssh;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SSH 服务器信息自采集。
 *
 * <p>agent 部署到被控机后采集：平台类型（Linux/Mac/Windows）+ sshd 服务级检测
 * （{@code systemctl} / 进程 / 配置——<b>非端口 22 探测</b>）+ SSH 凭据。
 * 采集结果用于 {@link SshServiceManager} 决定「转发已有 sshd」还是「自启 sshd」。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SshServiceProbe {

    private SshServiceProbe() {
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
     * sshd 服务级检测（非端口 22 探测）。
     *
     * @param platform 平台类型
     * @return true=本机已有 sshd 服务在运行（转发模式）；false=无（需自启）
     */
    public static boolean hasSshdService(Platform platform) {
        switch (platform) {
            case LINUX:
            case MAC:
                return hasUnixSshd();
            case WINDOWS:
                return hasWindowsSshd();
            default:
                return false;
        }
    }

    /**
     * Linux/Mac：systemctl（systemd 发行版）→ 进程检测（ps）→ 配置存在性，三路兜底。
     */
    private static boolean hasUnixSshd() {
        if (runOk("systemctl", "is-active", "ssh") || runOk("systemctl", "is-active", "sshd")) {
            return true;
        }
        String psOut = runOut("ps", "-ef");
        if (psOut != null && psOut.contains("sshd")) {
            return true;
        }
        return false;
    }

    /**
     * Windows：OpenSSH 服务（sshd——Windows 自带的 OpenSSH Server）。
     */
    private static boolean hasWindowsSshd() {
        String out = runOut("sc", "query", "sshd");
        if (out != null && out.contains("RUNNING")) {
            return true;
        }
        out = runOut("powershell", "-NoProfile", "-Command",
                "Get-Service sshd -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Status");
        return out != null && out.trim().equalsIgnoreCase("Running");
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

    /**
     * 采集 SSH 凭据（配置的 username/password——由启动参数注入）。
     *
     * @return 凭据数组 [username, password]
     */
    public static String[] collectCredentials(String configuredUser, String configuredPass) {
        String user = configuredUser != null && !configuredUser.isEmpty() ? configuredUser
                : System.getProperty("user.name", "");
        String pass = configuredPass != null ? configuredPass : "";
        return new String[]{user, pass};
    }
}
