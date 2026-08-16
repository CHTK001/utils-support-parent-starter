package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.DiscoveryProxyTargetResolver;
import com.chua.common.support.network.server.proxy.TcpProxyServer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Scatter 节点 = TCP 代理服务器 + 服务发现(hash 表)。
 *
 * <p>一个端口同时承载两类能力:</p>
 * <ol>
 *   <li><b>业务分发</b>:http/tcp 请求打到本节点端口,由内嵌 {@link TcpProxyServer} 按
 *       discovery(服务发现/hash 表)解析目标节点并转发——即"节点自身就是 TCP 代理服务器"。</li>
 *   <li><b>服务发现</b>:组合 {@link ScatterGatherServiceDiscovery},节点间交换身份 hash,
 *       维护节点表,提供 {@code getService} 路由能力。</li>
 * </ol>
 *
 * @author CH
 * @since 2026/08/16
 */
@Slf4j
public class ScatterGatherProxyNode implements AutoCloseable {

    private final ScatterGatherServiceDiscovery discovery;
    private final TcpProxyServer proxy;

    public ScatterGatherProxyNode(ScatterGatherSetting setting) throws Exception {
        this(setting, null);
    }

    /**
     * 构造 scatter 代理节点。
     *
     * @param setting      scatter 配置(host/port/seeds/协议等)
     * @param servicePaths 本节点对外提供的服务路径(用于注册到 discovery),可为 null
     */
    public ScatterGatherProxyNode(ScatterGatherSetting setting, List<String> servicePaths) throws Exception {
        // ① discovery:hash 交换 + 节点表(无中心化自动发现)
        com.chua.common.support.network.discovery.DiscoveryOption option = new com.chua.common.support.network.discovery.DiscoveryOption();
        option.setAddress(String.join(",", setting.getSeedAddresses()));
        this.discovery = new ScatterGatherServiceDiscovery(option, setting);
        this.discovery.start();

        // ② 注册本节点服务(供集群内其他节点发现)
        if (servicePaths != null) {
            for (String path : servicePaths) {
                Discovery self = Discovery.builder()
                        .serverId(setting.getNodeId())
                        .scatterId("default")
                        .protocol("tcp")
                        .host(setting.getHost())
                        .port(setting.getTcpPort())
                        .weight(1)
                        .build();
                this.discovery.registerService(path, self);
            }
        }

        // ③ TCP 代理:按 discovery(scatterId + tcp 协议)解析目标节点并转发
        ServerSetting proxySetting = ServerSetting.defaults();
        proxySetting.setHost(setting.getHost());
        proxySetting.setPort(setting.getTcpPort());
        // 每个服务路径挂一个 resolver;默认解析第一条
        String servicePath = servicePaths == null || servicePaths.isEmpty() ? "/" : servicePaths.get(0);
        this.proxy = new TcpProxyServer(proxySetting,
                new DiscoveryProxyTargetResolver(this.discovery, servicePath, "default", "weight"));
    }

    /**
     * 启动节点(同时启动 discovery 与 TCP 代理,共用本节点端口)。
     */
    public void start() throws Exception {
        proxy.start();
        log.info("ScatterGatherProxyNode started on {}:{} (discovery + tcp-proxy 共用端口)",
                proxy.getSetting().getHost(), proxy.getPort());
    }

    /**
     * 获取服务发现(hash 表)能力,供路由/查询使用。
     *
     * @return 服务发现实例
     */
    public ScatterGatherServiceDiscovery discovery() {
        return discovery;
    }

    public int getPort() {
        return proxy.getPort();
    }

    @Override
    public void close() throws Exception {
        if (proxy != null) {
            try {
                proxy.close();
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
