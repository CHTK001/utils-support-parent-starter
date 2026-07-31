package com.chua.remote.support.gateway;

import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.GatewayConfigService;
import com.chua.remote.support.gateway.config.GatewayProperties;
import com.chua.remote.support.gateway.core.auth.AclManager;
import com.chua.remote.support.gateway.core.auth.AuthHandler;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.chua.remote.support.gateway.ssh.GatewaySshReverseTunnelManager;
import com.chua.remote.support.gateway.transport.ws.MonitorPushService;

/**
 * 独立 Gateway 启动器 — 用于开发/测试，不依赖 Spring Boot
 * @author CH
 */
public class GatewayStandalone {

    public static void main(String[] args) throws Exception {
        GatewayProperties p = new GatewayProperties();
        p.setApiTokenEnabled(true);
        p.setWsApiGatewayPort(8081);
        p.setDwsRemoteControlPort(8082);
        p.setHttpApiPort(8083);
        p.setSocks5GatewayPort(1080);
        p.setTcpControlPort(9000);
        p.setTcpAgentPort(9001);

        AuthHandler ah = (ctx, protocol) -> "standalone";
        AclManager am = (c, t, p2) -> true;
        TargetRegistry tr = new TargetRegistry();
        SessionManager sm = new SessionManager(p.getMaxSessions());
        AgentRegistry ar = new AgentRegistry(p);
        GatewayRateLimiter rl = new GatewayRateLimiter(p.getRateLimitTokensPerSecond(), p.getRateLimitBurstCapacity());
        GatewayConfigService cs = new GatewayConfigService(p, sm, rl);
        cs.init();
        MonitorPushService mps = new MonitorPushService(sm, cs);
        System.out.println("=== ManagementToken: " + p.getManagementToken() + " ===");

        GatewayNettyServer server = new GatewayNettyServer(p, ah, am, tr, sm, ar, rl, cs, mps);
        server.start();

        // 启动 SSH 反向隧道（如已启用）
        if (p.isSshReverseTunnelEnabled()) {
            GatewaySshReverseTunnelManager tunnel = new GatewaySshReverseTunnelManager(p);
            int tunnelPort = tunnel.open();
            if (tunnelPort > 0) {
                Runtime.getRuntime().addShutdownHook(new Thread(tunnel::close));
            }
        }

        System.out.println("=== Gateway 启动成功 ===");
        System.out.println("DWS-REMOTE (远程控制+LiveKit): " + p.getDwsRemoteControlPort());
        System.out.println("TCP-AGENT (Agent 注册): " + p.getTcpAgentPort());
        System.out.println("按 Ctrl+C 停止");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
    }
}
