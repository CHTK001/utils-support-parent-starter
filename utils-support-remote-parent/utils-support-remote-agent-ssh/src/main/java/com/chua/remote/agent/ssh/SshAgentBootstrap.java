package com.chua.remote.agent.ssh;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.FrameServer;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;

/**
 * SSH agent 入口。
 *
 * <p>注册网关（AgentInfo：agentType=FORWARD + 平台 + sshd 状态 + SSH 凭据）
 * + 挂接 SSH 帧监听（{@link SshSessionChannel}——本机 sshd 会话代理）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("remote-agent-ssh")
public class SshAgentBootstrap {

    private final RemoteClient client;
    private final AgentInfo agentInfo;
    private final SshServiceManager serviceManager;
    private final SshSessionChannel sessionChannel;
    /** 会话服务端（9004——网关每会话独立建连——多会话并发；信令仍走 9000 主连接） */
    private FrameServer sessionServer;
    /** 会话端口（agent 挂端口——会话数据通道） */
    private static final int SESSION_PORT = 9004;
    private volatile boolean running;

    public SshAgentBootstrap(String gatewayUrl, AgentInfo agentInfo) {
        this.client = new RemoteClient(agentInfo.getId(), gatewayUrl);
        this.agentInfo = agentInfo;
        this.serviceManager = new SshServiceManager();
        this.sessionChannel = new SshSessionChannel(client, agentInfo.getId(), serviceManager);
    }

    public void start() {
        client.connect();
        log.info("已连接到网关");
        // 会话服务端：agent 挂端口 9004——网关每会话独立建连（多会话并发互不干扰）
        try {
            sessionServer = new FrameServer(ServerSetting.builder()
                    .host("0.0.0.0").port(SESSION_PORT).build());
            sessionServer.setListener((clientId, frame) -> {
                if (frame.getType() == MessageType.SSH) {
                    sessionChannel.handleSSHFrame(frame, clientId);
                }
            });
            sessionServer.start();
            sessionChannel.bindSessionServer(sessionServer);
            log.info("会话服务端已启动: port={}", SESSION_PORT);
        } catch (Exception e) {
            log.warn("会话服务端启动失败（会话帧将回退 9000 主通道）: {}", e.getMessage());
        }
        String agentId = registerToGateway();
        running = true;
        // 心跳日志（每 5s——区分进程存活 vs 日志缓冲滞后：心跳持续=进程活着，日志只是延迟刷出）
        Thread.ofPlatform().name("agent-heartbeat").daemon(true).start(() -> {
            while (running) {
                try {
                    Thread.sleep(5000);
                    // 心跳附带重发注册（幂等——首次注册帧随机丢失后自动补上，网关按 agentId 覆盖）
                    try {
                        registerToGateway();
                    } catch (Exception e) {
                        log.warn("心跳重注册失败: {}", e.getMessage());
                    }
                    log.info("agent-keepalive-tick: id={}", agentInfo.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        log.info("SSH agent 就绪: agentId={}, platform={}, sshdPresent={}",
                agentId, serviceManager.getPlatform(), serviceManager.isForwardMode());
    }

    /**
     * 注册到网关：AgentInfo（agentType=FORWARD + 平台 + sshd 状态 + SSH 凭据）。
     */
    private String registerToGateway() {
        String agentId = agentInfo.getId();
        agentInfo.setPlatform(serviceManager.getPlatform().name());
        agentInfo.setAgentType(AgentInfo.AgentType.FORWARD);
        agentInfo.setDesktopSupported(detectDesktopSupported());
        if (agentInfo.getExtra() == null) {
            agentInfo.setExtra(new HashMap<>());
        }
        agentInfo.getExtra().put("sshdAvailable", String.valueOf(serviceManager.isForwardMode()));
        var registerFrame = FrameCodec.encodeSignal(MessageType.SIGNAL, agentId, agentInfo);
        client.getTransport().send(registerFrame);
        log.info("被控端注册到网关: id={}, type={}, platform={}, sshdPresent={}, ssh={}@127.0.0.1",
                agentId, agentInfo.getAgentType(), agentInfo.getPlatform(),
                serviceManager.isForwardMode(), agentInfo.getUsername());
        return agentId;
    }

    /**
     * 桌面能力检测：Windows/macOS 恒有桌面；Linux 依赖 DISPLAY（无则 headless）。
     *
     * @return 是否支持桌面
     */
    private static boolean detectDesktopSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return true;
        }
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank() && !"null".equalsIgnoreCase(display.trim());
    }

    public void stop() {
        log.info("SSH agent 停止中: id={}, 调用线程={}", agentInfo.getId(), Thread.currentThread().getName());
        Thread.getAllStackTraces().forEach((t, st) -> {
            if ("main".equals(t.getName())) {
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement e : st) {
                    sb.append("\n    at ").append(e);
                }
                log.info("main 线程栈（死亡瞬间）:{}", sb);
            }
        });
        running = false;
        if (sessionServer != null) {
            try {
                sessionServer.stop();
            } catch (Exception ignored) {
            }
        }
        sessionChannel.stopAll();
        client.disconnect();
        log.info("SSH agent 已停止: id={}", agentInfo.getId());
    }

    public static void main(String[] args) {
        String gatewayUrl = args.length > 0 ? args[0] : "tcp://localhost:9000";
        String agentId = args.length > 1 ? args[1] : "ssh-agent-" + System.currentTimeMillis();
        String verifyCode = args.length > 2 ? args[2] : "0000";
        String username = args.length > 3 ? args[3] : System.getProperty("user.name", "");
        String password = args.length > 4 ? args[4] : "";
        AgentInfo info = new AgentInfo();
        info.setId(agentId);
        info.setVerifyCode(verifyCode);
        info.setAccessCode(verifyCode);
        info.setUsername(username);
        info.setPassword(password);
        // 多网卡全部 IP 上报（白名单校验：命中其一即通过）——复用 NetUtils 枚举
        info.setIps(com.chua.common.support.network.net.NetUtils.getLocalIps());
        SshAgentBootstrap bootstrap = new SshAgentBootstrap(gatewayUrl, info);
        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::stop));
        bootstrap.start();
        // 主线程保活 + 断连重连：连接丢失（Connection reset 等）后自动重建而非停机
        while (bootstrap.running) {
            if (!bootstrap.isConnected()) {
                log.info("连接断开，5 秒后重连...");
                try {
                    Thread.sleep(5000);
                    bootstrap.reconnect();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("重连失败: {}", e.getMessage());
                }
            } else {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 连接状态。
     *
     * @return 已连接
     */
    public boolean isConnected() {
        return client.isConnected();
    }

    /**
     * 断连重连：重新连接网关并重新注册（会话通道 9004 独立保持——不重新挂接 9000 SSH 帧）。
     */
    public void reconnect() {
        client.connect();
        registerToGateway();
        log.info("重连完成并重新注册: id={}", agentInfo.getId());
    }
}
