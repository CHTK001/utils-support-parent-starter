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

    /** 隧道 */
    private SshTunnel currentTunnel;

    public SSHTunnelManager(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
    }

    /**
     * 创建 RDP 隧道（通过 freerdp）。
     *
     * @param host     RDP 服务器地址
     * @param port     RDP 端口
     * @param localPort 本地转发端口
     * @param username 账号
     * @param password 密码
     */
    public void createRDPTunnel(String host, int port, int localPort, String username, String password) {
        log.info("创建 RDP 隧道: agentId={}, host={}, port={}, localPort={}",
                agentInfo.getId(), host, port, localPort);
        try {
            sshClient = SshClient.builder()
                    .host(host)
                    .port(port)
                    .username(username)
                    .password(password)
                    .build();
            sshClient.connect();
            // 正向隧道：本地端口 -> 远程 RDP 服务
            SshClient.TunnelDefinition def = SshClient.TunnelDefinition.local(localPort, "127.0.0.1", port);
            currentTunnel = new SshTunnel(sshClient, def, "127.0.0.1");
            currentTunnel.open();
            log.info("RDP 隧道已建立: localPort={}", localPort);
        } catch (Exception e) {
            log.error("创建 RDP 隧道失败", e);
        }
    }

    /**
     * 创建 VNC 隧道。
     *
     * @param host      VNC 服务器地址
     * @param vncPort   VNC 端口
     * @param localPort 本地转发端口
     * @param username  账号
     * @param password  密码
     */
    public void createVNCTunnel(String host, int vncPort, int localPort, String username, String password) {
        log.info("创建 VNC 隧道: agentId={}, host={}, vncPort={}, localPort={}",
                agentInfo.getId(), host, vncPort, localPort);
        try {
            sshClient = SshClient.builder()
                    .host(host)
                    .port(vncPort)
                    .username(username)
                    .password(password)
                    .build();
            sshClient.connect();
            SshClient.TunnelDefinition def = SshClient.TunnelDefinition.local(localPort, "127.0.0.1", vncPort);
            currentTunnel = new SshTunnel(sshClient, def, "127.0.0.1");
            currentTunnel.open();
            log.info("VNC 隧道已建立: localPort={}", localPort);
        } catch (Exception e) {
            log.error("创建 VNC 隧道失败", e);
        }
    }

    /**
     * 创建 SSH 隧道。
     *
     * @param host      SSH 服务器地址
     * @param sshPort   SSH 端口
     * @param localPort 本地转发端口
     * @param username  账号
     * @param password  密码
     */
    public void createSSHTunnel(String host, int sshPort, int localPort, String username, String password) {
        log.info("创建 SSH 隧道: agentId={}, host={}, sshPort={}, localPort={}",
                agentInfo.getId(), host, sshPort, localPort);
        try {
            sshClient = SshClient.builder()
                    .host(host)
                    .port(sshPort)
                    .username(username)
                    .password(password)
                    .build();
            sshClient.connect();
            SshClient.TunnelDefinition def = SshClient.TunnelDefinition.local(localPort, "127.0.0.1", sshPort);
            currentTunnel = new SshTunnel(sshClient, def, "127.0.0.1");
            currentTunnel.open();
            log.info("SSH 隧道已建立: localPort={}", localPort);
        } catch (Exception e) {
            log.error("创建 SSH 隧道失败", e);
        }
    }

    /**
     * 启动 freerdp 进程。
     *
     * @param host     RDP 主机地址
     * @param port     RDP 端口
     * @param username 账号
     * @param password 密码
     */
    public void startFreerdp(String host, int port, String username, String password) {
        log.info("启动 freerdp: agentId={}, host={}, port={}", agentInfo.getId(), host, port);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "freerdp", "/v:" + host + ":" + port, "/u:" + username, "/p:" + password);
            pb.inheritIO();
            Process process = pb.start();
            log.info("freerdp 进程已启动: pid={}", process.pid());
        } catch (Exception e) {
            log.error("启动 freerdp 失败", e);
        }
    }

    /**
     * 启动 vnc 客户端进程。
     *
     * @param host vnc 主机地址
     * @param port vnc 端口
     */
    public void startVncViewer(String host, int port) {
        log.info("启动 vncviewer: agentId={}, host={}, port={}", agentInfo.getId(), host, port);
        try {
            ProcessBuilder pb = new ProcessBuilder("vncviewer", host + ":" + port);
            pb.inheritIO();
            Process process = pb.start();
            log.info("vncviewer 进程已启动: pid={}", process.pid());
        } catch (Exception e) {
            log.error("启动 vncviewer 失败", e);
        }
    }

    /**
     * 启动 ssh 连接。
     *
     * @param host     SSH 主机地址
     * @param port     SSH 端口
     * @param username 账号
     */
    public void startSshShell(String host, int port, String username) {
        log.info("启动 ssh: agentId={}, host={}, port={}", agentInfo.getId(), host, port);
        try {
            ProcessBuilder pb = new ProcessBuilder("ssh", "-p", String.valueOf(port), username + "@" + host);
            pb.inheritIO();
            Process process = pb.start();
            log.info("ssh 进程已启动: pid={}", process.pid());
        } catch (Exception e) {
            log.error("启动 ssh 失败", e);
        }
    }

    /**
     * 关闭所有隧道和进程。
     */
    public void closeAll() {
        if (currentTunnel != null) {
            try {
                currentTunnel.close();
            } catch (Exception e) {
                log.warn("关闭隧道失败", e);
            }
        }
        if (sshClient != null) {
            try {
                sshClient.disconnect();
            } catch (Exception e) {
                log.warn("断开 SSH 客户端失败", e);
            }
        }
        log.info("所有隧道已关闭: agentId={}", agentInfo.getId());
    }
}
