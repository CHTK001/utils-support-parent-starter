package com.chua.remote.support.gateway;

import com.chua.remote.support.gateway.agent.AgentHeartbeatChecker;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.GatewayConfigService;
import com.chua.remote.support.gateway.config.GatewayProperties;
import com.chua.remote.support.gateway.core.auth.AclManager;
import com.chua.remote.support.gateway.core.auth.AuthHandler;
import com.chua.remote.support.gateway.core.firewall.GatewayFirewall;
import com.chua.remote.support.gateway.core.ratelimit.GatewayRateLimiter;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.ControllerRegistry;
import com.chua.remote.support.gateway.core.session.SessionManager;
import com.chua.remote.support.gateway.ssh.Socks5AgentBootstrapManager;
import com.chua.remote.support.gateway.transport.http.HttpApiHandler;
import com.chua.remote.support.gateway.transport.http.HttpManagementHandler;
import com.chua.remote.support.gateway.transport.socks5.ReverseSocks5GatewayHandler;
import com.chua.remote.support.gateway.transport.socks5.ReverseSocks5TunnelManager;
import com.chua.remote.support.gateway.transport.socks5.Socks5AgentFrameHandler;
import com.chua.remote.support.gateway.transport.tcp.AgentRelayHandler;
import com.chua.remote.support.gateway.transport.tcp.AgentTcpFrameDecoder;
import com.chua.remote.support.gateway.transport.tcp.BinaryAgentFrameHandler;
import com.chua.remote.support.gateway.transport.tcp.TcpAgentRegisterHandler;
import com.chua.remote.support.gateway.transport.tcp.TcpFrontendHandler;
import com.chua.remote.support.gateway.transport.ws.ClientCapabilityManager;
import com.chua.remote.support.gateway.transport.ws.LiveKitProxyHandler;
import com.chua.remote.support.gateway.transport.ws.MonitorPushService;
import com.chua.remote.support.gateway.transport.ws.RemoteControlWsHandler;
import com.chua.remote.support.gateway.transport.ws.WebSocketProxyHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;

/**
 * 接入网关 Netty 服务器 — 统一管理 7 个端口的启动/停止生命周期。
 *
 * <h3>端口一览</h3>
 * <pre>
 * 端口    | 名称         | 协议    | 用途
 * ────────┼──────────────┼─────────┼──────────────────────────────────────────
 * 9000    | TCP-CONTROL  | TCP     | 前端控制连接 — 浏览器/客户端直接连此端口
 * 9001    | TCP-AGENT    | TCP     | Agent 注册/心跳/数据通道（多 handler 流水线）
 * 3000    | HTTP-MGMT    | HTTP    | 管理控制台页面 + REST 管理 API
 * 8083    | HTTP-API     | HTTP    | 对外 REST API（代理查询/目标管理等）
 * 8081    | WS-API       | WS      | WebSocket 代理网关（通用 WS 转发）
 * 8082    | DWS-REMOTE   | WS      | 远程控制 + LiveKit 代理（路径路由: /remote-control, /livekit）
 * </pre>
 *
 * @author CH
 */
@Slf4j
public class GatewayNettyServer {
    /** 网关配置（端口号、token、心跳间隔等） */
    private final GatewayProperties properties;
    /** 认证处理器 — 验证客户端身份 */
    private final AuthHandler authHandler;
    /** 访问控制管理器 — ACL 权限校验 */
    private final AclManager aclManager;
    /** 目标注册表 — 管理所有可连接的目标（SSH/Desktop） */
    private final TargetRegistry targetRegistry;
    /** 会话管理器 — 创建/销毁/查询远程会话 */
    private final SessionManager sessionManager;
    /** Agent 注册表 — 管理所有已注册的被控端 Agent */
    private final AgentRegistry agentRegistry;
    /** 速率限制器 — TPS/突发流量控制 */
    private final GatewayRateLimiter rateLimiter;
    /** 配置持久化服务 — 读写磁盘配置 */
    private final GatewayConfigService configService;
    /** 监控推送服务 — 向 Web 控制端推送网关指标（连接数、TPS 等） */
    private final MonitorPushService monitorPushService;
    /** 控制端注册表 — 记录当前连接的 Web 控制端 */
    private final ControllerRegistry controllerRegistry = new ControllerRegistry();
    /** 客户端能力管理器 — 记录各会话的编码器/分辨率/帧率声明 */
    private final ClientCapabilityManager capabilityManager = new ClientCapabilityManager();
    /** SOCKS5 反向隧道管理器 */
    private final ReverseSocks5TunnelManager socks5TunnelManager = new ReverseSocks5TunnelManager();
    /** SSH bootstrap 临时 SOCKS5 Agent 管理器 */
    private final Socks5AgentBootstrapManager socks5AgentBootstrapManager;
    /** 防火墙管理器 — IP 黑/白名单过滤 */
    @Getter private final GatewayFirewall firewall = new GatewayFirewall();
    /** 所有已绑定端口的 Channel，用于优雅关闭 */
    private final List<Channel> serverChannels = new ArrayList<>();
    /** Netty boss 线程组（接收连接） */
    private EventLoopGroup bossGroup;
    /** Netty worker 线程组（处理 IO 事件） */
    @Getter private EventLoopGroup workerGroup;
    /** Agent 心跳检测器 — 定期检查 Agent 是否存活 */
    private AgentHeartbeatChecker heartbeatChecker;
    /** 控制端连接清理定时器 — 定期清理失效 WebSocket 连接 */
    private ScheduledExecutorService sweepExecutor;

    public GatewayNettyServer(GatewayProperties p, AuthHandler ah, AclManager am, TargetRegistry tr, SessionManager sm, AgentRegistry ar, GatewayRateLimiter rl) {
        this(p, ah, am, tr, sm, ar, rl, null, null);
    }

    public GatewayNettyServer(GatewayProperties p, AuthHandler ah, AclManager am, TargetRegistry tr,
                               SessionManager sm, AgentRegistry ar, GatewayRateLimiter rl,
                               GatewayConfigService cs, MonitorPushService mps) {
        this.properties = p;
        this.authHandler = ah;
        this.aclManager = am;
        this.targetRegistry = tr;
        this.sessionManager = sm;
        this.agentRegistry = ar;
        this.rateLimiter = rl;
        this.configService = cs;
        this.monitorPushService = mps;
        this.socks5AgentBootstrapManager = new Socks5AgentBootstrapManager(p, ar);
    }

    /**
     * 启动
     */
    public void start() throws InterruptedException {
        int bt = properties.getBossThreads();
        int wt = properties.getWorkerThreads() > 0 ? properties.getWorkerThreads() : Runtime.getRuntime().availableProcessors() * 2;
        bossGroup = new NioEventLoopGroup(bt);
        workerGroup = new NioEventLoopGroup(wt);

        startTcp(properties.getTcpControlPort(), "TCP-CONTROL", () ->
                new TcpFrontendHandler(authHandler, aclManager, rateLimiter, targetRegistry, sessionManager, workerGroup));
        startAgentTcp(properties.getTcpAgentPort(), "TCP-AGENT");
        startSocks5(properties.getSocks5GatewayPort(), "SOCKS5-GW");
        startHttp(properties.getHttpManagementPort(), "HTTP-MGMT");
        startHttpApi(properties.getHttpApiPort(), "HTTP-API");
        startWs(properties.getWsApiGatewayPort(), "WS-API");
        startRemoteControlWs(properties.getDwsRemoteControlPort(), "DWS-REMOTE");

        heartbeatChecker = new AgentHeartbeatChecker(agentRegistry, targetRegistry, properties.getAgentHeartbeatInterval());
        heartbeatChecker.start();
        // 每 30 秒清理失效的控制端连接
        sweepExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "controller-sweep");
            t.setDaemon(true);
            return t;
        });
        sweepExecutor.scheduleWithFixedDelay(() -> {
            try { controllerRegistry.sweep(); }

            catch (Exception e) { log.warn("[ControllerSweep] 异常: {}", e.getMessage()); }
        }, 30, 30, TimeUnit.SECONDS);
        if (monitorPushService != null) { monitorPushService.start(2000); }
        log.info("接入网关启动成功 TCP={}/{} SOCKS5={} HTTP={} API={} WS={} DWS={}",
                properties.getTcpControlPort(), properties.getTcpAgentPort(), properties.getSocks5GatewayPort(),
                properties.getHttpManagementPort(), properties.getHttpApiPort(),
                properties.getWsApiGatewayPort(), properties.getDwsRemoteControlPort());
        // 打印管理页面访问地址
        String token = properties.getManagementToken();
        if (token != null && !token.isEmpty()) {
            log.info("管理页面访问地址: http://{}:{}/?token={}",
                    "localhost", properties.getHttpManagementPort(), token);
        }
    }

    /**
     * 启动 TCP 控制端口 — 前端控制端（浏览器/CLI）通过此端口发送连接/输入/调整大小等指令。
     * 使用 {@link TcpFrontendHandler} 处理所有控制信令。
     */
    private void startTcp(int port, String name, java.util.function.Supplier<ChannelHandler> handler) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024).option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true).childOption(ChannelOption.SO_KEEPALIVE, true)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) { ch.pipeline().addLast(handler.get()); }
                });
        log.info("Binding {}:{}...", name, port);
        serverChannels.add(b.bind(port).sync().channel());
        log.info("{}:{}", name, port);
    }

    /**
     * 启动 SOCKS5 反向代理入口端口。
     */
    private void startSocks5(int port, String name) throws InterruptedException {
        if (port <= 0) {
            log.info("{} disabled", name);
            return;
        }
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024).option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true).childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                Socks5ServerEncoder.DEFAULT,
                                new Socks5InitialRequestDecoder(),
                                new ReverseSocks5GatewayHandler(agentRegistry, configService, socks5TunnelManager)
                        );
                    }
                });
        log.info("Binding {}:{}...", name, port);
        serverChannels.add(b.bind(port).sync().channel());
        log.info("{}:{}", name, port);
    }

    /**
     * Agent TCP 端口 — 被控端 Agent 通过此端口注册、发送心跳和传输数据。
     *
     * <p>流水线: {@link AgentTcpFrameDecoder} → {@link TcpAgentRegisterHandler}
     * → {@link BinaryAgentFrameHandler} → {@link AgentRelayHandler}
     * <ol>
     *   <li>{@code AgentTcpFrameDecoder} — 拆包（4 字节长度前缀 + 变长消息体）</li>
     *   <li>{@code TcpAgentRegisterHandler} — 处理 Agent 注册和身份验证</li>
     *   <li>{@code BinaryAgentFrameHandler} — 桌面远程二进制帧（H.264/H.265）透传</li>
     *   <li>{@code AgentRelayHandler} — 消息中继：Agent ↔ 前端 WebSocket 之间的双向转发</li>
     * </ol>
     */
    private void startAgentTcp(int port, String name) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024).option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true).childOption(ChannelOption.SO_KEEPALIVE, true)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new AgentTcpFrameDecoder(),
                                new TcpAgentRegisterHandler(agentRegistry, targetRegistry, properties, sessionManager),
                                new BinaryAgentFrameHandler(sessionManager, capabilityManager),
                                new Socks5AgentFrameHandler(socks5TunnelManager),
                                new AgentRelayHandler(sessionManager, agentRegistry)
                        );
                    }
                });
        log.info("Binding {}:{}...", name, port);
        serverChannels.add(b.bind(port).sync().channel());
        log.info("{}:{}", name, port);
    }

    /**
     * 启动 HTTP 管理端口 — 提供网关管理控制台和 REST 管理 API。
     * 使用 {@link HttpManagementHandler} 处理：目标列表/Agent 列表/会话管理/防火墙配置/等。
     * 前端可通过 {@code http://localhost:3000/?token=xxx} 访问。
     */
    private void startHttp(int port, String name) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 128)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536),
                                new HttpManagementHandler(targetRegistry, rateLimiter, configService, properties.getAdminPath(),
                                        controllerRegistry, agentRegistry, firewall, socks5TunnelManager, socks5AgentBootstrapManager));
                    }
                });
        log.info("Binding {}:{}...", name, port);
        serverChannels.add(b.bind(port).sync().channel());
        log.info("{}:{}", name, port);
    }

    /**
     * 启动 HTTP API 端口 — 对外提供 RESTful API（目标查询/代理状态/配置读写等）。
     * 使用 {@link HttpApiHandler} 处理，路径前缀由 {@code apiPath} 配置。
     */
    private void startHttpApi(int port, String name) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 128)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536),
                                new HttpApiHandler(targetRegistry, rateLimiter, properties.getApiPath(),
                                        sessionManager, configService, firewall).withAgentRegistry(agentRegistry));
                    }
                });
        log.info("Binding {}:{}...", name, port);
        serverChannels.add(b.bind(port).sync().channel());
        log.info("{}:{}", name, port);
    }

    /**
     * 启动 WebSocket API 网关端口 — 通用 WS 代理转发。
     * 使用 {@link WebSocketProxyHandler}，适用于需要 WebSocket 连接的第三方集成。
     */
    private void startWs(int port, String name) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 128)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(WebSocketProxyHandler.initialPipeline());
                        ch.pipeline().addLast(new WebSocketProxyHandler(sessionManager, rateLimiter));
                    }
                });
        log.info("Binding {}:{}...", name, port);
        serverChannels.add(b.bind(port).sync().channel());
        log.info("{}:{}", name, port);
    }

    /**
     * 启动远程控制 WebSocket 端口 (8082) — 路径路由分发:
     * <ul>
     *   <li><code>/livekit</code> → 由 {@link LiveKitProxyHandler} 处理, 浏览器 livekit-client 透传到 Agent relay</li>
     *   <li>其他路径 → 由 {@link RemoteControlWsHandler} 处理, SSH/Desktop 等传统远程控制</li>
     * </ul>
     *
     * <p>流水线: HTTP codec → aggregator → WS compression → 路径路由器 → (LiveKitProxyHandler | RemoteControlWsHandler)
     */
    private void startRemoteControlWs(int port, String name) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 128)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new UnifiedRemoteEntryHandler());
                    }
                });
        log.info("Binding {}:{}...", name, port);
        serverChannels.add(b.bind(port).sync().channel());
        log.info("{}:{}", name, port);
    }

    private void installSocks5Pipeline(ChannelPipeline p) {
        p.addLast(
                Socks5ServerEncoder.DEFAULT,
                new Socks5InitialRequestDecoder(),
                new ReverseSocks5GatewayHandler(agentRegistry, configService, socks5TunnelManager)
        );
    }

    private void installRemoteControlWsPipeline(ChannelPipeline p) {
        p.addLast(WebSocketProxyHandler.initialPipeline());
        p.addLast(new ChannelInboundHandlerAdapter() {
            /**
             * channelRead
             * @param ctx 参数
             * @param msg 参数
             */
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof HttpRequest) {
                    ChannelPipeline pipe = ctx.pipeline();
                    String uri = ((HttpRequest) msg).uri();
                    if (uri.startsWith("/livekit")) {
                        pipe.addLast(new LiveKitProxyHandler(agentRegistry, properties.getLivekitSfuUrl()));
                    }
 else {
                        pipe.addLast(new RemoteControlWsHandler(
                                targetRegistry, agentRegistry, sessionManager,
                                rateLimiter, capabilityManager, configService,
                                monitorPushService, controllerRegistry));
                    }
                    pipe.remove(this);
                }
                ctx.fireChannelRead(msg);
            }
        });
    }

    private class UnifiedRemoteEntryHandler extends ChannelInboundHandlerAdapter {
        /**
         * channelRead
         * @param ctx 参数
         * @param msg 参数
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf) || !buf.isReadable()) {
                ctx.fireChannelRead(msg);
                return;
            }
            ChannelPipeline p = ctx.pipeline();
            boolean socks5 = buf.getUnsignedByte(buf.readerIndex()) == 0x05;
            p.remove(this);
            if (socks5) {
                installSocks5Pipeline(p);
            }
 else {
                installRemoteControlWsPipeline(p);
            }
            p.fireChannelRead(msg);
        }
    }

    /**
     * 优雅关闭网关 — 按照依赖顺序释放资源：
     * <ol>
     *   <li>停止监控推送和心跳检测</li>
     *   <li>关闭所有绑定端口的 Channel</li>
     *   <li>关闭 Netty 线程池（worker → boss）</li>
     * </ol>
     */
    public void stop() {
        if (monitorPushService != null) { monitorPushService.stop(); }
        if (socks5AgentBootstrapManager != null) { socks5AgentBootstrapManager.closeAll(); }
        if (heartbeatChecker != null) { heartbeatChecker.stop(); }
        if (sweepExecutor != null) { sweepExecutor.shutdown(); }
        serverChannels.forEach(c -> { try { c.close().sync(); }
 catch (Exception ignored) { log.trace("关闭 server channel 失败", ignored); } });
        serverChannels.clear();
        if (workerGroup != null) { workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly(); }
        if (bossGroup != null) { bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly(); }
        log.info("接入网关已关闭");
    }
}
