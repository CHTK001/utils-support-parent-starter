package com.chua.remote.agent.ssh;

import lombok.extern.slf4j.Slf4j;

/**
 * SSH 服务供给管理器（平台分支核心）。
 *
 * <p>按平台保证本机 SSH 服务可达：</p>
 * <ul>
 *   <li><b>Linux</b>：已有 sshd 服务（运行中）→ 转发模式（复用已有，不重复起）；
 *       无 sshd 服务 → 自启 sshd（{@link SshServerStarter} 拉起）</li>
 *   <li><b>Mac</b>（类 Unix）：与 Linux 相同逻辑</li>
 *   <li><b>Windows</b>：自身不具备 SSH 服务 → 直接自启 SSH 服务器</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SshServiceManager {

    private final SshServiceProbe.Platform platform;
    private final boolean sshdPresent;

    public SshServiceManager() {
        this.platform = SshServiceProbe.detectPlatform();
        this.sshdPresent = SshServiceProbe.hasSshdService(platform);
        log.info("SSH 服务采集: platform={}, sshdPresent={}（{}）",
                platform, sshdPresent, sshdPresent ? "转发模式——复用已有 sshd" : "需自启 SSH 服务");
    }

    /**
     * 确保本机 SSH 服务可达。
     *
     * @return true=SSH 服务就绪（转发复用已有 / 自启成功）；false=不可用
     */
    public boolean ensureSshService() {
        if (sshdPresent) {
            return true;
        }
        return SshServerStarter.startSshServer(platform);
    }

    /**
     * 平台类型。
     */
    public SshServiceProbe.Platform getPlatform() {
        return platform;
    }

    /**
     * 是否转发模式（本机已有 sshd 服务——复用而非自启）。
     */
    public boolean isForwardMode() {
        return sshdPresent;
    }
}
