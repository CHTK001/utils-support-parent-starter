package com.chua.remote.gateway;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.remote.core.RemoteServer;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.MessageType;
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

    /**
     * 创建网关服务端。
     *
     * @param setting 服务配置（含加密配置）
     */
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
     * 处理信令帧。
     */
    private void handleSignal(com.chua.remote.protocol.frame.Frame frame) {
        log.debug("处理信令帧: sessionId={}", frame.getSessionId());
    }

    /**
     * 处理控制帧。
     */
    private void handleControl(com.chua.remote.protocol.frame.Frame frame) {
        log.debug("处理控制帧: sessionId={}", frame.getSessionId());
    }

    /**
     * 处理数据帧（媒体流）。
     */
    private void handleData(com.chua.remote.protocol.frame.Frame frame) {
        log.debug("处理数据帧: sessionId={}", frame.getSessionId());
    }

    @Override
    public String agentRegister(com.chua.remote.protocol.model.AgentInfo agentInfo) {
        // 校验验证码
        if (!authManager.verifyAgent(agentInfo.getId(), agentInfo.getVerifyCode())) {
            throw new SecurityException("验证码校验失败: agentId=" + agentInfo.getId());
        }
        // 注册到会话管理器
        sessionManager.registerAgent(agentInfo);
        log.info("被控端注册成功: id={}, type={}", agentInfo.getId(), agentInfo.getAgentType());
        return agentInfo.getId();
    }

    @Override
    public String controllerConnect(com.chua.remote.protocol.model.ControllerInfo controllerInfo) {
        // 校验接入令牌
        if (!authManager.verifyController(controllerInfo.getAccessToken())) {
            throw new SecurityException("接入令牌校验失败");
        }
        log.info("控制端接入成功: targetAgentId={}", controllerInfo.getTargetAgentId());
        return controllerInfo.getTargetAgentId();
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
        log.info("会话创建成功: sessionId={}, agentId={}, transcoded={}",
                session.getSessionId(), agentId, negotiated.isTranscoded());
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
