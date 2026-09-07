package com.chua.remote.agent;

import com.chua.runtime.shell.TelnetServer;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * 套壳模式被控端实现。
 *
 * <p>Java 启动器内启动三方软件（freerdp/vnc/ssh），
 * 负责网网关注册 id、验证码等信息，媒体流转发到本地软件。
 * 同时启动 Telnet Shell 提供本地管理界面。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AgentShellService {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** SSH 隧道管理器 */
    private final SSHTunnelManager sshTunnelManager;

    /** Telnet Shell 服务器 */
    private final TelnetServer telnetServer;

    /** 是否运行中 */
    private volatile boolean running;

    /** 套壳模式类型 */
    private final ShellMode shellMode;

    public AgentShellService(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
        this.sshTunnelManager = new SSHTunnelManager(agentInfo);
        this.telnetServer = new TelnetServer();
        this.shellMode = determineShellMode();
    }

    /**
     * 确定套壳模式类型。
     */
    private ShellMode determineShellMode() {
        String platform = agentInfo.getPlatform();
        if (platform != null) {
            if (platform.toUpperCase().contains("RDP") || platform.toUpperCase().contains("WINDOWS")) {
                return ShellMode.RDP;
            } else if (platform.toUpperCase().contains("VNC")) {
                return ShellMode.VNC;
            }
        }
        return ShellMode.SSH;
    }

    /**
     * 启动套壳模式。
     */
    public void start() {
        running = true;
        registerToGateway();
        startTelnetServer();
        switch (shellMode) {
            case RDP:
                startRDPSession();
                break;
            case VNC:
                startVNCSession();
                break;
            case SSH:
            default:
                startSSHSession();
                break;
        }
        log.info("套壳模式已启动: agentId={}, mode={}", agentInfo.getId(), shellMode);
    }

    /** 启动 Telnet Shell 服务器 */
    private void startTelnetServer() {
        try {
            telnetServer.start(4567);
            log.info("Telnet Shell 已启动，监听端口: 4567");
        } catch (Exception e) {
            log.error("Telnet Shell 启动失败", e);
        }
    }

    /** 注册到网关 */
    private void registerToGateway() {
        log.info("套壳模式注册: agentId={}, accessCode={}, verifyCode={}, type={}",
                agentInfo.getId(), agentInfo.getAccessCode(), agentInfo.getVerifyCode(), agentInfo.getAgentType());
    }

    /** 启动 RDP 会话（freerdp + SSH 隧道） */
    private void startRDPSession() {
        String host = agentInfo.getHost();
        int port = agentInfo.getPort() > 0 ? agentInfo.getPort() : 3389;
        String username = agentInfo.getUsername();
        String password = agentInfo.getPassword();
        String sshHost = agentInfo.getPlatform();
        int sshPort = 22;
        String sshUser = agentInfo.getUsername();
        String sshPass = agentInfo.getPassword();
        int localPort = 3390;

        sshTunnelManager.createRDPTunnel(host, port, sshHost, sshPort, sshUser, sshPass, localPort);
        sshTunnelManager.startFreerdp(host, port, username, password);
        log.info("启动 RDP 会话: agentId={}, host={}:{}", agentInfo.getId(), host, port);
    }

    /** 启动 VNC 会话（vncviewer + SSH 隧道） */
    private void startVNCSession() {
        String host = agentInfo.getHost();
        int port = agentInfo.getPort() > 0 ? agentInfo.getPort() : 5900;
        String sshHost = agentInfo.getPlatform();
        int sshPort = 22;
        String sshUser = agentInfo.getUsername();
        String sshPass = agentInfo.getPassword();
        int localPort = 5901;

        sshTunnelManager.createVNCTunnel(host, port, sshHost, sshPort, sshUser, sshPass, localPort);
        sshTunnelManager.startVncViewer(host, port);
        log.info("启动 VNC 会话: agentId={}, host={}:{}", agentInfo.getId(), host, port);
    }

    /** 启动 SSH 会话（SSH 隧道 + 交互式终端） */
    private void startSSHSession() {
        String host = agentInfo.getHost();
        int port = agentInfo.getPort() > 0 ? agentInfo.getPort() : 22;
        String username = agentInfo.getUsername();
        String password = agentInfo.getPassword();
        int localPort = 2222;

        sshTunnelManager.createSSHTunnel(host, port, username, password, localPort);
        sshTunnelManager.startSshShellViaClient(host, port, username, password);
        log.info("启动 SSH 会话: agentId={}, host={}:{}", agentInfo.getId(), host, port);
    }

    /**
     * 停止套壳模式。
     */
    public void stop() {
        running = false;
        telnetServer.stop();
        sshTunnelManager.closeAll();
        log.info("套壳模式已停止: agentId={}", agentInfo.getId());
    }

    /**
     * 套壳模式类型枚举。
     */
    public enum ShellMode {
        /** RDP（freerdp） */
        RDP,
        /** VNC（vncviewer） */
        VNC,
        /** SSH（ssh 命令/PTY 终端） */
        SSH
    }
}
