package com.chua.remote.agent.ssh.spi;

import com.chua.remote.agent.ssh.SshServiceProbe;

import java.io.IOException;

/**
 * SSH 会话启动策略 SPI（双分支：复用已有 sshd 套壳 / agent 自启 sshd）。
 *
 * <p>目标机（agent 所在机器）可能两种情况：</p>
 * <ul>
 *   <li>已有 sshd 服务在运行——复用系统 sshd（套壳——真实 pty，top/vim 可用）</li>
 *   <li>无 sshd 服务——agent 自启 sshd（{@code SshServerStarter} 拉起）后套壳</li>
 * </ul>
 * 通过 {@code META-INF/services/com.chua.remote.agent.ssh.spi.SshSessionStrategy} 注册，
 * 由 {@link SshSessionStrategies} 按探测结果选择匹配分支。
 *
 * @author AtomCode
 */
public interface SshSessionStrategy {

    /**
     * 分支匹配：该策略是否适用于当前平台 + sshd 存在状态。
     *
     * @param platform    平台（LINUX/MAC/WINDOWS/OTHER）
     * @param sshdPresent 本机是否已有 sshd 服务在运行
     * @return 是否适用
     */
    boolean supports(SshServiceProbe.Platform platform, boolean sshdPresent);

    /**
     * 确保 SSH 服务可用（复用检查 / 自启拉起）。
     *
     * @return true=服务可用
     */
    boolean ensureReady();

    /**
     * 启动 SSH 会话进程（ssh -tt 真实 pty——top/vim 可用）。
     *
     * @param keyPath 免密公钥路径（ssh -i）
     * @param cols    终端列数
     * @param rows    终端行数
     * @return 会话进程
     * @throws IOException 启动失败
     */
    Process startShell(String keyPath, int cols, int rows) throws IOException;
}
