package com.chua.remote.agent;

import com.chua.remote.protocol.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * SSH 隧道管理器。
 *
 * <p>管理套壳模式下与本地三方软件（freerdp/vnc/ssh）的端口转发和隧道连接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SSHTunnelManager {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    public SSHTunnelManager(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
    }

    /**
     * 创建 RDP 隧道。
     *
     * @param info 被控端信息
     */
    public void createRDPTunnel(AgentInfo info) {
        log.info("创建 RDP 隧道: agentId={}", info.getId());
    }

    /**
     * 创建 VNC 隧道。
     *
     * @param info 被控端信息
     */
    public void createVNCTunnel(AgentInfo info) {
        log.info("创建 VNC 隧道: agentId={}", info.getId());
    }

    /**
     * 创建 SSH 隧道。
     *
     * @param info 被控端信息
     */
    public void createSSHTunnel(AgentInfo info) {
        log.info("创建 SSH 隧道: agentId={}", info.getId());
    }

    /**
     * 关闭所有隧道。
     */
    public void closeAll() {
        log.info("关闭所有隧道: agentId={}", agentInfo.getId());
    }
}
