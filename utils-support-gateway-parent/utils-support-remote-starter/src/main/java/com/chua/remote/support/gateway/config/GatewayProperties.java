package com.chua.remote.support.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway configuration properties.
 *
 * @author CH
 */
@Data
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private int tcpControlPort = 9000;
    private int tcpAgentPort = 9001;
    private int socks5GatewayPort = 1080;
    private int httpManagementPort = 3000;
    private int wsApiGatewayPort = 8081;
    private int dwsRemoteControlPort = 8888;
    private int httpApiPort = 8083;
    private String livekitAgentRelayHost = "127.0.0.1";
    private int livekitAgentRelayPort = 9090;
    private String livekitSfuUrl = "ws://192.168.110.100:7880";
    private int bossThreads = 1;
    private int workerThreads = 0;
    private int agentHeartbeatInterval = 30;
    private int maxHeartbeatMisses = 3;
    private String agentRegisterKey = "gateway-agent-secret";
    private int sessionIdleTimeout = 300;
    private int maxSessions = 10000;
    private int rateLimitTokensPerSecond = 1000;
    private int rateLimitBurstCapacity = 2000;
    private int sniffBytes = 8;
    private String adminPath = "/admin";
    private String apiPath = "/admin/api";
    private String managementToken = "";
    private boolean apiTokenEnabled = true;
    private String remoteApiHost = "127.0.0.1";
    private int remoteApiPort = 8083;
    private boolean sshReverseTunnelEnabled = false;
    private String sshRemoteHost = "";
    private int sshRemotePort = 22;
    private int sshReverseLocalPort = 0;
    private int sshReverseRemotePort = 0;
    private String sshUsername = "";
    private String sshPassword = "";
    private String sshKeyFile = "";
    private String verifyCode = "";
}