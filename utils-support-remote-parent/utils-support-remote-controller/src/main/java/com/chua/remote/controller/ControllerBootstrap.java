package com.chua.remote.controller;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.ControllerInfo;
import com.chua.remote.protocol.spi.RemoteControllerSPI;
import lombok.extern.slf4j.Slf4j;

/**
 * 控制端启动入口。
 *
 * <p>连接网关、上报接入令牌和解码能力、发起会话、渲染画面、注入键鼠事件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ControllerBootstrap {

    /** 控制端客户端 */
    private final ControllerClient controllerClient;

    /** 控制端信息 */
    private final ControllerInfo controllerInfo;

    public ControllerBootstrap(String gatewayUrl, ControllerInfo controllerInfo) {
        this.controllerClient = new ControllerClient(gatewayUrl, controllerInfo);
        this.controllerInfo = controllerInfo;
    }

    /**
     * 启动控制端。
     */
    public void start() {
        // 连接到网关
        controllerClient.connect();

        // 上报解码能力
        controllerClient.reportCapability(controllerInfo.getDecodingCapability());

        log.info("控制端已启动: accessToken={}", controllerInfo.getAccessToken());
    }

    /**
     * 发起对某被控端的会话。
     *
     * @param agentId    被控端 id
     * @param verifyCode 验证码
     * @return 会话 id
     */
    public String startSession(String agentId, String verifyCode) {
        return controllerClient.createSession(agentId, verifyCode);
    }

    /**
     * 渲染画面帧。
     *
     * @param frameData 帧数据
     */
    public void renderFrame(byte[] frameData) {
        controllerClient.renderFrame(frameData);
    }

    /**
     * 注入键鼠事件。
     *
     * @param eventData 事件数据
     */
    public void injectInputEvent(byte[] eventData) {
        controllerClient.injectInputEvent(eventData);
    }

    /**
     * 断开连接。
     */
    public void stop() {
        controllerClient.disconnect();
        log.info("控制端已停止");
    }
}
