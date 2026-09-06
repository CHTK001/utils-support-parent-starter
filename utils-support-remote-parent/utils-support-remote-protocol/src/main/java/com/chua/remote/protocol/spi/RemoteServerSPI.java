package com.chua.remote.protocol.spi;

import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;

/**
 * 网关服务端 SPI 接口。
 *
 * <p>定义网关对被控端和控制端的接入、鉴权、会话管理能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RemoteServerSPI {

    /**
     * 被控端注册到网关。
     *
     * @param agentInfo 被控端信息
     * @return 注册结果（含会话 id）
     */
    String agentRegister(AgentInfo agentInfo);

    /**
     * 控制端接入网关。
     *
     * @param controllerInfo 控制端信息
     * @return 接入结果
     */
    String controllerConnect(ControllerInfo controllerInfo);

    /**
     * 建立会话（控制端请求访问某被控端）。
     *
     * @param controllerId 控制端 id
     * @param agentId      被控端 id
     * @param verifyCode   验证码
     * @return 会话信息
     */
    Session createSession(String controllerId, String agentId, String verifyCode);

    /**
     * 网关鉴权（校验接入令牌/接入码）。
     *
     * @param token 接入令牌或接入码
     * @return 是否通过
     */
    boolean authenticate(String token);

    /**
     * 关闭会话。
     *
     * @param sessionId 会话 id
     */
    void closeSession(String sessionId);
}
