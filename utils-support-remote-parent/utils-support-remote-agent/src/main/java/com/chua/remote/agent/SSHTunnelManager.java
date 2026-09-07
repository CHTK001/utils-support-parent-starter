package com.chua.remote.agent;

import com.chua.ssh.support.client.SshClient;
import com.chua.ssh.support.client.SshTunnel;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * SSH 隧道管理器。
 *
 * <p>管理套壳模式下的正向隧道（agent→目标）和反向隧道（agent→网关）连接。
 * 反向隧道解决 agent 在 NAT/防火墙后、网关无法主动连 agent 的问题：
 * agent 主动创建反向隧道到网关，网关通过隧道的本地端口反向访问 agent。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SSHTunnelManager {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** SSH 客户端（用于正向隧道） */
    private SshClient sshClient;

    /** SSH 客户端（用于反向隧道——连接到网关） */
    private SshClient reverseTunnelClient;

    /** RDP 正向隧道 */
    private SshTunnel rdpTunnel;

    /** VNC 正向隧道 */
    private SshTunnel vncTunnel;

    /** SSH 正向隧道 */
    private SshTunnel sshTunnel;

    /** 反向隧道 */
    private SshTunnel reverseTunnel;

    /** 本地转发端口 */
    private int rdpLocalPort;
    private int vncLocalPort;
    private int sshLocalPort;

    /** 反向隧道端口（网关通过此端口访问 agent） */
    private int reverseTunnelPort;

    public SSHTunnelManager(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
    }

    // ==================== 正向隧道（agent→目标） ====================

    /**
     * 创建 RDP 正向隧道（通过 SSH 转发到远程 RDP 服务）。
     *
     * @param host     RDP 服务器地址
     * @param port     RDP 端口
     * @param sshHost  SSH 跳板机地址
     * @param sshPort  SSH 端口
     * @param sshUser  SSH 账号
     * @param sshPass  SSH 密码
     * @param localPort 本地转发端口
     */
    public void createRDPTunnel(String host, int port, String sshHost, int sshPort,
                                   String sshUser, String sshPass, int localPort) {
        log.info("创建 RDP 正向隧道: agentId={}, host={}:{}, ssh={}:{}, localPort={}",
                agentInfo.getId(), host, port, sshHost, sshPort, localPort);
        try {
            sshClient = SshClient.builder()
                    .host(sshHost).port(sshPort)
                    .username(sshUser).password(sshPass)
                    .build();
            sshClient.connect();
            rdpTunnel = new SshTunnel(sshClient,
                    SshClient.TunnelDefinition.local(localPort, host, port), "127.0.0.1");
            rdpTunnel.open();
            rdpLocalPort = localPort;
            log.info("RDP 正向隧道已建立: localPort={} → {}:{}", localPort, host, port);
        } catch (Exception e) {
            log.error("创建 RDP 正向隧道失败", e);
        }
    }

    /**
     * 创建 VNC 正向隧道。
     *
     * @param host      VNC 服务器地址
     * @param vncPort   VNC 端口
     * @param sshHost   SSH 跳板机地址
     * @param sshPort   SSH 端口
     * @param sshUser   SSH 账号
     * @param sshPass   SSH 密码
     * @param localPort 本地转发端口
     */
    public void createVNCTunnel(String host, int vncPort, String sshHost, int sshPort,
                                   String sshUser, String sshPass, int localPort) {
        log.info("创建 VNC 正向隧道: agentId={}, host={}:{}, ssh={}:{}, localPort={}",
                agentInfo.getId(), host, vncPort, sshHost, sshPort, localPort);
        try {
            sshClient = SshClient.builder()
                    .host(sshHost).port(sshPort)
                    .username(sshUser).password(sshPass)
                    .build();
            sshClient.connect();
            vncTunnel = new SshTunnel(sshClient,
                    SshClient.TunnelDefinition.local(localPort, host, vncPort), "127.0.0.1");
            vncTunnel.open();
            vncLocalPort = localPort;
            log.info("VNC 正向隧道已建立: localPort={} → {}:{}", localPort, host, vncPort);
        } catch (Exception e) {
            log.error("创建 VNC 正向隧道失败", e);
        }
    }

    /**
     * 创建 SSH 正向隧道并启动交互式终端。
     *
     * @param host     SSH 目标主机
     * @param sshPort  SSH 端口
     * @param sshUser  SSH 账号
     * @param sshPass  SSH 密码
     * @param localPort 本地转发端口
     */
    public void createSSHTunnel(String host, int sshPort, String sshUser, String sshPass, int localPort) {
        log.info("创建 SSH 正向隧道: agentId={}, host={}:{}, localPort={}",
                agentInfo.getId(), host, sshPort, localPort);
        try {
            sshClient = SshClient.builder()
                    .host(host).port(sshPort)
                    .username(sshUser).password(sshPass)
                    .build();
            sshClient.connect();
            sshTunnel = new SshTunnel(sshClient,
                    SshClient.TunnelDefinition.local(localPort, host, sshPort), "127.0.0.1");
            sshTunnel.open();
            sshLocalPort = localPort;
            log.info("SSH 正向隧道已建立: localPort={}", localPort);
        } catch (Exception e) {
            log.error("创建 SSH 正向隧道失败", e);
        }
    }

    // ==================== 反向隧道（agent→网关） ====================

    /**
     * 创建反向 SSH 隧道到网关。
     *
     * <p>agent 主动连接到网关的 SSH 服务器，创建反向隧道：
     * 网关的 {@code gatewayLocalPort} 端口 → agent 的 {@code agentHost}:{@code agentPort}。
     * 网关通过此本地端口即可反向访问 agent 上的服务。</p>
     *
     * @param gatewayHost     网关 SSH 主机地址
     * @param gatewayPort     网关 SSH 端口
     * @param gatewayUser     网关 SSH 账号
     * @param gatewayPass     网关 SSH 密码
     * @param gatewayLocalPort 网关本地端口（网关通过此端口连接 agent）
     * @param agentHost       agent 上的服务地址（通常是 127.0.0.1）
     * @param agentPort       agent 上的服务端口
     */
    public void createReverseTunnel(String gatewayHost, int gatewayPort,
                                        String gatewayUser, String gatewayPass,
                                        int gatewayLocalPort, String agentHost, int agentPort) {
        log.info("创建反向隧道: agentId={}, gateway={}:{}, gatewayLocalPort={}, agent={}:{}",
                agentInfo.getId(), gatewayHost, gatewayPort, gatewayLocalPort, agentHost, agentPort);
        try {
            reverseTunnelClient = SshClient.builder()
                    .host(gatewayHost).port(gatewayPort)
                    .username(gatewayUser).password(gatewayPass)
                    .build();
            reverseTunnelClient.connect();
            // 反向隧道：网关的 gatewayLocalPort → agentHost:agentPort
            // SshClient.TunnelDefinition.remote(remotePort, localHost, localPort)
            // 表示：远程端口 remotePort 映射到本地 localHost:localPort
            // 对于网关来说，remotePort 就是 gatewayLocalPort，localHost:localPort 是 agentHost:agentPort
            reverseTunnel = new SshTunnel(reverseTunnelClient,
                    SshClient.TunnelDefinition.remote(gatewayLocalPort, agentHost, agentPort),
                    "127.0.0.1");
            reverseTunnel.open();
            reverseTunnelPort = gatewayLocalPort;
            log.info("反向隧道已建立: 网关 {}:{} ←→ agent {}:{}", gatewayHost, gatewayLocalPort, agentHost, agentPort);
        } catch (Exception e) {
            log.error("创建反向隧道失败", e);
        }
    }

    /**
     * 创建反向隧道（使用 AgentInfo 中的网关 SSH 配置）。
     *
     * @param gatewayLocalPort 网关本地端口
     * @param agentHost        agent 上的服务地址
     * @param agentPort        agent 上的服务端口
     */
    public void createReverseTunnel(int gatewayLocalPort, String agentHost, int agentPort) {
        String gatewayHost = agentInfo.getGatewaySshHost();
        int gatewayPort = agentInfo.getGatewaySshPort() > 0 ? agentInfo.getGatewaySshPort() : 22;
        String gatewayUser = agentInfo.getGatewaySshUser();
        String gatewayPass = agentInfo.getGatewaySshPass();
        createReverseTunnel(gatewayHost, gatewayPort, gatewayUser, gatewayPass, gatewayLocalPort, agentHost, agentPort);
    }

    /**
     * 获取反向隧道端口（网关通过此端口连接 agent）。
     */
    public int getReverseTunnelPort() {
        return reverseTunnelPort;
    }

    // ==================== 客户端启动 ====================

    /**
     * 启动 freerdp 进程（通过已建立的 SSH 正向隧道连接）。
     *
     * @param host     RDP 主机地址（远程）
     * @param port     RDP 端口
     * @param username 账号
     * @param password 密码
     */
    public void startFreerdp(String host, int port, String username, String password) {
        log.info("启动 freerdp: agentId={}, host={}:{}, username={}", agentInfo.getId(), host, port, username);
        int localPort = rdpLocalPort > 0 ? rdpLocalPort : findAvailablePort();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "freerdp", "/v:127.0.0.1:" + localPort, "/u:" + username, "/p:" + password);
            pb.inheritIO();
            Process process = pb.start();
            log.info("freerdp 进程已启动: pid={}, localPort={}", process.pid(), localPort);
        } catch (Exception e) {
            log.error("启动 freerdp 失败", e);
        }
    }

    /**
     * 启动 vncviewer 进程（通过已建立的 SSH 正向隧道连接）。
     *
     * @param host vnc 主机地址（远程）
     * @param port vnc 端口
     */
    public void startVncViewer(String host, int port) {
        log.info("启动 vncviewer: agentId={}, host={}:{}", agentInfo.getId(), host, port);
        int localPort = vncLocalPort > 0 ? vncLocalPort : findAvailablePort();
        try {
            ProcessBuilder pb = new ProcessBuilder("vncviewer", "127.0.0.1:" + localPort);
            pb.inheritIO();
            Process process = pb.start();
            log.info("vncviewer 进程已启动: pid={}, localPort={}", process.pid(), localPort);
        } catch (Exception e) {
            log.error("启动 vncviewer 失败", e);
        }
    }

    /**
     * 启动 ssh 交互式终端（通过已建立的 SSH 正向隧道连接）。
     *
     * @param host     SSH 主机地址（远程）
     * @param port     SSH 端口
     * @param username 账号
     */
    public void startSshShell(String host, int port, String username) {
        log.info("启动 SSH 交互式终端: agentId={}, host={}:{}, username={}", agentInfo.getId(), host, port, username);
        int localPort = sshLocalPort > 0 ? sshLocalPort : findAvailablePort();
        try {
            ProcessBuilder pb = new ProcessBuilder("ssh", "-p", String.valueOf(localPort), username + "@127.0.0.1");
            pb.inheritIO();
            Process process = pb.start();
            log.info("ssh 进程已启动: pid={}, localPort={}", process.pid(), localPort);
        } catch (Exception e) {
            log.error("启动 ssh 失败", e);
        }
    }

    /**
     * 通过 SSH 客户端的终端功能启动交互式 Shell。
     *
     * @param host     SSH 主机地址
     * @param port     SSH 端口
     * @param username 账号
     * @param password 密码
     */
    public void startSshShellViaClient(String host, int port, String username, String password) {
        log.info("启动 SSH PTY 终端: agentId={}, host={}:{}, username={}", agentInfo.getId(), host, port, username);
        try {
            if (sshClient == null || !sshClient.isConnected()) {
                sshClient = SshClient.builder()
                        .host(host).port(port)
                        .username(username).password(password)
                        .build();
                sshClient.connect();
            }
            sshClient.terminal().connect();
            log.info("SSH PTY 终端已连接: {}@{}:{}", username, host, port);
        } catch (Exception e) {
            log.error("启动 SSH PTY 终端失败", e);
        }
    }

    // ==================== 关闭 ====================

    /**
     * 关闭所有隧道和进程。
     */
    public void closeAll() {
        // 先关闭反向隧道
        if (reverseTunnel != null) {
            try { reverseTunnel.close(); } catch (Exception e) { log.warn("关闭反向隧道失败", e); }
        }
        if (reverseTunnelClient != null) {
            try { reverseTunnelClient.disconnect(); } catch (Exception e) { log.warn("断开反向 SSH 客户端失败", e); }
        }
        // 再关闭正向隧道
        if (rdpTunnel != null) {
            try { rdpTunnel.close(); } catch (Exception e) { log.warn("关闭 RDP 正向隧道失败", e); }
        }
        if (vncTunnel != null) {
            try { vncTunnel.close(); } catch (Exception e) { log.warn("关闭 VNC 正向隧道失败", e); }
        }
        if (sshTunnel != null) {
            try { sshTunnel.close(); } catch (Exception e) { log.warn("关闭 SSH 正向隧道失败", e); }
        }
        if (sshClient != null) {
            try { sshClient.disconnect(); } catch (Exception e) { log.warn("断开 SSH 客户端失败", e); }
        }
        log.info("所有隧道已关闭: agentId={}", agentInfo.getId());
    }

    // ==================== 工具 ====================

    /**
     * 查找可用本地端口。
     */
    private int findAvailablePort() {
        try {
            java.net.ServerSocket socket = new java.net.ServerSocket(0);
            int port = socket.getLocalPort();
            socket.close();
            return port;
        } catch (Exception e) {
            return 50000;
        }
    }

    /**
     * 获取 RDP 本地端口。
     */
    public int getRdpLocalPort() {
        return rdpLocalPort;
    }

    /**
     * 获取 VNC 本地端口。
     */
    public int getVncLocalPort() {
        return vncLocalPort;
    }

    /**
     * 获取 SSH 本地端口。
     */
    public int getSshLocalPort() {
        return sshLocalPort;
    }
}
