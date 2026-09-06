package com.chua.remote.gateway;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.remote.core.RemoteServer;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.spi.RemoteServerSPI;
import lombok.extern.slf4j.Slf4j;

/**
 * 远控网关入口。
 *
 * <p>负责管理被控端和控制端的连接、鉴权、信令路由和会话生命周期。</p>
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

    public void start() {
        server.start();
        log.info("远控网关已启动");
    }

    public void stop() {
        server.stop();
        log.info("远控网关已停止");
    }

    public void setGatewayCallback(GatewayCallback callback) {
        this.gatewayCallback = callback;
    }

    private void handleSignal(Frame frame) {
        log.debug("处理信令帧: sessionId={}", frame.getSessionId());
    }

    private void handleControl(Frame frame) {
        log.debug("处理控制帧: sessionId={}", frame.getSessionId());
    }

    private void handleData(Frame frame) {
        log.debug("处理数据帧: sessionId={}", frame.getSessionId());
        if (frame.getType() == MessageType.DATA) {
            routeData(frame);
        }
    }

    private void routeData(Frame frame) {
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(frame);
        }
        server.getTransport().publish(frame);
        log.debug("路由数据帧到控制端: sessionId={}", frame.getSessionId());
    }

    private void routeInputEvent(Frame frame) {
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(frame);
        }
        server.getTransport().send(frame.getSessionId(), frame);
        log.debug("路由键鼠事件到被控端: sessionId={}", frame.getSessionId());
    }

    @Override
    public String agentRegister(AgentInfo agentInfo) {
        if (!authManager.verifyAgent(agentInfo.getId(), agentInfo.getVerifyCode())) {
            throw new SecurityException("验证码校验失败: agentId=" + agentInfo.getId());
        }
        sessionManager.registerAgent(agentInfo);
        authManager.registerAgentAccessCode(agentInfo.getAccessCode());
        authManager.registerAgent(agentInfo.getId(), agentInfo.getVerifyCode());
        log.info("被控端注册成功: id={}, type={}, accessCode={}",
                agentInfo.getId(), agentInfo.getAgentType(), agentInfo.getAccessCode());
        return agentInfo.getId();
    }

    @Override
    public String controllerConnect(ControllerInfo controllerInfo) {
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
        if (!authManager.verifyAgent(agentId, verifyCode)) {
            throw new SecurityException("验证码校验失败");
        }
        var agentInfo = sessionManager.getAgent(agentId);
        var controllerInfo = sessionManager.getController(controllerId);
        if (agentInfo == null || controllerInfo == null) {
            throw new IllegalStateException("被控端或控制端未注册");
        }
        var negotiated = transcodeEngine.negotiate(
                agentInfo.getEncodingCapability(),
                controllerInfo.getDecodingCapability());
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

@FunctionalInterface
interface GatewayCallback {
    void onFrame(Frame frame);
}
