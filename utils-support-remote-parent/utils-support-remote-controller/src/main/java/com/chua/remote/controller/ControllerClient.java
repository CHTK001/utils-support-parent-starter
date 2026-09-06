package com.chua.remote.controller;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.spi.RemoteControllerSPI;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制端客户端。
 *
 * <p>连接网关、上报接入令牌和解码能力、发起会话、渲染画面、注入键鼠事件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ControllerClient implements RemoteControllerSPI {

    /** 网关客户端 */
    private final RemoteClient client;

    /** 控制端信息 */
    private final ControllerInfo controllerInfo;

    /** 当前会话 id */
    private String currentSessionId;

    /** 会话映射 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 解码渲染器 */
    private final DecoderRenderer decoderRenderer;

    /** 键鼠事件注入器 */
    private final InputInjector inputInjector;

    public ControllerClient(String gatewayUrl, ControllerInfo controllerInfo) {
        this.client = new RemoteClient(gatewayUrl);
        this.controllerInfo = controllerInfo;
        this.decoderRenderer = new DecoderRenderer(controllerInfo.getDecodingCapability());
        this.inputInjector = new InputInjector();
    }

    /**
     * 连接到网关。
     */
    public void connect() {
        client.connect();
        // 上报接入令牌和解码能力
        reportToGateway();
        log.info("控制端已连接: accessToken={}", controllerInfo.getAccessToken());
    }

    /** 上报到网关 */
    private void reportToGateway() {
        var frame = FrameCodec.encodeSignal(MessageType.SIGNAL, controllerInfo.getAccessToken(), controllerInfo);
        client.getTransport().send(frame);
    }

    @Override
    public String connect(ControllerInfo info) {
        client.connect();
        reportToGateway();
        return info.getAccessToken();
    }

    @Override
    public String startSession(String agentId, String verifyCode) {
        // 通过网关创建会话
        currentSessionId = java.util.UUID.randomUUID().toString();
        var session = Session.builder()
                .sessionId(currentSessionId)
                .controllerSessionId(controllerInfo.getAccessToken())
                .agentId(agentId)
                .status(Session.SessionStatus.ACTIVE)
                .createTime(System.currentTimeMillis())
                .build();
        sessions.put(currentSessionId, session);
        log.info("发起会话: controllerId={}, agentId={}, sessionId={}",
                controllerInfo.getAccessToken(), agentId, currentSessionId);
        return currentSessionId;
    }

    @Override
    public void reportCapability(CodecProfile capability) {
        var frame = FrameCodec.encodeSignal(MessageType.SIGNAL, currentSessionId, capability);
        client.getTransport().send(frame);
        log.info("上报解码能力: sessionId={}", currentSessionId);
    }

    @Override
    public void renderFrame(byte[] frameData) {
        var frame = FrameCodec.dataFrame(currentSessionId, frameData);
        decoderRenderer.render(frame);
        log.debug("渲染画面帧: sessionId={}, size={}", currentSessionId, frameData.length);
    }

    @Override
    public void injectInputEvent(byte[] eventData) {
        var frame = FrameCodec.controlFrame(currentSessionId, eventData);
        client.getTransport().send(frame);
        inputInjector.inject(eventData);
        log.debug("注入键鼠事件: sessionId={}", currentSessionId);
    }

    /**
     * 发起会话并返回会话 id。
     *
     * @param agentId    被控端 id
     * @param verifyCode 验证码
     * @return 会话 id
     */
    public String createSession(String agentId, String verifyCode) {
        currentSessionId = startSession(agentId, verifyCode);
        return currentSessionId;
    }

    /**
     * 断开连接。
     */
    public void disconnect() {
        client.disconnect();
        sessions.clear();
        currentSessionId = null;
        log.info("控制端已断开");
    }

    /**
     * 获取解码渲染器。
     *
     * @return DecoderRenderer
     */
    public DecoderRenderer getDecoderRenderer() {
        return decoderRenderer;
    }

    /**
     * 获取键鼠事件注入器。
     *
     * @return InputInjector
     */
    public InputInjector getInputInjector() {
        return inputInjector;
    }

    /**
     * 获取当前会话 id。
     *
     * @return 会话 id
     */
    public String getCurrentSessionId() {
        return currentSessionId;
    }
}
