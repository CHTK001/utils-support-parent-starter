package com.chua.common.support.network.cluster;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter;
import com.chua.common.support.network.server.filter.proxy.ReverseProxyServerFilter;
import com.chua.common.support.network.server.proxy.DiscoveryProxyTargetResolver;
import com.chua.common.support.network.server.proxy.TcpProxyServer;
import com.chua.common.support.scatter.ScatterContext;
import com.chua.common.support.scatter.ScatterNodeServer;
import com.chua.common.support.scatter.ScatterRemoteClient;
import com.chua.common.support.scatter.ScatterResult;
import com.chua.common.support.scatter.ScatterResultWithRequestId;
import com.chua.common.support.scatter.ScatterServiceDiscovery;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.TcpScatterBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 集群节点:组合服务发现(无中心化 hash 交换)+ 业务 Server + HTTP/TCP 代理。
 *
 * <p>一个节点同时承载:</p>
 * <ol>
 *   <li><b>服务发现</b>:基于 {@link ScatterServiceDiscovery} 通过 seeds 引导加入对等网格,
 *       自动发现其他节点、交换身份 hash、按 groupId(scatterId) 注册/查询服务。</li>
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
    private final ScatterServiceDiscovery discovery;
    private final String scatterId;
    private Server httpServer;
    private TcpProxyServer tcpProxy;
    private ScatterNodeServer nodeServer;
    private int httpPort;
    private int tcpPort;
    private List<String> registeredPaths = List.of();

    public ClusterNode(ClusterSetting clusterSetting) throws Exception {
        this.clusterSetting = clusterSetting;
        this.scatterId = clusterSetting.getScatterId() == null || clusterSetting.getScatterId().isBlank()
                ? "default" : clusterSetting.getScatterId();

        // ① 服务发现:无中心化对等网格(seeds 引导 + hash 交换)
        ScatterSetting scatterSetting = clusterSetting.toScatterSetting();
        TcpScatterBuilder builder = new TcpScatterBuilder(scatterSetting);
        this.discovery = builder.buildDiscovery();
        // 配置远程客户端:autoDiscovery 依赖它向其他节点拉取服务列表(未配置则默认返回 failure,
        // 集群节点间服务注册无法互相传播 → 自动发现不收敛)。
        ScatterRemoteClient<Discovery> remoteClient = builder.buildRemoteClient();
        if (remoteClient != null) {
            this.discovery.remoteClient(remoteClient);
        }
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
                // 路由前缀必须带 /**:matchServicePath 仅识别 "/**"/"/*" 结尾或精确前缀
                // (原 path+"**" 拼出 /api** 永不匹配 → 请求被放行 → 本地无路由 404)
                discoveryFilter.addRoute(path.endsWith("/") ? path + "**" : path + "/**", path);
            }
            discoveryFilter.setScatterId(scatterId);
            discoveryFilter.setProtocol("http");
            discoveryFilter.setBalance(clusterSetting.getBalance());
            // 排除本节点:防止请求被转发回自身代理
            discoveryFilter.setExcludeServerId(clusterSetting.getNodeId() + "-http");
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
            proxySetting.setPort(clusterSetting.getPort() > 0 ? clusterSetting.getPort() + 1 : 0);
            String servicePath = paths.get(0);
            tcpProxy = new TcpProxyServer(proxySetting,
                    new DiscoveryProxyTargetResolver(discovery, servicePath, scatterId, clusterSetting.getBalance()));
            tcpProxy.start();
            tcpPort = tcpProxy.getPort();
            log.info("ClusterNode TCP 入口启动: {}:{} (scatterId={})", clusterSetting.getHost(), tcpPort, scatterId);
        }

        // ④ 启动 scatter 节点服务:响应其他节点的远程服务拉取(autoDiscovery 的 sync/request),
        //    否则远程拉取无响应 → 集群节点间服务注册无法互相传播。
        //    监听端口 = scatterPort(显式)或 port+2,与 HTTP(port)/TCP 代理(port+1)分离
        this.nodeServer = new TcpScatterBuilder(clusterSetting.toScatterSetting()).buildNodeServer();
        if (nodeServer != null) {
            // 注意:registerHandler 的适配器会丢弃 clientId,无法定向回复;
            // 需直接用 syncServer.addListener,onMessage 里拿 clientId 回发 ScatterResultWithRequestId
            // (remoteClient 的 subscribe 回调要求该包装类型,否则 future 永不 complete → invoke 超时)
            SyncServer syncServer = nodeServer.getSyncServer();
            if (syncServer != null) {
                syncServer.addListener(new SyncServerListener() {
                    @Override
                    public void onMessage(String clientId, String messageTopic, Object message) {
                        if (!"sync/request".equals(messageTopic) || message == null) {
                            return;
                        }
                        try {
                            // sync 协议为文本行(topic:payload),payload 是 ScatterContext.toString() 输出的 JSON
                            ScatterContext ctx = ScatterContext.fromLine(message.toString());
                            if (ctx == null) {
                                log.warn("ClusterNode 远程查询消息无法解析: {}", message);
                                return;
                            }
                            String requestId = ctx.getRequestId();
                            String path = ctx.getPath();
                            java.util.Set<Discovery> services = discovery.getServiceAll(path);
                            Discovery picked = services.stream().findFirst().orElse(null);
                            ScatterResult<Discovery> result =
                                    picked != null
                                            ? ScatterResult.success(clusterSetting.getNodeId(), picked)
                                            : ScatterResult.failure(clusterSetting.getNodeId(), "无服务");
                            syncServer.send(clientId, "sync/response",
                                    new ScatterResultWithRequestId<>(requestId, result));
                            log.debug("ClusterNode 远程查询已响应: {} 服务={}", path, picked);
                        } catch (Exception e) {
                            log.warn("ClusterNode 远程查询处理异常: {}", e.getMessage());
                        }
                    }
                });
            }
            nodeServer.start();
            log.info("ClusterNode 节点服务启动: {}:{} (scatter 远程查询)",
                    clusterSetting.getHost(), clusterSetting.toScatterSetting().getPort());
        }

        registerSelf(paths);
        this.registeredPaths = paths;
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
    public ScatterServiceDiscovery discovery() {
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
        // ① 先注销本节点服务，防止其他节点继续路由到已关闭节点
        try {
            for (String path : registeredPaths) {
                discovery.unregisterService(path, clusterSetting.getNodeId() + "-http");
                discovery.unregisterService(path, clusterSetting.getNodeId() + "-tcp");
            }
        } catch (Exception ignored) {
        }
        // ② 按顺序关闭:节点服务 → TCP 代理 → HTTP 服务器 → discovery
        if (nodeServer != null) {
            try {
                nodeServer.close();
            } catch (Exception ignored) {
            }
        }
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
