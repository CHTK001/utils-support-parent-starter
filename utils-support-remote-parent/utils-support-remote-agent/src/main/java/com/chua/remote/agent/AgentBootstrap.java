package com.chua.remote.agent;

import com.chua.common.support.utils.DigestUtils;
import com.chua.common.support.utils.IdUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.spi.RemoteAgentSPI;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Spi("remote-agent")
public class AgentBootstrap {

    private final RemoteClient client;
    private final AgentInfo agentInfo;
    private final AgentService service;
    private final AgentShellService shellService;
    private final SSHChannelManager sshChannelManager;
    private final VncSessionManager vncSessionManager;
    private volatile boolean running;

    public AgentBootstrap(String gatewayUrl, AgentInfo agentInfo) {
        this.client = new RemoteClient(agentInfo.getId(), gatewayUrl);
        this.agentInfo = agentInfo;
        this.service = new AgentService(agentInfo);
        this.shellService = new AgentShellService(agentInfo, client);
        this.sshChannelManager = new SSHChannelManager(client, agentInfo.getId());
        this.vncSessionManager = new VncSessionManager(client, agentInfo);
    }

    public void start() {
        client.connect();
        log.info("已连接到网关");

        client.getTransport().on(MessageType.SIGNAL, this::handleSignal);
        client.getTransport().on(MessageType.SSH, sshChannelManager::handleSSHFrame);
        client.getTransport().on(MessageType.VNC, vncSessionManager::handleVncFrame);

        String agentId = registerToGateway();
        reportCapabilities();

        if (agentInfo.getAgentType() == AgentInfo.AgentType.PUSH) {
            if (!Boolean.TRUE.equals(agentInfo.getDesktopSupported())) {
                log.warn("桌面采集不可用，降级到套壳模式: agentId={}", agentId);
                startShellMode(agentId);
            } else {
                startServiceMode(agentId);
            }
        } else {
            startShellMode(agentId);
        }
        running = true;
    }

    private void handleSignal(Frame frame) {
        String kind = frame.getMetadata() != null
                ? frame.getMetadata().get(FrameCodec.METADATA_KIND) : null;
        if (Session.class.getSimpleName().equals(kind)) {
            Session session = FrameCodec.decodeSignal(frame, Session.class);
            if (session != null && session.isReverseTunnelEnabled()) {
                log.info("收到会话建立通知（反向隧道已启用）: sessionId={}, agentId={}",
                        session.getSessionId(), agentInfo.getId());
                shellService.start();
            }
        }
    }

    private String registerToGateway() {
        String agentId = agentInfo.getId();
        var registerFrame = FrameCodec.encodeSignal(MessageType.SIGNAL, agentId, agentInfo);
        client.getTransport().send(registerFrame);
        log.info("被控端注册到网关: id={}, type={}, verifyCode={}",
                agentId, agentInfo.getAgentType(), agentInfo.getVerifyCode());
        return agentId;
    }

    private void reportCapabilities() {
        var capabilityFrame = FrameCodec.encodeSignal(
                MessageType.SIGNAL, agentInfo.getId(), agentInfo.getEncodingCapability());
        client.getTransport().send(capabilityFrame);
        log.info("已上报编码能力: agentId={}, encodings={}",
                agentInfo.getId(), agentInfo.getEncodingCapability().getEncodings());
    }

    private void startServiceMode(String agentId) {
        service.setScreenCallback(encodedFrame -> {
            var dataFrame = FrameCodec.dataFrame(agentId, encodedFrame);
            client.getTransport().send(dataFrame);
        });
        service.start();
        log.info("服务模式已启动: agentId={}", agentId);
    }

    private void startShellMode(String agentId) {
        shellService.start();
        log.info("套壳模式已启动: agentId={}", agentId);
    }

    public void stop() {
        running = false;
        service.stop();
        shellService.stop();
        vncSessionManager.stopAll();
        client.disconnect();
        log.info("被控端已停止: id={}", agentInfo.getId());
    }

    public AgentInfo getAgentInfo() {
        return agentInfo;
    }

    public AgentService getService() {
        return service;
    }

    public AgentShellService getShellService() {
        return shellService;
    }

    public static void main(String[] args) {
        String gatewayUrl = args.length > 0 ? args[0] : "tcp://localhost:9000";
        String agentId = args.length > 1 ? args[1] : "agent-" + System.currentTimeMillis();
        String verifyCode = args.length > 2 ? args[2] : "0000";
        AgentInfo.AgentType agentType = args.length > 3
                ? AgentInfo.AgentType.valueOf(args[3].toUpperCase()) : AgentInfo.AgentType.PUSH;
        AgentInfo info = AgentInfo.builder()
                .id(agentId)
                .verifyCode(verifyCode)
                .accessCode(verifyCode)
                .platform(System.getProperty("os.name"))
                .agentType(agentType)
                .desktopSupported(detectDesktopSupported())
                .encodingCapability(CodecProfile.builder()
                        .encodings(java.util.List.of("jpeg"))
                        .maxWidth(1920)
                        .maxHeight(1080)
                        .quality(80)
                        .build())
                .build();
        AgentBootstrap bootstrap = new AgentBootstrap(gatewayUrl, info);
        bootstrap.start();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean detectDesktopSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return true;
        }
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank() && !"null".equalsIgnoreCase(display.trim());
    }
}
