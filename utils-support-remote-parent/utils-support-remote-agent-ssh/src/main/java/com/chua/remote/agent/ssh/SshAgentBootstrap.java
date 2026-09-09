package com.chua.remote.agent.ssh;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.remote.core.RemoteClient;
import com.chua.remote.core.codec.FrameCodec;
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
    private volatile boolean running;

    public SshAgentBootstrap(String gatewayUrl, AgentInfo agentInfo) {
        this.client = new RemoteClient(agentInfo.getId(), gatewayUrl);
        this.agentInfo = agentInfo;
        this.serviceManager = new SshServiceManager();
        // 注册上报的 SSH 凭据（username/password）作为会话缺省凭据（meta 未带时回落）
        this.sessionChannel = new SshSessionChannel(client, agentInfo.getId(), serviceManager,
                agentInfo.getUsername(), agentInfo.getPassword());
    }

    public void start() {
        client.connect();
        log.info("已连接到网关");
        client.getTransport().on(MessageType.SSH, sessionChannel::handleSSHFrame);
        String agentId = registerToGateway();
        running = true;
        // 心跳日志（每 5s——区分进程存活 vs 日志缓冲滞后：心跳持续=进程活着，日志只是延迟刷出）
        Thread.ofPlatform().name("agent-heartbeat").daemon(true).start(() -> {
            while (running) {
                try {
                    Thread.sleep(5000);
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
        SshAgentBootstrap bootstrap = new SshAgentBootstrap(gatewayUrl, info);
        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::stop));
        bootstrap.start();
        // 主线程保活：main 返回后 JVM 立即退出（虚拟线程不阻塞进程退出）——必须阻塞直到停机信号
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
