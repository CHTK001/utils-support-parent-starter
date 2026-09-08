package com.chua.remote.agent.ssh;

import lombok.extern.slf4j.Slf4j;

/**
 * SSH 服务自启器。
 *
 * <p>本机缺失 sshd 服务时由 agent 补位拉起：</p>
 * <ul>
 *   <li><b>Linux</b>：{@code systemctl start ssh/sshd} → 兜底 {@code service ssh start}</li>
 *   <li><b>Mac</b>：{@code launchctl} / {@code systemctl start sshd}</li>
 *   <li><b>Windows</b>：启动 OpenSSH Server 服务（{@code Start-Service sshd} / {@code net start sshd}）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SshServerStarter {

    private SshServerStarter() {
    }

    /**
     * 自启 SSH 服务。
     *
     * @param platform 平台类型
     * @return true=启动成功；false=启动失败（环境缺 sshd 安装/无权限）
     */
    public static boolean startSshServer(SshServiceProbe.Platform platform) {
        switch (platform) {
            case LINUX:
                return startLinuxSshd();
            case MAC:
                return startMacSshd();
            case WINDOWS:
                return startWindowsSshd();
            default:
                log.warn("不支持的平台，无法自启 SSH 服务: {}", platform);
                return false;
        }
    }

    private static boolean startLinuxSshd() {
        if (runOk("systemctl", "start", "ssh") || runOk("systemctl", "start", "sshd")) {
            log.info("SSH 服务自启成功（systemctl）");
            return true;
        }
        if (runOk("service", "ssh", "start") || runOk("service", "sshd", "start")) {
            log.info("SSH 服务自启成功（service）");
            return true;
        }
        log.warn("SSH 服务自启失败：systemctl/service 均不可用（可能未安装 sshd 或无权限）");
        return false;
    }

    private static boolean startMacSshd() {
        if (runOk("systemctl", "start", "sshd")) {
            log.info("SSH 服务自启成功（Mac systemctl）");
            return true;
        }
        if (runOk("launchctl", "load", "-w", "/System/Library/LaunchDaemons/ssh.plist")) {
            log.info("SSH 服务自启成功（Mac launchctl）");
            return true;
        }
        log.warn("SSH 服务自启失败（Mac）：systemctl/launchctl 均不可用");
        return false;
    }

    private static boolean startWindowsSshd() {
        if (runOk("powershell", "-NoProfile", "-Command", "Start-Service sshd -ErrorAction Stop")) {
            log.info("SSH 服务自启成功（Windows Start-Service sshd）");
            return true;
        }
        if (runOk("net", "start", "sshd")) {
            log.info("SSH 服务自启成功（Windows net start sshd）");
            return true;
        }
        log.warn("SSH 服务自启失败（Windows）：OpenSSH Server 未安装或无权限");
        return false;
    }

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
