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
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.model.Session;
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
        this.client = new RemoteClient(agentInfo.getId(), gatewayUrl);
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
        client.connect();
        log.info("已连接到网关");

        client.getTransport().on(MessageType.SIGNAL, this::handleSignal);

        String agentId = registerToGateway();
        reportCapabilities();

        if (agentInfo.getAgentType() == AgentInfo.AgentType.PUSH) {
            if (!Boolean.TRUE.equals(agentInfo.getDesktopSupported())) {
                log.warn("桌面采集不可用（headless/无 X 服务），拒绝桌面连接并降级到套壳模式: agentId={}", agentId);
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
                ? frame.getMetadata().get(com.chua.remote.core.codec.FrameCodec.METADATA_KIND) : null;
        if (Session.class.getSimpleName().equals(kind)) {
            Session session = com.chua.remote.core.codec.FrameCodec.decodeSignal(frame, Session.class);
            if (session != null && session.isReverseTunnelEnabled()) {
                log.info("收到会话建立通知（反向隧道已启用）: sessionId={}, agentId={}",
                        session.getSessionId(), agentInfo.getId());
                shellService.start();
            }
        }
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

    /**
     * 部署启动入口。
     *
     * @param args [0]=网关地址（tcp://host:port），[1]=agentId，[2]=verifyCode，[3]=agentType（PUSH/SHELL，默认 PUSH）
     */
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
                        .encodings(java.util.List.of("h264", "jpeg"))
                        .maxWidth(1920)
                        .maxHeight(1080)
                        .quality(80)
                        .build())
                .build();
        AgentBootstrap bootstrap = new AgentBootstrap(gatewayUrl, info);
        bootstrap.start();
        // 保持 JVM 存活（传输层为 NIO Reactor 异步线程——主线程须阻塞，否则 main 返回即退出）
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 探测桌面环境可用性（屏幕采集可行性）。
     *
     * <p>Windows/macOS 通常有桌面；Linux 需 X 服务（DISPLAY/xvfb），无则 headless——
     * 控制端应提前感知采集不可行（网关/控制端可据此降级或提示）。</p>
     *
     * @return true=支持桌面（可采集）
     */
    public static boolean detectDesktopSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return true;
        }
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank() && !"null".equalsIgnoreCase(display.trim());
    }
}
