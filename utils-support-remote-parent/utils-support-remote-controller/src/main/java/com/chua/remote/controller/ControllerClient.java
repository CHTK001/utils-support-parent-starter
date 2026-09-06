package com.chua.remote.controller;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.spi.RemoteControllerSPI;
import lombok.extern.slf4j.Slf4j;

/**
 * 控制端客户端。
 *
 * <p>连接网关、上报接入令牌和解码能力、发起会话、渲染画面。</p>
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

    public ControllerClient(String gatewayUrl, ControllerInfo controllerInfo) {
        this.client = new RemoteClient(gatewayUrl);
        this.controllerInfo = controllerInfo;
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
        return info.getAccessToken();
    }

    @Override
    public String startSession(String agentId, String verifyCode) {
        // 通过网关创建会话
        currentSessionId = java.util.UUID.randomUUID().toString();
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
        log.debug("渲染画面帧: sessionId={}, size={}", currentSessionId, frameData.length);
    }

    @Override
    public void injectInputEvent(byte[] eventData) {
        var frame = FrameCodec.controlFrame(currentSessionId, eventData);
        client.getTransport().send(frame);
        log.debug("注入键鼠事件: sessionId={}", currentSessionId);
    }

    /**
     * 断开连接。
     */
    public void disconnect() {
        client.disconnect();
        log.info("控制端已断开");
    }
}
