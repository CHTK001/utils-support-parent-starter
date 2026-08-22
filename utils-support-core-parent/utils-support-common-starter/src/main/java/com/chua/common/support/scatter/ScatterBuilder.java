package com.chua.common.support.scatter;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.tcp.TcpClient;
import com.chua.common.support.network.tcp.TcpServer;
import com.chua.common.support.scatter.discovery.AbstractScatterDiscovery;
import com.chua.common.support.scatter.discovery.RouteModeDiscovery;
import com.chua.common.support.scatter.discovery.SeedModeDiscovery;
import com.chua.common.support.scatter.node.ScatterNodeHandler;
import com.chua.common.support.scatter.node.ScatterTcpNodeServer;
import com.chua.common.support.scatter.node.ScatterNodeServer;
import com.chua.common.support.scatter.node.ScatterTcpNodeServer;
import com.chua.common.support.scatter.node.UdpScatterNodeServer;

/**
 * scatter 构建器（仅 tcp/udp 两协议）。
 *
 * <p>SPI 注入（未启动前）：{@code spiName} 指定实现（如 "tcp"/"vertx-tcp"），
 * 或直接注入 {@code server}/{@code client} 实现对象；都为空则默认 jdk 实现。</p>
 *
 * @param <B> 构建器自身类型
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings("unchecked")
public abstract class ScatterBuilder<B extends ScatterBuilder<B>> {

    protected final ScatterSetting setting;

    protected ScatterBuilder(String protocol) {
        this(protocol, new ScatterSetting());
    }

    protected ScatterBuilder(String protocol, ScatterSetting setting) {
        this.setting = setting == null ? new ScatterSetting() : setting;
        this.setting.setProtocol(protocol);
    }

    public static UdpScatterBuilder udp() {
        return new UdpScatterBuilder();
    }

    public static TcpScatterBuilder tcp() {
        return new TcpScatterBuilder();
    }

    public B nodeId(String nodeId) {
        setting.setNodeId(nodeId);
        return (B) this;
    }

    public B groupId(String groupId) {
        setting.setGroupId(groupId);
        return (B) this;
    }

    public B host(String host) {
        setting.setHost(host);
        return (B) this;
    }

    public B port(int port) {
        setting.setPort(port);
        return (B) this;
    }

    public B announceHost(String announceHost) {
        setting.setAnnounceHost(announceHost);
        return (B) this;
    }

    public B servicePath(String servicePath) {
        setting.setServicePath(servicePath);
        return (B) this;
    }

    /** 路由模式：设置网段（如 "192.168.1.0/24"）。 */
    public B subnet(String subnet) {
        setting.setSubnet(subnet);
        return (B) this;
    }

    /** seed 引导模式：设置 seed 地址列表。 */
    public B seeds(String... addresses) {
        setting.setSeeds(java.util.Arrays.asList(addresses));
        return (B) this;
    }

    public B seeds(java.util.List<String> addresses) {
        setting.setSeeds(addresses);
        return (B) this;
    }

    /** SPI 实现名（如 "tcp"/"vertx-tcp"），空则默认 jdk。 */
    public B spiName(String spiName) {
        setting.setSpiName(spiName);
        return (B) this;
    }

    /** 直接注入服务端实现对象（未启动）。 */
    public B server(TcpServer server) {
        setting.setServer(server);
        return (B) this;
    }

    /** 直接注入客户端实现对象（未启动）。 */
    public B client(TcpClient client) {
        setting.setClient(client);
        return (B) this;
    }

    public B autoDiscoveryInterval(long millis) {
        setting.setAutoDiscoveryIntervalMillis(millis);
        return (B) this;
    }

    public B heartbeatInterval(long millis) {
        setting.setHeartbeatIntervalMillis(millis);
        return (B) this;
    }

    public B failRemoveCount(int count) {
        setting.setFailRemoveCount(count);
        return (B) this;
    }

    public B timeoutMillis(long millis) {
        setting.setTimeoutMillis(millis);
        return (B) this;
    }

    public B persistenceEnabled(boolean enabled) {
        setting.setPersistenceEnabled(enabled);
        return (B) this;
    }

    /** 持久化文件路径。 */
    public B persistenceFile(String file) {
        setting.setPersistenceFile(file);
        return (B) this;
    }

    /**
     * 构建节点服务端。
     *
     * @param handler 帧处理器（discovery）
     * @return 节点服务端
     */
    public ScatterNodeServer buildNodeServer(ScatterNodeHandler handler) {
        ServerSetting serverSetting = ServerSetting.defaults();
        serverSetting.setHost(setting.getHost());
        serverSetting.setPort(setting.getPort());
        serverSetting.setProtocol(setting.getProtocol());
        serverSetting.setBacklog(2048);
        // 缩短 readTimeout：与 scatter timeoutMillis 一致，确保健康检查能快速检测到对端下线
        serverSetting.setReadTimeout((int) Math.min(setting.getTimeoutMillis(), 3000L));
        if ("udp".equalsIgnoreCase(setting.getProtocol())) {
            return new ScatterNodeServerWrapper(
                    new UdpScatterNodeServer(serverSetting, handler), setting);
        }
        return new ScatterNodeServerWrapper(
                new ScatterTcpNodeServer(serverSetting, handler), setting);
    }

    /**
     * 构建远程客户端（短连接）。
     *
     * @return 远程客户端
     */
    public ScatterRemoteClient buildRemoteClient() {
        if (setting.getClient() != null) {
            ScatterSyncHelper.setCustomClient(setting.getClient());
        }
        if (setting.getSpiName() != null && !setting.getSpiName().isBlank()) {
            return com.chua.common.support.spi.ServiceProvider.of(ScatterRemoteClient.class)
                    .getNewExtension(setting.getSpiName(), setting);
        }
        if ("udp".equalsIgnoreCase(setting.getProtocol())) {
            return new UdpScatterRemoteClient();
        }
        return new TcpScatterRemoteClient();
    }

    /**
     * 构建发现服务（按模式选择路由/seed）。
     *
     * @return 发现服务
     */
    public AbstractScatterDiscovery buildDiscovery() {
        AbstractScatterDiscovery discovery;
        if (setting.getSubnet() != null && !setting.getSubnet().isBlank()) {
            discovery = new RouteModeDiscovery(setting);
        } else {
            discovery = new SeedModeDiscovery(setting);
        }
        discovery.remoteClient(buildRemoteClient());
        return discovery;
    }

    /**
     * 聚合入口：构建完整 scatter（discovery + nodeServer 组装）。
     *
     * @return Scatter 聚合实例
     */
    public Scatter build() {
        return new DefaultScatter(this);
    }

    /** 节点服务端包装：桥接 ScatterNodeServer 接口。 */
    private static final class ScatterNodeServerWrapper implements ScatterNodeServer {
        private final AutoCloseable delegate;
        private final ScatterSetting setting;

        ScatterNodeServerWrapper(AutoCloseable delegate, ScatterSetting setting) {
            this.delegate = delegate;
            this.setting = setting;
        }

        @Override
        public void start() throws Exception {
            if (delegate instanceof com.chua.common.support.network.server.Server s) {
                s.start();
            } else if (delegate instanceof Runnable r) {
                r.run();
            }
        }

        @Override
        public void stop() throws Exception {
            if (delegate instanceof com.chua.common.support.network.server.Server s) {
                s.stop();
            } else {
                delegate.close();
            }
        }

        @Override
        public int getPort() {
            if (delegate instanceof com.chua.common.support.network.server.Server s) {
                return s.getPort();
            }
            return setting.getPort();
        }
    }
}
