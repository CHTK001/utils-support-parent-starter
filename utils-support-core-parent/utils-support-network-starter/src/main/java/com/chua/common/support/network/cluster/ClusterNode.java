package com.chua.common.support.network.cluster;

import com.chua.common.support.scatter.ScatterRemoteClient;
import com.chua.common.support.scatter.ScatterServiceDiscovery;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.TcpScatterBuilder;
import com.chua.common.support.scatter.node.ScatterNodeServer;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.discovery.ServiceDiscoveryServerFilter;
import com.chua.common.support.network.server.filter.proxy.ReverseProxyServerFilter;
import com.chua.common.support.network.server.proxy.DiscoveryProxyTargetResolver;
import com.chua.common.support.network.server.proxy.TcpProxyServer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
* 集群节点：组合 Scatter 服务发现 + HTTP/TCP 双入口代理。
*
* <p>启动时自动完成：</p>
* <ol>
*   <li>通过 Scatter（seed/gateway 模式）加入对等网格</li>
*   <li>启动 HTTP 入口（按 scatterId 路由，转发到集群内目标节点）</li>
*   <li>启动 TCP 入口（按 scatterId + tcp 解析目标转发）</li>
*   <li>将本节点自身能力（基于注册进来的 ServerEntry 自动推断）注册进集群</li>
*   <li>将显式 addServer 的远端目标也注册进集群，由 scatter 扩散</li>
* </ol>
*
* <p>scatter 内置路由决策：请求到达本节点时，按 path+protocol 查内部 hash 表，
* 若本节点有能力则本地处理（由业务 过滤器 实现），否则转发至集群内其他节点。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class ClusterNode implements AutoCloseable {

    private final ClusterSetting clusterSetting; // clustersetting
    private final ScatterServiceDiscovery discovery; // discovery
    private final String scatterId; // scatterid
    private final String selfNodeId; // self节点标识

    private Server httpServer; // http服务端
    private TcpProxyServer tcpProxy; // tcp代理
    private ScatterNodeServer nodeServer; // 节点服务端
    private int httpPort; // http端口
    private int tcpPort; // tcp端口
    private int scatterPort; // scatter端口
    /** 本节点实际注册的服务路径列表 */
    private List<String> registeredPaths = List.of();
    /** 本节点注册的 http/tcp 服务端标识，用于注销 */
    private final List<String> selfServerIds = new ArrayList<>();

    /**
    * cluster节点。
    * @param clusterSetting clustersetting
     */
    public ClusterNode(ClusterSetting clusterSetting) throws Exception {
        this.clusterSetting = clusterSetting;
        this.scatterId = clusterSetting.getScatterId() == null || clusterSetting.getScatterId().isBlank()
                ? "default" : clusterSetting.getScatterId();
        this.selfNodeId = clusterSetting.getNodeId();

        ScatterSetting scatterSetting = clusterSetting.toScatterSetting();
        TcpScatterBuilder builder = new TcpScatterBuilder(scatterSetting);
        this.discovery = builder.buildDiscovery();
        ScatterRemoteClient remoteClient = builder.buildRemoteClient();
        if (remoteClient != null) {
            this.discovery.remoteClient(remoteClient);
        }
 // 先启动 discovery（不启动定时任务），节点服务端 端口确定后再 启动
    }

    /**
    * 启动节点：绑定端口 → 启动 discovery 定时任务 → 启动 HTTP/TCP 代理 → 注册服务。
     */
    public void start() throws Exception {
 // ① 先启动 节点服务端，确定 scatter 通信端口
        this.nodeServer = new TcpScatterBuilder(clusterSetting.toScatterSetting())
                .buildNodeServer(discovery);
        nodeServer.start();
        clusterSetting.setScatterPort(nodeServer.getPort());
        this.scatterPort = nodeServer.getPort();
        log.info("ClusterNode scatter 节点服务启动: {}:{} ", clusterSetting.getHost(), nodeServer.getPort());

        // ② 更新 discovery 的端口配置后再启动定时任务
        discovery.getSetting().setPort(nodeServer.getPort());
        discovery.start();

        // ③ 启动 HTTP 入口
        if (clusterSetting.isHttpEnabled()) {
            httpServer = ServerBuilder.create().type("jdk-http")
                    .host(clusterSetting.getHost()).port(clusterSetting.getPort()).build();
            ServiceDiscoveryServerFilter filter = new ServiceDiscoveryServerFilter(discovery);
            for (String path : resolveServicePaths()) {
                String pattern = path.endsWith("/") ? path + "**" : path + "/**";
                filter.addRoute(pattern, path);
            }
            filter.setScatterId(scatterId);
            filter.setProtocol("http");
            filter.setBalance(clusterSetting.getBalance());
            String excludeId = selfNodeId != null ? selfNodeId + "-http" : "";
            if (!excludeId.isBlank()) {
                filter.setExcludeServerId(excludeId);
            }
            httpServer.addFilter(filter);
            httpServer.addFilter(new ReverseProxyServerFilter(
                    (int) Math.min(clusterSetting.getTimeoutMillis() / 1000, Integer.MAX_VALUE)));
            httpServer.start();
            httpPort = httpServer.getPort();
            log.info("ClusterNode HTTP 入口启动: {}:{} (scatterId={})", clusterSetting.getHost(), httpPort, scatterId);
        }

        // ④ 启动 TCP 入口
        if (clusterSetting.isTcpEnabled()) {
            ServerSetting proxySetting = ServerSetting.defaults();
            proxySetting.setHost(clusterSetting.getHost());
            proxySetting.setPort(clusterSetting.getPort() > 0 ? clusterSetting.getPort() + 1 : 0);
            String servicePath = resolveServicePaths().isEmpty() ? "/" : resolveServicePaths().get(0);
            tcpProxy = new TcpProxyServer(proxySetting,
                    new DiscoveryProxyTargetResolver(discovery, servicePath, scatterId, clusterSetting.getBalance()));
            tcpProxy.start();
            tcpPort = tcpProxy.getPort();
            log.info("ClusterNode TCP 入口启动: {}:{} (scatterId={})", clusterSetting.getHost(), tcpPort, scatterId);
        }

 // ⑤ 注册本节点自身能力（基于 http已启用/tcp已启用 及 服务路径）
        registerSelf();

 // ⑥ 注册显式 添加服务端 声明的远端目标（由 cluster服务端 构建器 传入）
        registerExternalServers();

        this.registeredPaths = resolveServicePaths();
        log.info("ClusterNode 已启动: nodeId={}, scatterId={}, httpPort={}, tcpPort={}",
                selfNodeId, scatterId, httpPort, tcpPort);
    }

    /**
    * 解析实际使用的服务路径列表。
    *
    * @return resolve服务路径的结果
     */
    private List<String> resolveServicePaths() {
        List<String> paths = clusterSetting.getServicePaths();
        if (paths == null || paths.isEmpty()) {
            return List.of("/");
        }
        return paths;
    }

    /** 将本节点自身注册进集群（按 http已启用/tcp已启用 + 服务路径）。 */
    private void registerSelf() {
        List<String> paths = resolveServicePaths();
        for (String path : paths) {
            if (clusterSetting.isHttpEnabled() && httpPort > 0) {
                String serverId = (selfNodeId != null ? selfNodeId : clusterSetting.getHost()) + "-http";
                discovery.registerService(path, Discovery.builder()
                        .serverId(serverId).scatterId(scatterId).protocol("http")
                        .host(clusterSetting.getHost()).port(httpPort).weight(1D).build());
                selfServerIds.add(serverId);
            }
            if (clusterSetting.isTcpEnabled() && tcpPort > 0) {
                String serverId = (selfNodeId != null ? selfNodeId : clusterSetting.getHost()) + "-tcp";
                discovery.registerService(path, Discovery.builder()
                        .serverId(serverId).scatterId(scatterId).protocol("tcp")
                        .host(clusterSetting.getHost()).port(tcpPort).weight(1D).build());
                selfServerIds.add(serverId);
            }
        }
        log.info("ClusterNode 自身已注册: paths={}, serverIds={}", paths, selfServerIds);
    }

    /** 将显式 添加服务端 声明的远端目标注册进集群，供 scatter 扩散。 */
    private void registerExternalServers() {
 // cluster服务端 构建器 会在启动前把 entry 传给 clustersetting
        List<ServerEntry> entries = clusterSetting.getServerEntries();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (ServerEntry entry : entries) {
            entry.validate();
            String proto = entry.normalizedProtocol();
            String path = entry.getServicePath();
            // 只注册与本节点协议匹配的远端服务（http entry → http 路由，tcp entry → tcp 路由）
            boolean matchesHttp = "http".equals(proto) && clusterSetting.isHttpEnabled();
            boolean matchesTcp = "tcp".equals(proto) && clusterSetting.isTcpEnabled();
            if (!matchesHttp && !matchesTcp) {
                log.debug("跳过不匹配的 entry: {} -> {}:{} ({}) httpEnabled={} tcpEnabled={}",
                        path, entry.getHost(), entry.getPort(), proto,
                        clusterSetting.isHttpEnabled(), clusterSetting.isTcpEnabled());
                continue;
            }
            String serverId = entry.getHost() + ":" + entry.getPort();
            discovery.registerService(path, Discovery.builder()
                    .id(serverId).serverId(serverId)
                    .scatterId(entry.getScatterId() != null && !entry.getScatterId().isBlank()
                            ? entry.getScatterId() : scatterId)
                    .protocol(proto)
                    .host(entry.getHost()).port(entry.getPort()).weight(1D).build());
            log.info("ClusterNode 注册远端服务: {} -> {}:{} ({})", path, entry.getHost(), entry.getPort(), proto);
        }
    }

    /**
    * discovery。
    * @return discovery的结果
     */
    public ScatterServiceDiscovery discovery() {
        return discovery;
    }

    /**
    * 获取http端口。
    * @return 获取http端口的结果
     */
    public int getHttpPort() {
        return httpPort;
    }

    /**
    * 获取tcp端口。
    * @return 获取tcp端口的结果
     */
    public int getTcpPort() {
        return tcpPort;
    }

    /**
    * 获取scatter端口。
    * @return 获取scatter端口的结果
     */
    public int getScatterPort() {
        return scatterPort;
    }

    @Override
    public void close() throws Exception {
        if (nodeServer != null) {
            try { nodeServer.close(); } catch (Exception ignored) {}
        }
        if (tcpProxy != null) {
            try { tcpProxy.close(); } catch (Exception ignored) {}
        }
        if (httpServer != null) {
            try { httpServer.close(); } catch (Exception ignored) {}
        }
        try {
            for (String path : registeredPaths) {
                for (String sid : selfServerIds) {
                    discovery.unregisterService(path, sid);
                }
            }
        } catch (Exception ignored) {}
        if (discovery != null) {
            try { discovery.close(); } catch (Exception ignored) {}
        }
    }
}
