package com.chua.remote.agent;

import com.chua.ssh.support.client.SshClient;
import com.chua.ssh.support.client.SshTunnel;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * SSH 隧道管理器。
 *
 * <p>管理套壳模式下与本地三方软件（freerdp/vnc/ssh）的端口转发和隧道连接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SSHTunnelManager {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** SSH 客户端 */
    private SshClient sshClient;

    /** RDP 隧道 */
    private SshTunnel rdpTunnel;

    /** VNC 隧道 */
    private SshTunnel vncTunnel;

    /** SSH 隧道 */
    private SshTunnel sshTunnel;

    /** 本地转发端口 */
    private int rdpLocalPort;
    private int vncLocalPort;
    private int sshLocalPort;

    public SSHTunnelManager(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
    }

    /**
     * 创建 RDP 隧道（通过 SSH 转发到远程 RDP 服务）。
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
        log.info("创建 RDP 隧道: agentId={}, host={}:{}, ssh={}:{}, localPort={}",
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
            log.info("RDP 隧道已建立: localPort={} → {}:{}", localPort, host, port);
        } catch (Exception e) {
            log.error("创建 RDP 隧道失败", e);
        }
    }

    /**
     * 创建 VNC 隧道。
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
        log.info("创建 VNC 隧道: agentId={}, host={}:{}, ssh={}:{}, localPort={}",
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
            log.info("VNC 隧道已建立: localPort={} → {}:{}", localPort, host, vncPort);
        } catch (Exception e) {
            log.error("创建 VNC 隧道失败", e);
        }
    }

    /**
     * 创建 SSH 隧道并启动交互式终端。
     *
     * @param host     SSH 目标主机
     * @param sshPort  SSH 端口
     * @param sshUser  SSH 账号
     * @param sshPass  SSH 密码
     * @param localPort 本地转发端口
     */
    public void createSSHTunnel(String host, int sshPort, String sshUser, String sshPass, int localPort) {
        log.info("创建 SSH 隧道: agentId={}, host={}:{}, localPort={}",
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
            log.info("SSH 隧道已建立: localPort={}", localPort);
        } catch (Exception e) {
            log.error("创建 SSH 隧道失败", e);
        }
    }

    /**
     * 启动 freerdp 进程（通过已建立的 SSH 隧道连接）。
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
     * 启动 vncviewer 进程（通过已建立的 SSH 隧道连接）。
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
     * 启动 ssh 交互式终端（通过已建立的 SSH 隧道连接）。
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

    /**
     * 关闭所有隧道和进程。
     */
    public void closeAll() {
        if (rdpTunnel != null) {
            try { rdpTunnel.close(); } catch (Exception e) { log.warn("关闭 RDP 隧道失败", e); }
        }
        if (vncTunnel != null) {
            try { vncTunnel.close(); } catch (Exception e) { log.warn("关闭 VNC 隧道失败", e); }
        }
        if (sshTunnel != null) {
            try { sshTunnel.close(); } catch (Exception e) { log.warn("关闭 SSH 隧道失败", e); }
        }
        if (sshClient != null) {
            try { sshClient.disconnect(); } catch (Exception e) { log.warn("断开 SSH 客户端失败", e); }
        }
        log.info("所有隧道已关闭: agentId={}", agentInfo.getId());
    }

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
