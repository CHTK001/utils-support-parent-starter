package com.chua.remote.support.gateway.autoconfigure;

import com.chua.remote.support.spi.RemoteGatewaySpi;
import com.chua.remote.support.gateway.GatewayNettyServer;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.GatewayConfigService;
import com.chua.remote.support.gateway.config.GatewayProperties;
import com.chua.remote.support.gateway.core.GatewayRemoteSpiImpl;
import com.chua.remote.support.gateway.core.auth.AclManager;
import com.chua.remote.support.gateway.core.auth.AuthHandler;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.chua.remote.support.gateway.transport.ws.MonitorPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 接入网关 Spring Boot 自动配置
 * <p>当 {@code gateway.enabled=true}（默认）时自动装配所有网关核心组件：
 * <ul>
 *   <li>{@link TargetRegistry} — 目标节点注册表</li>
 *   <li>{@link SessionManager} — 会话管理器</li>
 *   <li>{@link AgentRegistry} — Agent 注册表</li>
 *   <li>{@link GatewayRateLimiter} — 速率限制器</li>
 *   <li>{@link AuthHandler} — 认证处理器</li>
 *   <li>{@link AclManager} — 访问控制管理器</li>
 *   <li>{@link GatewayConfigService} — 配置持久化服务</li>
 *   <li>{@link MonitorPushService} — 监控推送服务</li>
 *   <li>{@link GatewayNettyServer} — Netty 服务器（8 端口）</li>
 *   <li>{@link RemoteGatewaySpi} — 远程网关 SPI</li>
 * </ul>
 *
 * @author CH
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
@ConditionalOnProperty(prefix = "gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GatewayAutoConfiguration {

    /** 目标节点注册表 */
    @Bean @ConditionalOnMissingBean
    public TargetRegistry targetRegistry() { return new TargetRegistry(); }

    /** 会话管理器（从配置读取最大会话数） */
    @Bean @ConditionalOnMissingBean
    public SessionManager sessionManager(GatewayProperties p) { return new SessionManager(p.getMaxSessions()); }

    /** Agent 注册表（从配置读取禁用列表、过期时间等） */
    @Bean @ConditionalOnMissingBean
    public AgentRegistry agentRegistry(GatewayProperties p) { return new AgentRegistry(p); }

    /** 网关速率限制器（TPS + 突发容量） */
    @Bean @ConditionalOnMissingBean
    public GatewayRateLimiter rateLimiter(GatewayProperties p) {
        return new GatewayRateLimiter(p.getRateLimitTokensPerSecond(), p.getRateLimitBurstCapacity());
    }

    /** 认证处理器 — 默认根据远程地址提取客户端标识 */
    @Bean @ConditionalOnMissingBean
    public AuthHandler authHandler() {
        return (ctx, protocol) -> ctx.channel().remoteAddress() instanceof java.net.InetSocketAddress addr ? addr.getHostString() : "anonymous";
    }

    /** ACL 管理器 — 默认允许所有请求 */
    @Bean @ConditionalOnMissingBean
    public AclManager aclManager() { return (c, t, p) -> true; }

    /** 配置持久化服务（init 方法加载磁盘配置） */
    @Bean(initMethod = "init")
    public GatewayConfigService gatewayConfigService(GatewayProperties p, SessionManager sm, GatewayRateLimiter rl) {
        return new GatewayConfigService(p, sm, rl);
    }

    /** 监控推送服务（向 Web 控制端推送连接数/TPS 等指标） */
    @Bean
    public MonitorPushService monitorPushService(SessionManager sm, GatewayConfigService cs) {
        return new MonitorPushService(sm, cs);
    }

    /** 接入网关 Netty 服务器 — 统一管理 8 个端口 */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public GatewayNettyServer gatewayNettyServer(GatewayProperties p, AuthHandler ah, AclManager am,
                                                  TargetRegistry tr, SessionManager sm, AgentRegistry ar,
                                                  GatewayRateLimiter rl, GatewayConfigService cs,
                                                  MonitorPushService mps) {
        return new GatewayNettyServer(p, ah, am, tr, sm, ar, rl, cs, mps);
    }

    /** 远程网关 SPI — 供外部系统通过 SPI 接口调用网关能力 */
    @Bean @ConditionalOnMissingBean(RemoteGatewaySpi.class)
    public RemoteGatewaySpi gatewaySpi(TargetRegistry tr, SessionManager sm) {
        return new GatewayRemoteSpiImpl(tr, sm);
    }
}
