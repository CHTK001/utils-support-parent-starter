package com.chua.remote.gateway;

import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 鉴权管理器。
 *
 * <p>管理控制端的接入令牌和被控端的接入码/验证码校验。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AuthManager {

    /** 控制端令牌缓存 */
    private final Map<String, ControllerInfo> controllerTokens = new ConcurrentHashMap<>();

    /** 被控端验证码缓存 */
    private final Map<String, String> agentVerifyCodes = new ConcurrentHashMap<>();

    /** 被控端接入码缓存 */
    private final Map<String, String> agentAccessCodes = new ConcurrentHashMap<>();

    /**
     * 校验控制端接入令牌。
     *
     * @param token 接入令牌
     * @return 是否通过
     */
    public boolean verifyController(String token) {
        return controllerTokens.containsKey(token);
    }

    /**
     * 校验被控端验证码。
     *
     * @param agentId     被控端 id
     * @param verifyCode 验证码
     * @return 是否通过
     */
    public boolean verifyAgent(String agentId, String verifyCode) {
        return verifyCode.equals(agentVerifyCodes.get(agentId));
    }

    /**
     * 校验被控端接入码。
     *
     * @param accessCode 接入码
     * @return 是否通过
     */
    public boolean verifyAgentAccessCode(String accessCode) {
        return agentAccessCodes.containsKey(accessCode);
    }

    /**
     * 校验被控端令牌。
     *
     * @param token 令牌
     * @return 是否通过
     */
    public boolean verifyAgentToken(String token) {
        return agentAccessCodes.containsKey(token);
    }

    /**
     * 注册控制端令牌。
     *
     * @param token   令牌
     * @param info    控制端信息
     */
    public void registerController(String token, ControllerInfo info) {
        controllerTokens.put(token, info);
    }

    /**
     * 注册被控端验证码。
     *
     * @param agentId     被控端 id
     * @param verifyCode 验证码
     */
    public void registerAgent(String agentId, String verifyCode) {
        agentVerifyCodes.put(agentId, verifyCode);
    }

    /**
     * 注册被控端接入码。
     *
     * @param accessCode 接入码
     */
    public void registerAgentAccessCode(String accessCode) {
        agentAccessCodes.put(accessCode, accessCode);
    }
}
