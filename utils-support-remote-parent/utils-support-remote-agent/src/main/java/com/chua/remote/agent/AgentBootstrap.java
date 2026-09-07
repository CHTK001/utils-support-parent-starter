package com.chua.remote.agent;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.sync.netty.NettyWebSocketSyncFlow;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.DigestUtils;
import com.chua.common.support.utils.IdUtils;
import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.spi.RemoteAgentSPI;
import lombok.extern.slf4j.Slf4j;

/**
 * 被控端启动入口。
 *
 * <p>连接网关、上报身份信息和编码能力、启动服务模式或套壳模式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("remote-agent")
public class AgentBootstrap {

    /** 网关客户端 */
    private final RemoteClient client;

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** 服务模式实现 */
    private final AgentService service;

    /** 套壳模式实现 */
    private final AgentShellService shellService;

    /** 运行状态 */
    private volatile boolean running;

    public AgentBootstrap(String gatewayUrl, AgentInfo agentInfo) {
        this.client = new RemoteClient(gatewayUrl);
        this.agentInfo = agentInfo;
        this.service = new AgentService(agentInfo);
        this.shellService = new AgentShellService(agentInfo);
        // 生成验证码
        agentInfo.setVerifyCode(DigestUtils.md5(IdUtils.uuid() + System.currentTimeMillis()));
    }

    /**
     * 启动被控端。
     */
    public void start() {
        // 连接网关
        client.connect();
        log.info("已连接到网关");

        // 注册到网关
        String agentId = registerToGateway();

        // 上报能力
        reportCapabilities();

        // 根据模式启动：PUSH 走自研采集推送；FORWARD/SHELL 走三方对接/套壳路径
        if (agentInfo.getAgentType() == AgentInfo.AgentType.PUSH) {
            startServiceMode(agentId);
        } else {
            startShellMode(agentId);
        }
        running = true;
    }

    /** 注册到网关 */
    private String registerToGateway() {
        String agentId = agentInfo.getId();
        // 先通过网关的 SPI 接口注册
        // 实际注册通过 RemoteTransport 发送信号帧
        var registerFrame = FrameCodec.encodeSignal(
                MessageType.SIGNAL, agentId, agentInfo);
        client.getTransport().send(registerFrame);
        log.info("被控端注册到网关: id={}, type={}, verifyCode={}",
                agentId, agentInfo.getAgentType(), agentInfo.getVerifyCode());
        return agentId;
    }

    /** 上报编码能力 */
    private void reportCapabilities() {
        var capabilityFrame = FrameCodec.encodeSignal(
                MessageType.SIGNAL, agentInfo.getId(), agentInfo.getEncodingCapability());
        client.getTransport().send(capabilityFrame);
        log.info("已上报编码能力: agentId={}, encodings={}",
                agentInfo.getId(), agentInfo.getEncodingCapability().getEncodings());
    }

    /** 启动服务模式 */
    private void startServiceMode(String agentId) {
        // 设置截图回调，将编码后的帧发送到网关
        service.setScreenCallback(encodedFrame -> {
            var dataFrame = FrameCodec.dataFrame(agentId, encodedFrame);
            client.getTransport().send(dataFrame);
        });
        service.start();
        log.info("服务模式已启动: agentId={}", agentId);
    }

    /** 启动套壳模式 */
    private void startShellMode(String agentId) {
        shellService.start();
        log.info("套壳模式已启动: agentId={}", agentId);
    }

    /**
     * 停止被控端。
     */
    public void stop() {
        running = false;
        service.stop();
        shellService.stop();
        client.disconnect();
        log.info("被控端已停止: id={}", agentInfo.getId());
    }

    /**
     * 获取被控端信息。
     *
     * @return AgentInfo
     */
    public AgentInfo getAgentInfo() {
        return agentInfo;
    }

    /**
     * 获取服务模式。
     *
     * @return AgentService
     */
    public AgentService getService() {
        return service;
    }

    /**
     * 获取套壳模式。
     *
     * @return AgentShellService
     */
    public AgentShellService getShellService() {
        return shellService;
    }
}
