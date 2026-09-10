package com.chua.remote.agent.ssh.spi;

import com.chua.remote.agent.ssh.SshServiceProbe;

/**
 * 分支 A：目标机已有 sshd 服务在运行——复用系统 sshd 套壳（转发模式）。
 *
 * <p>不重复起服务——直接 ssh -tt 套壳（真实 pty）。</p>
 *
 * @author AtomCode
 */
public class SystemSshdStrategy extends AbstractSshdStrategy {

    public SystemSshdStrategy() {
        this(SshServiceProbe.detectPlatform());
    }

    public SystemSshdStrategy(SshServiceProbe.Platform platform) {
        super(platform);
    }

    @Override
    public boolean supports(SshServiceProbe.Platform p, boolean sshdPresent) {
        return sshdPresent;
    }

    @Override
    public boolean ensureReady() {
        // 已有 sshd 服务在运行——无需额外准备（探测时已确认）
        return true;
    }
}
