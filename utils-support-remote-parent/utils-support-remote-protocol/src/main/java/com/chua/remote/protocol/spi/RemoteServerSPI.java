package com.chua.remote.protocol.spi;

import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;

public interface RemoteServerSPI {

    /**
     * 注册被控端（agent 接入平台）。
     *
     * @param agentInfo 被控端信息
     * @param remoteIp  接入来源 IP（安全审计——来自帧连接的真实地址，可能为 null）
     * @return 被控端 id
     */
    String agentRegister(AgentInfo agentInfo, String remoteIp);

    String controllerConnect(ControllerInfo controllerInfo);

    Session createSession(String controllerId, String agentId, String verifyCode);

    Session createSession(String controllerId, String agentId, String verifyCode, boolean reverseTunnelEnabled);

    boolean authenticate(String token);

    void closeSession(String sessionId);
}
