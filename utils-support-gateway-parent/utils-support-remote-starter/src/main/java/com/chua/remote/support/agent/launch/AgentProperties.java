package com.chua.remote.support.agent.launch;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent runtime properties.

 * @author CH
 */public class AgentProperties {
    private String agentId;
    private String agentName;
    private String agentType = "java";
    private String gatewayHost = "127.0.0.1";
    private int gatewayPort = 9001;
    /**
     * 密钥
     */
    private String secret;
    /**
     * 令牌
     */
    private String token;
    private long heartbeatIntervalMs = 15000L;
    private boolean autoReconnect = true;
    private List<String> transports = new ArrayList<>();
    private String agentSecret;
    private String protocols;
    private String codecs;
    private String transport;
    private String capabilities;
    /**
     * 验证码
     */
    private String verifyCode;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public String getGatewayHost() { return gatewayHost; }
    public void setGatewayHost(String gatewayHost) { this.gatewayHost = gatewayHost; }
    public int getGatewayPort() { return gatewayPort; }
    public void setGatewayPort(int gatewayPort) { this.gatewayPort = gatewayPort; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }
    public boolean isAutoReconnect() { return autoReconnect; }
    public void setAutoReconnect(boolean autoReconnect) { this.autoReconnect = autoReconnect; }
    public List<String> getTransports() { return transports; }
    public void setTransports(List<String> transports) { this.transports = transports; }
    public String getAgentSecret() { return agentSecret; }
    public void setAgentSecret(String agentSecret) { this.agentSecret = agentSecret; }
    public String getProtocols() { return protocols; }
    public void setProtocols(String protocols) { this.protocols = protocols; }
    public String getCodecs() { return codecs; }
    public void setCodecs(String codecs) { this.codecs = codecs; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public String getVerifyCode() { return verifyCode; }
    public void setVerifyCode(String verifyCode) { this.verifyCode = verifyCode; }
}
