package com.chua.common.support.network.cluster;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.DiscoveryOption;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter;
import com.chua.common.support.network.server.filter.proxy.ReverseProxyServerFilter;
import com.chua.common.support.network.server.proxy.DiscoveryProxyTargetResolver;
import com.chua.common.support.network.server.proxy.TcpProxyServer;
import com.chua.common.support.scattergather.ScatterGatherServiceDiscovery;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 集群节点:组合服务发现(无中心化 hash 交换)+ 业务 Server + HTTP/TCP 代理。
 *
 * <p>一个节点同时承载:</p>
 * <ol>
 *   <li><b>服务发现</b>:{@link ScatterGatherServiceDiscovery} 通过 seeds 引导加入对等网格,
 *       自动发现其他节点、交换身份 hash、按 scatterId 注册/查询服务。</li>
 *   <li><b>HTTP 入口</b>:过滤器链(ServiceDiscoveryServerFilter 按 scatterId 路由
 *       + ReverseProxyServerFilter 转发)到集群内目标节点。</li>
 *   <li><b>TCP 入口</b>:TcpProxyServer 按 discovery(scatterId+tcp)解析目标节点转发。</li>
 * </ol>
 *
 * @author CH
 * @since 2026/08/16
 */
@Slf4j
public class ClusterNode implements AutoCloseable {

    private final ClusterSetting clusterSetting;
    private final ScatterGatherServiceDiscovery discovery;
    private final String scatterId;
    private Server httpServer;
    private TcpProxyServer tcpProxy;
    private int httpPort;
    private int tcpPort;

    public ClusterNode(ClusterSetting clusterSetting) throws Exception {
        this.clusterSetting = clusterSetting;
        this.scatterId = clusterSetting.getScatterId() == null || clusterSetting.getScatterId().isBlank()
                ? "default" : clusterSetting.getScatterId();

        // ① 服务发现:无中心化对等网格(seeds 引导 + hash 交换)
        DiscoveryOption option = new DiscoveryOption();
        if (clusterSetting.getSeeds() != null && !clusterSetting.getSeeds().isEmpty()) {
            option.setAddress(String.join(",", clusterSetting.getSeeds()));
        }
        this.discovery = new ScatterGatherServiceDiscovery(option, clusterSetting.toScatterSetting());
        this.discovery.start();
    }

    /**
     * 启动节点(启动业务 Server 与代理,并注册本节点服务到集群)。
     */
    public void start() throws Exception {
        List<String> paths = clusterSetting.getServicePaths();
        if (paths == null || paths.isEmpty()) {
            paths = List.of("/");
        }

        // ② 启动 HTTP 入口(按 scatterId 路由 + 反向代理)
        if (clusterSetting.isHttpEnabled()) {
            ServerSetting setting = ServerSetting.defaults();
            setting.setHost(clusterSetting.getHost());
            setting.setPort(clusterSetting.getPort());
            httpServer = ServerBuilder.create().type("jdk-http").host(clusterSetting.getHost())
                    .port(clusterSetting.getPort()).build();
            ServiceDiscoveryServerFilter discoveryFilter = new ServiceDiscoveryServerFilter(discovery);
            for (String path : paths) {
                discoveryFilter.addRoute(path + "**", path);
            }
            discoveryFilter.setScatterId(scatterId);
            discoveryFilter.setProtocol("http");
            discoveryFilter.setBalance(clusterSetting.getBalance());
            httpServer.addFilter(discoveryFilter);
            httpServer.addFilter(new ReverseProxyServerFilter((int) clusterSetting.getTimeoutMillis() / 1000));
            httpServer.start();
            httpPort = httpServer.getPort();
            log.info("ClusterNode HTTP 入口启动: {}:{} (scatterId={})", clusterSetting.getHost(), httpPort, scatterId);
        }

        // ③ 启动 TCP 入口(按 scatterId + tcp 解析目标)
        if (clusterSetting.isTcpEnabled()) {
            ServerSetting proxySetting = ServerSetting.defaults();
            proxySetting.setHost(clusterSetting.getHost());
            proxySetting.setPort(clusterSetting.getPort());
            String servicePath = paths.get(0);
            tcpProxy = new TcpProxyServer(proxySetting,
                    new DiscoveryProxyTargetResolver(discovery, servicePath, scatterId, clusterSetting.getBalance()));
            tcpProxy.start();
            tcpPort = tcpProxy.getPort();
            log.info("ClusterNode TCP 入口启动: {}:{} (scatterId={})", clusterSetting.getHost(), tcpPort, scatterId);
        }

        // ④ 注册本节点服务(HTTP + TCP 双协议,按 scatterId 分组)
        registerSelf(paths);
    }

    private void registerSelf(List<String> paths) {
        for (String path : paths) {
            if (clusterSetting.isHttpEnabled() && httpPort > 0) {
                discovery.registerService(path, Discovery.builder()
                        .serverId(clusterSetting.getNodeId() + "-http")
                        .scatterId(scatterId).protocol("http")
                        .host(clusterSetting.getHost()).port(httpPort).weight(1).build());
            }
            if (clusterSetting.isTcpEnabled() && tcpPort > 0) {
                discovery.registerService(path, Discovery.builder()
                        .serverId(clusterSetting.getNodeId() + "-tcp")
                        .scatterId(scatterId).protocol("tcp")
                        .host(clusterSetting.getHost()).port(tcpPort).weight(1).build());
            }
        }
        log.info("ClusterNode 服务已注册: paths={}, scatterId={}, httpPort={}, tcpPort={}",
                paths, scatterId, httpPort, tcpPort);
    }

    /**
     * 获取服务发现(集群视图/路由能力)。
     */
    public ScatterGatherServiceDiscovery discovery() {
        return discovery;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    @Override
    public void close() throws Exception {
        if (tcpProxy != null) {
            try {
                tcpProxy.close();
            } catch (Exception ignored) {
            }
        }
        if (httpServer != null) {
            try {
                httpServer.close();
            } catch (Exception ignored) {
            }
        }
        if (discovery != null) {
            try {
                discovery.close();
            } catch (Exception ignored) {
            }
        }
    }
}
