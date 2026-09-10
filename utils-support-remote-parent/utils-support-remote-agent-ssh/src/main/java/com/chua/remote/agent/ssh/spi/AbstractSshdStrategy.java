package com.chua.remote.agent.ssh.spi;

import com.chua.remote.agent.ssh.SshServiceProbe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH 会话策略抽象基类——双分支共用的 ssh -tt 套壳启动逻辑（真实 pty——top/vim 可用）。
 *
 * <p>agent 部署在目标机上：SSH 会话连接本机 sshd（{@code 127.0.0.1}），
 * 免密公钥认证（{@code ssh -i}）——不依赖账号密码。</p>
 *
 * @author AtomCode
 */
public abstract class AbstractSshdStrategy implements SshSessionStrategy {

    protected final SshServiceProbe.Platform platform;

    protected AbstractSshdStrategy(SshServiceProbe.Platform platform) {
        this.platform = platform;
    }

    @Override
    public Process startShell(String keyPath, int cols, int rows) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveSshExecutable());
        cmd.add("-i");
        cmd.add(keyPath);
        cmd.add("-tt");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o");
        cmd.add("UserKnownHostsFile=/dev/null");
        cmd.add("-o");
        cmd.add("PreferredAuthentications=publickey");
        // agent 在目标机上——连本机 sshd（免密公钥——当前系统用户）
        cmd.add("127.0.0.1");
        if (platform != SshServiceProbe.Platform.WINDOWS) {
            cmd.add("stty cols " + cols + " rows " + rows + "; exec bash -l");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // TERM 必须显式设置（Windows 启动的 agent 环境常缺——远端 top/vim/htop 依赖它）
        pb.environment().putIfAbsent("TERM", "xterm-256color");
        return pb.start();
    }

    /**
     * 解析 ssh 可执行文件：Windows 优先系统内置 OpenSSH（避免 Git 发行版 ssh.exe
     * 子进程缺 DLL（"error while loading sha..."）崩溃）。
     */
    private String resolveSshExecutable() {
        if (platform == SshServiceProbe.Platform.WINDOWS) {
            File winSsh = new File("C:\\Windows\\System32\\OpenSSH\\ssh.exe");
            if (winSsh.isFile()) {
                return winSsh.getAbsolutePath();
            }
        }
        return "ssh";
    }
}
