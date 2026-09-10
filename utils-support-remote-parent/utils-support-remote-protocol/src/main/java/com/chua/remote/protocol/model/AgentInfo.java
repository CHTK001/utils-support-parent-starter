package com.chua.remote.protocol.model;

import com.chua.remote.protocol.capability.CodecProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String verifyCode;
    private String accessCode;
    private CodecProfile encodingCapability;
    private String platform;
    private String hardwareInfo;
    private String host;
    private int port;
    private String username;
    private String password;
    private AgentType agentType;
    private Map<String, String> extra;
    private Boolean desktopSupported;
    private String gatewaySshHost;
    private int gatewaySshPort;
    private String gatewaySshUser;
    /** 本机全部网卡 IP（多网卡全部上报——白名单校验命中其一即通过） */
    private java.util.List<String> ips;
    private String gatewaySshPass;

    public enum AgentType {
        FORWARD,
        SHELL,
        PUSH
    }
}
