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

    /**
     * 部署启动入口。
     *
     * @param args [0]=网关地址（默认 tcp://localhost:9000），[1]=accessToken，[2]=targetAgentId，[3]=verifyCode
     */
    public static void main(String[] args) {
        String gatewayUrl = args.length > 0 ? args[0] : "tcp://localhost:9000";
        String accessToken = args.length > 1 ? args[1] : "controller-token-1";
        String targetAgentId = args.length > 2 ? args[2] : "agent1";
        String verifyCode = args.length > 3 ? args[3] : "0000";
        ControllerInfo info = ControllerInfo.builder()
                .accessToken(accessToken)
                .targetAgentId(targetAgentId)
                .verifyCode(verifyCode)
                .decodingCapability(CodecProfile.builder()
                        .encodings(java.util.List.of("h264", "jpeg"))
                        .maxWidth(1920)
                        .maxHeight(1080)
                        .quality(80)
                        .build())
                .build();
        ControllerBootstrap bootstrap = new ControllerBootstrap(gatewayUrl, info);
        bootstrap.start();
        // 保持 JVM 存活（传输层为 NIO Reactor 异步线程——主线程须阻塞，否则 main 返回即退出）
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
