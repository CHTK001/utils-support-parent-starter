package com.chua.remote.gateway;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.core.RemoteServer;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.spi.RemoteServerSPI;
import lombok.extern.log4j.Log4j2;

import lombok.extern.slf4j.Slf4j;

/**
 * 远控网关入口。
 *
 * <p>负责管理被控端和控制端的连接、鉴权、信令路由和会话生命周期。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("remote-gateway")
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

    /** 是否开启反向隧道（控制端通过网关反向连接被控端） */
    private boolean reverseTunnelEnabled;

    public GatewayServer(ServerSetting setting) {
        this.server = new RemoteServer(setting);
        this.authManager = new AuthManager();
        this.sessionManager = new SessionManager();
        this.routeManager = new RouteManager(sessionManager);
        this.transcodeEngine = new TranscodeEngine();
        this.reverseTunnelEnabled = false;
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

    /**
     * 获取底层传输层。
     *
     * <p>供嵌入式部署注入或观测传输帧使用。</p>
     *
     * @return 传输层
     */
    public RemoteTransport getTransport() {
        return server.getTransport();
    }

    /**
     * 处理信令帧：按载荷对象类型分发到被控端注册或控制端接入。
     *
     * @param frame 信令帧
     */
    private void handleSignal(Frame frame) {
        String kind = frame.getMetadata() != null
                ? frame.getMetadata().get(FrameCodec.METADATA_KIND) : null;
        if (AgentInfo.class.getSimpleName().equals(kind)) {
            AgentInfo agentInfo = FrameCodec.decodeSignal(frame, AgentInfo.class);
            if (agentInfo != null) {
                agentRegister(agentInfo);
            }
            return;
        }
        if (ControllerInfo.class.getSimpleName().equals(kind)) {
            ControllerInfo controllerInfo = FrameCodec.decodeSignal(frame, ControllerInfo.class);
            if (controllerInfo != null) {
                controllerConnect(controllerInfo);
            }
            return;
        }
        log.debug("处理信令帧: sessionId={}, kind={}", frame.getSessionId(), kind);
    }

    /**
     * 处理控制帧：将键鼠事件路由到会话对应的被控端。
     *
     * @param frame 控制帧（sessionId 为远控会话 id）
     */
    private void handleControl(Frame frame) {
        routeInputEvent(frame);
    }

    private void handleData(Frame frame) {
        log.debug("处理数据帧: sessionId={}", frame.getSessionId());
        if (frame.getType() == MessageType.DATA) {
            routeData(frame);
        }
    }

    private void routeData(Frame frame) {
        // 协商无交集时：网关按会话协商结果兜底转码后转发
        Frame routed = frame;
        Session session = sessionManager.getSession(frame.getSessionId());
        if (session != null && session.getNegotiatedCodec() != null
                && session.getNegotiatedCodec().isTranscoded()) {
            byte[] payload = transcodeEngine.transcode(session, frame.getPayload());
            if (payload != frame.getPayload()) {
                routed = Frame.builder()
                        .type(MessageType.DATA)
                        .sessionId(frame.getSessionId())
                        .payload(payload)
                        .metadata(frame.getMetadata())
                        .build();
            }
        }
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(routed);
        }
        server.getTransport().publish(routed);
        log.debug("路由数据帧到控制端: sessionId={}", frame.getSessionId());
    }

    /**
     * 路由键鼠事件到被控端。
     *
     * <p>控制帧以远控会话 id 标识，路由前改写为被控端 id 以定位目标连接。</p>
     *
     * @param frame 控制帧
     */
    private void routeInputEvent(Frame frame) {
        Session session = sessionManager.getSession(frame.getSessionId());
        if (session == null) {
            log.warn("会话不存在，丢弃控制帧: sessionId={}", frame.getSessionId());
            return;
        }
        Frame routed = Frame.builder()
                .type(MessageType.CTRL)
                .sessionId(session.getAgentId())
                .payload(frame.getPayload())
                .metadata(frame.getMetadata())
                .build();
        if (gatewayCallback != null) {
            gatewayCallback.onFrame(routed);
        }
        server.getTransport().send(session.getAgentId(), routed);
        log.debug("路由键鼠事件到被控端: agentId={}, sessionId={}",
                session.getAgentId(), session.getSessionId());
    }

    /**
     * 被控端注册。
     *
     * <p>验证码由被控端自行生成并随注册上报，作为后续控制端发起会话的凭据；
     * 重复注册时校验验证码一致性，防止身份冒用。</p>
     *
     * @param agentInfo 被控端信息
     * @return 被控端 id
     */
    @Override
    public String agentRegister(AgentInfo agentInfo) {
        AgentInfo existing = sessionManager.getAgent(agentInfo.getId());
        if (existing != null && !Objects.equals(existing.getVerifyCode(), agentInfo.getVerifyCode())) {
            throw new SecurityException("被控端重复注册且验证码不一致: agentId=" + agentInfo.getId());
        }
        sessionManager.registerAgent(agentInfo);
        authManager.registerAgent(agentInfo.getId(), agentInfo.getVerifyCode());
        authManager.registerAgentAccessCode(agentInfo.getAccessCode());
        log.info("被控端注册成功: id={}, type={}, accessCode={}",
                agentInfo.getId(), agentInfo.getAgentType(), agentInfo.getAccessCode());
        return agentInfo.getId();
    }

    /**
     * 控制端接入。
     *
     * <p>接入令牌即凭据（capability token）：首次接入完成注册，重复接入更新接入信息；
     * 后续 {@link #authenticate(String)} 与会话校验均以已注册令牌为准。</p>
     *
     * @param controllerInfo 控制端信息
     * @return 接入令牌
     */
    @Override
    public String controllerConnect(ControllerInfo controllerInfo) {
        authManager.registerController(controllerInfo.getAccessToken(), controllerInfo);
        log.info("控制端接入成功: accessToken={}, targetAgentId={}",
                controllerInfo.getAccessToken(), controllerInfo.getTargetAgentId());
        return controllerInfo.getAccessToken();
    }

    @Override
    public Session createSession(String controllerId, String agentId, String verifyCode) {
        return createSession(controllerId, agentId, verifyCode, false);
    }

    @Override
    public Session createSession(String controllerId, String agentId, String verifyCode, boolean reverseTunnelEnabled) {
        if (!authManager.verifyAgent(agentId, verifyCode)) {
            throw new SecurityException("验证码校验失败");
        }
        var agentInfo = sessionManager.getAgent(agentId);
        var controllerInfo = sessionManager.getController(controllerId);
        if (agentInfo == null || controllerInfo == null) {
            throw new IllegalStateException("被控端或控制端未注册");
        }
        // 控制端参数优先
        if (controllerInfo.isReverseTunnelEnabled()) {
            reverseTunnelEnabled = true;
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
                .agentType(agentInfo.getAgentType())
                .createTime(System.currentTimeMillis())
                .reverseTunnelEnabled(reverseTunnelEnabled)
                .build();
        sessionManager.addSession(session);
        log.info("会话创建成功: sessionId={}, agentId={}, controllerId={}, transcoded={}, reverseTunnelEnabled={}",
                session.getSessionId(), agentId, controllerId, negotiated.isTranscoded(), reverseTunnelEnabled);
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