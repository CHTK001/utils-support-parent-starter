package com.chua.remote.agent;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * 套壳模式被控端实现。
 *
 * <p>Java 启动器内启动三方软件（freerdp/vnc/ssh），
 * 负责网网关注册 id、验证码等信息，媒体流转发到本地软件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AgentShellService {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** SSH 隧道管理器 */
    private final SSHTunnelManager sshTunnelManager;

    /** 是否运行中 */
    private volatile boolean running;

    public AgentShellService(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
        this.sshTunnelManager = new SSHTunnelManager(agentInfo);
    }

    /**
     * 启动套壳模式。
     */
    public void start() {
        running = true;
        // 1. 注册到网关（信令通道）
        registerToGateway();

        // 2. 根据 agentType 启动对应的三方工具
        if ("RDP".equalsIgnoreCase(agentInfo.getPlatform())) {
            startRDPSession();
        } else if ("VNC".equalsIgnoreCase(agentInfo.getPlatform())) {
            startVNCSession();
        } else {
            startSSHSession();
        }
        log.info("套壳模式已启动: agentId={}", agentInfo.getId());
    }

    /** 注册到网关 */
    private void registerToGateway() {
        // 上报 id、验证码、接入码、编码能力
        log.info("套壳模式注册: agentId={}, accessCode={}, verifyCode={}",
                agentInfo.getId(), agentInfo.getAccessCode(), agentInfo.getVerifyCode());
    }

    /** 启动 RDP 会话（freerdp） */
    private void startRDPSession() {
        // 启动 freerdp 进程或转发到本地 freerdp 端口
        log.info("启动 RDP 会话: agentId={}", agentInfo.getId());
        sshTunnelManager.createRDPTunnel(agentInfo);
    }

    /** 启动 VNC 会话 */
    private void startVNCSession() {
        // 启动 vnc 服务或转发到本地 vnc 端口
        log.info("启动 VNC 会话: agentId={}", agentInfo.getId());
        sshTunnelManager.createVNCTunnel(agentInfo);
    }

    /** 启动 SSH 会话 */
    private void startSSHSession() {
        // 启动 ssh 连接或转发到本地 ssh 服务
        log.info("启动 SSH 会话: agentId={}", agentInfo.getId());
        sshTunnelManager.createSSHTunnel(agentInfo);
    }

    /**
     * 停止套壳模式。
     */
    public void stop() {
        running = false;
        sshTunnelManager.closeAll();
        log.info("套壳模式已停止: agentId={}", agentInfo.getId());
    }
}
