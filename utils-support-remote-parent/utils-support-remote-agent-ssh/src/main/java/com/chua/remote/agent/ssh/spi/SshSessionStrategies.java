package com.chua.remote.agent.ssh.spi;

import com.chua.remote.agent.ssh.SshServiceProbe;

/**
 * SSH 会话策略选择器：按目标机探测结果（平台 + sshd 是否存在）选择匹配分支。
 *
 * <p>通过 {@code ServiceLoader} 发现实现——遍历匹配 {@link SshSessionStrategy#supports}，
 * 默认回退自启分支（保证任何机器可用）。</p>
 *
 * @author AtomCode
 */
public final class SshSessionStrategies {

    private SshSessionStrategies() {
    }

    /**
     * 选择适用的 SSH 会话策略。
     *
     * @param platform    平台
     * @param sshdPresent 本机是否已有 sshd
     * @return 匹配的策略（无匹配时回退自启分支）
     */
    public static SshSessionStrategy select(SshServiceProbe.Platform platform, boolean sshdPresent) {
        SshSessionStrategy fallback = new SelfSshdStrategy(platform, new com.chua.remote.agent.ssh.SshServiceManager());
        try {
            for (SshSessionStrategy strategy : java.util.ServiceLoader.load(SshSessionStrategy.class)) {
                if (strategy.supports(platform, sshdPresent)) {
                    return strategy;
                }
            }
        } catch (Exception ignored) {
            // SPI 发现失败——回退自启分支
        }
        return fallback;
    }
}
