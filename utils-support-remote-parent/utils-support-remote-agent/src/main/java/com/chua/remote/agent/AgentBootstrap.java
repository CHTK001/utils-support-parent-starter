package com.chua.remote.agent;

import com.chua.common.support.network.server.ServerSetting;
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
public class AgentBootstrap {

    /** 网关客户端 */
    private final RemoteClient client;

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** 服务模式实现 */
    private final AgentService service;

    /** 套壳模式实现 */
    private final AgentShellService shellService;

    /**
     * 创建被控端启动器。
     *
     * @param gatewayUrl 网关地址
     * @param agentInfo  被控端信息
     */
    public AgentBootstrap(String gatewayUrl, AgentInfo agentInfo) {
        this.client = new RemoteClient(gatewayUrl);
        this.agentInfo = agentInfo;
        this.service = new AgentService(agentInfo);
        this.shellService = new AgentShellService(agentInfo);
    }

    /**
     * 启动被控端。
     */
    public void start() {
        // 连接网关
        client.connect();

        // 上报能力
        reportCapabilities();

        // 根据类型启动模式
        if (agentInfo.getAgentType() == AgentInfo.AgentType.SERVICE) {
            startServiceMode();
        } else {
            startShellMode();
        }
    }

    /** 上报编码能力 */
    private void reportCapabilities() {
        var frame = FrameCodec.encodeSignal(MessageType.SIGNAL, agentInfo.getId(), agentInfo);
        client.getTransport().send(frame);
        log.info("已上报被控端信息: id={}, type={}", agentInfo.getId(), agentInfo.getAgentType());
    }

    /** 启动服务模式 */
    private void startServiceMode() {
        service.start();
        log.info("服务模式已启动: agentId={}", agentInfo.getId());
    }

    /** 启动套壳模式 */
    private void startShellMode() {
        shellService.start();
        log.info("套壳模式已启动: agentId={}", agentInfo.getId());
    }

    /**
     * 停止被控端。
     */
    public void stop() {
        service.stop();
        shellService.stop();
        client.disconnect();
        log.info("被控端已停止: id={}", agentInfo.getId());
    }
}
