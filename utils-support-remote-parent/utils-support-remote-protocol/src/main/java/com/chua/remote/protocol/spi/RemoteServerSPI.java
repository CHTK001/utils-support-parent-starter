package com.chua.remote.protocol.spi;

import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;

public interface RemoteServerSPI {

    String agentRegister(AgentInfo agentInfo);

    String controllerConnect(ControllerInfo controllerInfo);

    Session createSession(String controllerId, String agentId, String verifyCode);

    Session createSession(String controllerId, String agentId, String verifyCode, boolean reverseTunnelEnabled);

    boolean authenticate(String token);

    void closeSession(String sessionId);
}
