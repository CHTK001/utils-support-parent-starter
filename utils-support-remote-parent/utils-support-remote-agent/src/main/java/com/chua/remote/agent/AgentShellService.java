package com.chua.remote.agent;

import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.runtime.shell.TelnetServer;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AgentShellService {

    private final AgentInfo agentInfo;
    private final SSHTunnelManager sshTunnelManager;
    private final TelnetServer telnetServer;
    private final RemoteClient client;
    private volatile boolean running;
    private final ShellMode shellMode;
    private int reverseTunnelPort;

    public AgentShellService(AgentInfo agentInfo, RemoteClient client) {
        this.agentInfo = agentInfo;
        this.client = client;
        this.sshTunnelManager = new SSHTunnelManager(agentInfo);
        this.telnetServer = new TelnetServer();
        this.shellMode = determineShellMode();
    }

    private ShellMode determineShellMode() {
        String platform = agentInfo.getPlatform();
        if (platform != null) {
            String upper = platform.toUpperCase();
            if (upper.contains("WINDOWS")) {
                return ShellMode.RDP;
            } else if (upper.contains("VNC")) {
                return ShellMode.VNC;
            }
        }
        return ShellMode.SSH;
    }

    public void start() {
        running = true;
        startTelnetServer();
        createReverseTunnel();
        reRegisterToGateway();
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
        log.info("套壳模式已启动: agentId={}, mode={}, reverseTunnelPort={}",
                agentInfo.getId(), shellMode, reverseTunnelPort);
    }

    private void createReverseTunnel() {
        if (agentInfo.getGatewaySshHost() == null) {
            log.warn("未配置网关 SSH 信息，跳过反向隧道创建");
            return;
        }
        int gatewayLocalPort = findReverseTunnelPort();
        int agentLocalPort = getAgentServicePort();
        sshTunnelManager.createReverseTunnel(gatewayLocalPort, "127.0.0.1", agentLocalPort);
        reverseTunnelPort = sshTunnelManager.getReverseTunnelPort();
        if (reverseTunnelPort > 0) {
            log.info("反向隧道已建立: 网关 {}:{} ←→ agent 127.0.0.1:{}",
                    agentInfo.getGatewaySshHost(), reverseTunnelPort, agentLocalPort);
            if (agentInfo.getExtra() == null) {
                agentInfo.setExtra(new java.util.HashMap<>());
            }
            agentInfo.getExtra().put("reverseTunnelPort", String.valueOf(reverseTunnelPort));
            agentInfo.getExtra().put("gatewayLocalPort", String.valueOf(reverseTunnelPort));
        }
    }

    private void reRegisterToGateway() {
        var frame = FrameCodec.encodeSignal(MessageType.SIGNAL, agentInfo.getId(), agentInfo);
        client.getTransport().send(frame);
        log.info("套壳模式重新注册到网关: agentId={}, reverseTunnelPort={}",
                agentInfo.getId(), reverseTunnelPort);
    }

    private int getAgentServicePort() {
        switch (shellMode) {
            case RDP: return sshTunnelManager.getRdpLocalPort();
            case VNC: return sshTunnelManager.getVncLocalPort();
            case SSH: return sshTunnelManager.getSshLocalPort();
            default: return 0;
        }
    }

    private int findReverseTunnelPort() {
        switch (shellMode) {
            case RDP: return 3391;
            case VNC: return 5902;
            case SSH: return 2223;
            default: return 50000;
        }
    }

    private void startTelnetServer() {
        try {
            telnetServer.start(4567);
            log.info("Telnet Shell 已启动，监听端口: 4567");
        } catch (Exception e) {
            log.error("Telnet Shell 启动失败", e);
        }
    }

    private void startRDPSession() {
        String host = agentInfo.getHost();
        int port = agentInfo.getPort() > 0 ? agentInfo.getPort() : 3389;
        String username = agentInfo.getUsername();
        String password = agentInfo.getPassword();
        sshTunnelManager.createRDPTunnel(host, port, agentInfo.getPlatform(), 22, username, password, 3390);
        sshTunnelManager.startFreerdp(host, port, username, password);
        log.info("启动 RDP 会话: agentId={}, host={}:{}", agentInfo.getId(), host, port);
    }

    private void startVNCSession() {
        String host = agentInfo.getHost();
        int port = agentInfo.getPort() > 0 ? agentInfo.getPort() : 5900;
        String username = agentInfo.getUsername();
        String password = agentInfo.getPassword();
        sshTunnelManager.createVNCTunnel(host, port, agentInfo.getPlatform(), 22, username, password, 5901);
        sshTunnelManager.startVncViewer(host, port);
        log.info("启动 VNC 会话: agentId={}, host={}:{}", agentInfo.getId(), host, port);
    }

    private void startSSHSession() {
        String host = agentInfo.getHost();
        int port = agentInfo.getPort() > 0 ? agentInfo.getPort() : 22;
        String username = agentInfo.getUsername();
        String password = agentInfo.getPassword();
        sshTunnelManager.createSSHTunnel(host, port, username, password, 2222);
        sshTunnelManager.startSshShellViaClient(host, port, username, password);
        log.info("启动 SSH 会话: agentId={}, host={}:{}", agentInfo.getId(), host, port);
    }

    public void stop() {
        running = false;
        telnetServer.stop();
        sshTunnelManager.closeAll();
        log.info("套壳模式已停止: agentId={}", agentInfo.getId());
    }

    public int getReverseTunnelPort() {
        return reverseTunnelPort;
    }

    public enum ShellMode {
        RDP,
        VNC,
        SSH
    }
}
