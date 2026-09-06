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

    /** 套壳模式类型 */
    private final ShellMode shellMode;

    public AgentShellService(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
        this.sshTunnelManager = new SSHTunnelManager(agentInfo);
        this.shellMode = determineShellMode();
    }

    /**
     * 确定套壳模式类型。
     */
    private ShellMode determineShellMode() {
        String platform = agentInfo.getPlatform();
        if (platform != null) {
            if (platform.toUpperCase().contains("RDP") || platform.toUpperCase().contains("WINDOWS")) {
                return ShellMode.RDP;
            } else if (platform.toUpperCase().contains("VNC")) {
                return ShellMode.VNC;
            }
        }
        return ShellMode.SSH;
    }

    /**
     * 启动套壳模式。
     */
    public void start() {
        running = true;
        // 1. 注册到网关（信令通道）
        registerToGateway();

        // 2. 根据模式启动对应的三方工具
        switch (shellMode) {
            case RDP:
                startRDPSession();
                break;
            case VNC:
                startVNCSession();
                break;
            case SSH:
            default:
                startSSHSession();
                break;
        }
        log.info("套壳模式已启动: agentId={}, mode={}", agentInfo.getId(), shellMode);
    }

    /** 注册到网关 */
    private void registerToGateway() {
        // 上报 id、验证码、接入码、编码能力
        log.info("套壳模式注册: agentId={}, accessCode={}, verifyCode={}, type={}",
                agentInfo.getId(), agentInfo.getAccessCode(), agentInfo.getVerifyCode(), agentInfo.getAgentType());
    }

    /** 启动 RDP 会话（freerdp） */
    private void startRDPSession() {
        sshTunnelManager.startFreerdp(
                agentInfo.getHardwareInfo(), // host
                3389, // default RDP port
                "admin", // username (from config)
                agentInfo.getVerifyCode() // password/verify
        );
        log.info("启动 RDP 会话: agentId={}", agentInfo.getId());
    }

    /** 启动 VNC 会话 */
    private void startVNCSession() {
        sshTunnelManager.startVncViewer(
                agentInfo.getHardwareInfo(), // host
                5900 // default VNC port
        );
        log.info("启动 VNC 会话: agentId={}", agentInfo.getId());
    }

    /** 启动 SSH 会话 */
    private void startSSHSession() {
        sshTunnelManager.startSshShell(
                agentInfo.getHardwareInfo(), // host
                22, // default SSH port
                "root" // username (from config)
        );
        log.info("启动 SSH 会话: agentId={}", agentInfo.getId());
    }

    /**
     * 停止套壳模式。
     */
    public void stop() {
        running = false;
        sshTunnelManager.closeAll();
        log.info("套壳模式已停止: agentId={}", agentInfo.getId());
    }

    /**
     * 套壳模式类型枚举。
     */
    public enum ShellMode {
        /** RDP（freerdp） */
        RDP,
        /** VNC（vncviewer） */
        VNC,
        /** SSH（ssh 命令） */
        SSH
    }
}
