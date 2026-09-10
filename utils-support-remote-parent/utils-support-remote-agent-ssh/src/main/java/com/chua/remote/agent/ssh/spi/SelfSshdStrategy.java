package com.chua.remote.agent.ssh.spi;

import com.chua.remote.agent.ssh.SshServiceManager;
import com.chua.remote.agent.ssh.SshServiceProbe;

/**
 * 分支 B：目标机无 sshd 服务——agent 自启 sshd（{@link SshServiceManager#ensureSshService()} 拉起）后套壳。
 *
 * @author AtomCode
 */
public class SelfSshdStrategy extends AbstractSshdStrategy {

    private final SshServiceManager serviceManager;

    public SelfSshdStrategy() {
        this(SshServiceProbe.detectPlatform(), new SshServiceManager());
    }

    public SelfSshdStrategy(SshServiceProbe.Platform platform, SshServiceManager serviceManager) {
        super(platform);
        this.serviceManager = serviceManager;
    }

    @Override
    public boolean supports(SshServiceProbe.Platform p, boolean sshdPresent) {
        return !sshdPresent;
    }

    @Override
    public boolean ensureReady() {
        // 无 sshd——自启（SshServerStarter 拉起）——起不来则不可用
        return serviceManager.ensureSshService();
    }
}
