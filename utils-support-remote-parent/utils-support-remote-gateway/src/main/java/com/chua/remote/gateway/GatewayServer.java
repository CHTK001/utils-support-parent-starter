package com.chua.remote.agent;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.spi.RemoteAgentSPI;
import com.chua.remote.protocol.spi.RemoteControllerSPI;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远控网关入口。
 *
 * <p>负责管理被控端和控制端的连接、鉴权、信令路由和会话生命周期。
 * 实际的帧转发通过 {@link #routeData} / {@link #routeInputEvent} 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GatewayServer implements RemoteServerSPI {

    /** 底层网关服务端 */
    private final RemoteServer server;

    /** 鉴权管理器 */
    private final AuthManager authManager;

    /** 会话管理器 */
    private final SessionManager sessionManager;

    /** 信令路由管理器 */
    final RouteManager routeManager;

    /** 转码引擎 */
    final TranscodeEngine transcodeEngine;

    /** 帧回调（用于实际转发到 RemoteTransport） */
    private GatewayCallback gatewayCallback;

    public GatewayServer(ServerSetting setting) {
        this.server = new RemoteServer(setting);
        this.authManager = new AuthManager();
        this.sessionManager = new SessionManager();
        this.routeManager = new RouteManager(sessionManager);
        this.transcodeEngine = new TranscodeEngine();
        initHandlers();
    }

    /** 初始化消息处理器 */
    private void initHandlers() {
        server.getTransport().on(MessageType.SIGNAL, frame -> {
            handleSignal(frame);
        });
        server.getTransport().on(MessageType.CTRL, frame -> {
            handleControl(frame);
        });
        server.getTransport().on(MessageType.DATA, frame -> {
            handleData(frame);
        });
    }

    /**
     * 启动网关。
     */
    public void start() {
        server.start();
        log.info("远控网关已启动");
    }

    /**
     * 停止网关。
     */
    public void stop() {
        server.stop();
        log.info("远控网关已停止");
    }

    /**
     * 设置网关回调（用于实际发送帧到传输层）。
     *
     * @param callback 回调
     */
    public void setGatewayCallback(GatewayCallback callback) {
        this.gatewayCallback = callback;
    }

    /**
     * 处理信令帧（注册/能力上报）。
     */
    private void handleSignal(Frame frame) {
        log.debug("处理信令帧: sessionId={}", frame.getSessionId());
        // 信令帧可能包含 AgentInfo 或 ControllerInfo
        // 实际注册逻辑在 agentRegister / controllerConnect 中
    }

    /**
     * 处理控制帧（鉴权/编解码协商/转码指令）。
     */
    private void handleControl(Frame frame) {
        log.debug("处理控制帧: sessionId={}", frame.getSessionId());
        // 处理转码指令等控制消息
    }

    /**
     * 处理数据帧（媒体流/键鼠事件）。
     */
    private void handleData(Frame frame) {
        log.debug("处理数据帧: sessionId={}", frame.getSessionId());
        // 数据帧路由
        if (frame.getType() == MessageType.DATA) {
            // 屏幕流数据 -> 转发给控制端
            routeData(frame);
        }
    }

    /**
     * 路由数据帧（屏幕流）到控制端。
     */
    private void routeData(Frame frame) {
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(frame);
        }
        log.debug("路由数据帧到控制端: sessionId={}", frame.getSessionId());
    }

    /**
     * 路由键鼠事件到被控端。
     */
    private void routeInputEvent(Frame frame) {
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(frame);
        }
        log.debug("路由键鼠事件到被控端: sessionId={}", frame.getSessionId());
    }

    /**
     * 广播帧到所有订阅者。
     */
    private void broadcast(Frame frame) {
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(frame);
        }
        server.getTransport().publish(frame);
    }

    @Override
    public String agentRegister(AgentInfo agentInfo) {
        // 校验验证码
        if (!authManager.verifyAgent(agentInfo.getId(), agentInfo.getVerifyCode())) {
            throw new SecurityException("验证码校验失败: agentId=" + agentInfo.getId());
        }
        // 注册到会话管理器
        sessionManager.registerAgent(agentInfo);
        authManager.registerAgentAccessCode(agentInfo.getAccessCode());
        authManager.registerAgent(agentInfo.getId(), agentInfo.getVerifyCode());
        log.info("被控端注册成功: id={}, type={}, accessCode={}",
                agentInfo.getId(), agentInfo.getAgentType(), agentInfo.getAccessCode());
        return agentInfo.getId();
    }

    @Override
    public String controllerConnect(ControllerInfo controllerInfo) {
        // 校验接入令牌
        if (!authManager.verifyController(controllerInfo.getAccessToken())) {
            throw new SecurityException("接入令牌校验失败");
        }
        authManager.registerController(controllerInfo.getAccessToken(), controllerInfo);
        log.info("控制端接入成功: accessToken={}, targetAgentId={}",
                controllerInfo.getAccessToken(), controllerInfo.getTargetAgentId());
        return controllerInfo.getAccessToken();
    }

    @Override
    public Session createSession(String controllerId, String agentId, String verifyCode) {
        // 校验验证码
        if (!authManager.verifyAgent(agentId, verifyCode)) {
            throw new SecurityException("验证码校验失败");
        }
        // 获取双方能力
        var agentInfo = sessionManager.getAgent(agentId);
        var controllerInfo = sessionManager.getController(controllerId);
        if (agentInfo == null || controllerInfo == null) {
            throw new IllegalStateException("被控端或控制端未注册");
        }
        // 协商编解码
        var negotiated = transcodeEngine.negotiate(
                agentInfo.getEncodingCapability(),
                controllerInfo.getDecodingCapability());
        // 创建会话
        var session = Session.builder()
                .sessionId(java.util.UUID.randomUUID().toString())
                .controllerSessionId(controllerId)
                .agentId(agentId)
                .status(Session.SessionStatus.ACTIVE)
                .negotiatedCodec(negotiated)
                .createTime(System.currentTimeMillis())
                .build();
        sessionManager.addSession(session);
        log.info("会话创建成功: sessionId={}, agentId={}, controllerId={}, transcoded={}",
                session.getSessionId(), agentId, controllerId, negotiated.isTranscoded());
        return session;
    }

    @Override
    public boolean authenticate(String token) {
        return authManager.verifyController(token) || authManager.verifyAgentToken(token);
    }

    @Override
    public void closeSession(String sessionId) {
        sessionManager.closeSession(sessionId);
        log.info("会话已关闭: sessionId={}", sessionId);
    }
}

/**
 * 网关回调接口（用于实际帧转发）。
 */
@FunctionalInterface
interface GatewayCallback {
    void onFrame(com.chua.remote.protocol.frame.Frame frame);
}
