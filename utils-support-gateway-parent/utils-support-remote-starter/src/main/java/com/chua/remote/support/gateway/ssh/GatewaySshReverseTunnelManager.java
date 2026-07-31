package com.chua.remote.support.gateway.ssh;

import com.chua.remote.support.gateway.config.GatewayProperties;
import com.chua.ssh.support.client.SshClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 网关侧 SSH 反向隧道管理器。
 *
 * <p>Gateway 主动 SSH 到远程服务器，建立反向端口映射，
 * 使得服务器上的 Agent 可以通过访问远程隧道端口连接到 Gateway 的 agent TCP 端口。
 *
 * @author CH
 */
@Slf4j
public class GatewaySshReverseTunnelManager {

    private final GatewayProperties props;
    private SshClient sshClient;
    private AutoCloseable tunnelTracker;
    private int assignedPort = -1;

    public GatewaySshReverseTunnelManager(GatewayProperties props) {
        this.props = props;
    }

    /**
     * 建立 SSH 反向隧道。
     *
     * @return 远程监听端口（即 Agent 应连接的端口）
     * @throws Exception 如果隧道建立失败
     */
    public int open() throws Exception {
        if (!props.isSshReverseTunnelEnabled()) {
            log.info("[GatewaySshTunnel] SSH 反向隧道未启用，跳过");
            return -1;
        }

        String remoteHost = props.getSshRemoteHost();
        int remotePort = props.getSshRemotePort();
        int remoteListenPort = props.getSshReverseRemotePort();
        int localAgentPort = props.getSshReverseLocalPort() > 0
                ? props.getSshReverseLocalPort()
                : props.getTcpAgentPort();

        log.info("[GatewaySshTunnel] 建立反向隧道: 远程 {}:{} -> 127.0.0.1:{}",
                remoteHost, remoteListenPort, localAgentPort);

        sshClient = SshClient.builder()
                .host(remoteHost)
                .port(remotePort)
                .username(props.getSshUsername())
                .password(props.getSshPassword())
                .privateKey(props.getSshKeyFile())
                .build();

        sshClient.connect();
        tunnelTracker = sshClient.forward()
                .remote(remoteListenPort, "127.0.0.1", localAgentPort)
                .start();

        assignedPort = remoteListenPort;
        log.info("[GatewaySshTunnel] SSH 反向隧道建立成功: 远端 {}:{} -> 127.0.0.1:{}",
                remoteHost, remoteListenPort, localAgentPort);
        return assignedPort;
    }

    /**
     * 关闭 SSH 反向隧道。
     */
    public void close() {
        if (tunnelTracker != null) {
            try {
                tunnelTracker.close();
                log.info("[GatewaySshTunnel] SSH 反向隧道已关闭");
            } catch (Exception e) {
                log.warn("[GatewaySshTunnel] 关闭隧道时异常: {}", e.getMessage());
            }
        }
        if (sshClient != null) {
            sshClient.disconnect();
        }
        tunnelTracker = null;
        sshClient = null;
        assignedPort = -1;
    }

    public boolean isOpen() {
        return sshClient != null && tunnelTracker != null;
    }

    public int getAssignedPort() {
        return assignedPort;
    }
}