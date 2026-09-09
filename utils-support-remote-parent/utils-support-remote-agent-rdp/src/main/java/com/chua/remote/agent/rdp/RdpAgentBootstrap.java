package com.chua.remote.agent.rdp;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;

/**
 * RDP agent 入口。
 *
 * <p>注册网关（AgentInfo：agentType=FORWARD + 平台 + RDP 服务状态 + 桌面会话）
 * + 挂接 RDP 帧监听（{@link RdpSessionChannel}——桌面画面采集推流 + 键鼠注入 + WebRTC 信令预留）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("remote-agent-rdp")
public class RdpAgentBootstrap {

    private final RemoteClient client;
    private final AgentInfo agentInfo;
    private final RdpServiceManager serviceManager;
    private final RdpSessionChannel sessionChannel;
    private volatile boolean running;

    public RdpAgentBootstrap(String gatewayUrl, AgentInfo agentInfo) {
        this.client = new RemoteClient(agentInfo.getId(), gatewayUrl);
        this.agentInfo = agentInfo;
        this.serviceManager = new RdpServiceManager();
        this.sessionChannel = new RdpSessionChannel(client, agentInfo, serviceManager);
    }

    public void start() {
        client.connect();
        log.info("已连接到网关");
        client.getTransport().on(MessageType.RDP, sessionChannel::handleRdpFrame);
        String agentId = registerToGateway();
        running = true;
        log.info("RDP agent 就绪: agentId={}, platform={}, rdpPresent={}, desktop={}",
                agentId, serviceManager.getPlatform(), serviceManager.isForwardMode(),
                serviceManager.isDesktopAvailable());
    }

    /**
     * 注册到网关：AgentInfo（agentType=FORWARD + 平台 + RDP 服务状态 + 桌面会话）。
     *
     * @return 被控端 id
     */
    private String registerToGateway() {
        String agentId = agentInfo.getId();
        agentInfo.setPlatform(serviceManager.getPlatform().name());
        agentInfo.setAgentType(AgentInfo.AgentType.FORWARD);
        agentInfo.setDesktopSupported(serviceManager.isDesktopAvailable());
        if (agentInfo.getExtra() == null) {
            agentInfo.setExtra(new HashMap<>());
        }
        agentInfo.getExtra().put("rdpAvailable", String.valueOf(serviceManager.isForwardMode()));
        var registerFrame = FrameCodec.encodeSignal(MessageType.SIGNAL, agentId, agentInfo);
        client.getTransport().send(registerFrame);
        log.info("被控端注册到网关: id={}, type={}, platform={}, rdpPresent={}, desktop={}",
                agentId, agentInfo.getAgentType(), agentInfo.getPlatform(),
                serviceManager.isForwardMode(), serviceManager.isDesktopAvailable());
        return agentId;
    }

    public void stop() {
        running = false;
        sessionChannel.stopAll();
        client.disconnect();
        log.info("RDP agent 已停止: id={}", agentInfo.getId());
    }

    public static void main(String[] args) {
        String gatewayUrl = args.length > 0 ? args[0] : "tcp://localhost:9000";
        String agentId = args.length > 1 ? args[1] : "rdp-agent-" + System.currentTimeMillis();
        String verifyCode = args.length > 2 ? args[2] : "0000";
        AgentInfo info = AgentInfo.builder()
                .id(agentId)
                .verifyCode(verifyCode)
                .accessCode(verifyCode)
                .encodingCapability(com.chua.remote.protocol.capability.CodecProfile.builder()
                        .encodings(java.util.List.of("jpeg"))
                        .maxWidth(1920)
                        .maxHeight(1080)
                        .quality(80)
                        .maxFps(15)
                        .build())
                .build();
        RdpAgentBootstrap bootstrap = new RdpAgentBootstrap(gatewayUrl, info);
        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::stop));
        bootstrap.start();
        // 主线程保活（main 返回后 JVM 立即退出——必须阻塞直到停机信号）
        while (bootstrap.running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
