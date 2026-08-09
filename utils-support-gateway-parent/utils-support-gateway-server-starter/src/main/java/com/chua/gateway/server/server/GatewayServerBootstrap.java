package com.chua.gateway.server.server;

import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.objects.DefaultObjectContext;
import com.chua.common.support.objects.ObjectContextConfig;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.gateway.server.api.ConnectionController;
import com.chua.gateway.server.api.ProtocolScanner;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import com.chua.gateway.server.store.ConnectionStore;
import com.chua.gateway.server.store.SqliteConnectionStore;
import com.chua.gateway.server.tunnel.TunnelRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 服务端 bootstrap（基于 common-starter Server）。
 *
 * <p>启动流程：
 *   <ol>
 *     <li>实例化 TunnelRegistry、ProtocolScanner（共享）</li>
 *     <li>实例化默认 ConnectionStore（SqliteConnectionStore）并 init()</li>
 *     <li>构造 ConnectionController（注入上述依赖）</li>
 *     <li>通过 ServiceProvider.of(Server.class).getNewExtension("jdk", setting) 创建 server</li>
 *     <li>registerBean(controller) — common-starter 自动扫描 @RequestMethod 注册路由</li>
 *     <li>server.start()</li>
 *   </ol>
 * </p>
 *
 * <p>提供 {@link #start()} 和 {@link #stop()} 包装供 GatewayServerApplication main 调用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class GatewayServerBootstrap {

    /**
     * 默认 WebSocket 帧大小（10MB）
     */
    private static final int WS_MAX_FRAME_BYTES = 10 * 1024 * 1024;

    /**
     * 默认 backlog
     */
    private static final int DEFAULT_BACKLOG = 8192;

    /**
     * host 占位 IP（common-starter 默认）
     */
    private static final String DEFAULT_BIND_HOST = "0.0.0.0";

    /**
     * Server SPI 类型（jdk = JDK HttpServer 纯 JDK 实现）
     */
    private static final String SERVER_TYPE_JDK = "jdk";

    /**
     * Tunnel Registry
     */
    private final TunnelRegistry tunnelRegistry;

    /**
     * 协议扫描器
     */
    private final ProtocolScanner protocolScanner;

    /**
     * 连接存储
     */
    private final ConnectionStore connectionStore;

    /**
     * common-starter Server 实例
     */
    private Server server;

    /**
     * 默认构造：使用 SqliteConnectionStore（生产环境）
     */
    public GatewayServerBootstrap() {
        this(new SqliteConnectionStore(), new TunnelRegistry(), new ProtocolScanner());
    }

    /**
     * 注入构造（测试或定制存储用）。
     *
     * @param connectionStore 连接存储实现
     * @param tunnelRegistry   Tunnel 注册中心
     * @param protocolScanner  协议扫描器
     */
    public GatewayServerBootstrap(ConnectionStore connectionStore,
                                 TunnelRegistry tunnelRegistry,
                                 ProtocolScanner protocolScanner) {
        this.connectionStore = connectionStore;
        this.tunnelRegistry = tunnelRegistry;
        this.protocolScanner = protocolScanner;
    }

    /**
     * 启动服务。
     */
    public void start() {
        // 1. 初始化存储
        connectionStore.init();

        // 2. 构造 controller
        ConnectionController controller = new ConnectionController(
                connectionStore, tunnelRegistry, protocolScanner);

        // 3. 构造 server setting
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost(DEFAULT_BIND_HOST);
        setting.setPort(GatewayProperties.httpPort());
        setting.setBacklog(DEFAULT_BACKLOG);
        setting.setMaxRequestSize(WS_MAX_FRAME_BYTES);

        // 4. 通过 SPI 加载 Server 实现并 build
        Server raw = ServerProvider.of(SERVER_TYPE_JDK, setting);
        if (raw == null) {
            throw new IllegalStateException("未找到 Server SPI 实现: " + SERVER_TYPE_JDK);
        }
        this.server = raw;

        // 4.5 替换 server 自带的 ObjectContext（必须先 init() 让 registry 就绪）
        DefaultObjectContext ctx = new DefaultObjectContext(ObjectContextConfig.builder()
                .spiEnabled(true)
                .annotationScanEnabled(false)
                .build());
        ctx.init();
        raw.setObjectContext(ctx);

        // 5. register controller（让 @RequestMethod 反射挂在 bean context 上）
        raw.registerBean(controller);

        // 6. 注册自定义 UrlMappingFilter 和路由
        GatewayUrlMappingFilter mappingFilter = new GatewayUrlMappingFilter();
        RouteRegistrar.register(mappingFilter, connectionStore, protocolScanner, tunnelRegistry);
        log.info("[gateway-server] 路由已注册: count={}", mappingFilter.routeCount());
        log.info("[gateway-server] 当前 routes: {}", mappingFilter.getRoutes());
        // 移除旧 UrlMappingServerFilter 并加入自定义 filter
        try {
            java.lang.reflect.Field f = com.chua.common.support.network.server.AbstractServer.class
                    .getDeclaredField("urlMappingFilter");
            f.setAccessible(true);
            Object oldFilter = f.get(raw);
            if (oldFilter != null && oldFilter instanceof ServerFilter) {
                raw.removeFilter((ServerFilter) oldFilter);
            }
        } catch (Exception ex) {
            log.warn("[gateway-server] 移除旧 urlMappingFilter 失败: {}", ex.getMessage());
        }
        raw.addFilter(mappingFilter);
        raw.refreshFilters();

        // 7. 启动 server
        raw.start();
        log.info("[gateway-server] Gateway 服务端启动完成: port={} type={}", setting.getPort(), raw.getProtocol());
    }

    /**
     * 获取实际绑定的 HTTP 端口（当配置为 0 时由操作系统分配）。
     *
     * @return 端口号
     */
    public int bindingPort() {
        if (server == null) {
            return GatewayProperties.httpPort();
        }
        return server.getPort();
    }

    /**
     * 停止服务。
     */
    public void stop() {
        if (server != null && server.isRunning()) {
            server.close();
            log.info("[gateway-server] Gateway 服务端已停止");
        }
    }

    /**
     * 获取活跃 Tunnel Registry（外部调用，如监控和 WS 桥接）。
     *
     * @return TunnelRegistry
     */
    public TunnelRegistry tunnelRegistry() {
        return tunnelRegistry;
    }

    /**
     * 列出已注册的协议（启动时打印）。
     *
     * @return 协议名列表
     */
    public List<String> protocolList() {
        return protocolScanner.listProtocols();
    }

    /**
     * ServerProvider 简单包装，避免重复 import 名字。
     * 静态 inner class。
     */
    private static final class ServerProvider {
        static Server of(String type, ServerSetting setting) {
            Server raw = ServiceProvider.of(Server.class).getNewExtension(type, setting);
            if (raw == null) {
                return null;
            }
            if (!(raw instanceof Server)) {
                return null;
            }
            return raw;
        }
    }
}
