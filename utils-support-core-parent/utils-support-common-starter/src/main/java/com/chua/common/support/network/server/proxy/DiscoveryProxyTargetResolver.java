package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.ServiceDiscovery;

import java.net.InetSocketAddress;

/**
 * 基于 {@link ServiceDiscovery} 的 TCP 代理目标解析器。
 *
 * <p>将 TCP 代理的前端连接解析为注册中心中的后端地址:
 * 按服务路径 + scatterId(业务分组) + 协议(tcp) 查询,并走负载均衡选择目标节点,
 * 实现"TCP 代理接入发现服务、自动负载均衡、故障节点自动剔除后不再被路由"。</p>
 *
 * @author CH
 * @since 2026/08/16
 */
public class DiscoveryProxyTargetResolver implements ProxyTargetResolver<InetSocketAddress> {

    /** 服务discovery */
    private final ServiceDiscovery serviceDiscovery;
    /** 服务路径 */
    private final String servicePath;
    /** ScatterID */
    private final String scatterId;
    /** Balance */
    private final String balance;

    /**
     * 创建 DiscoveryProxyTargetResolver 实例
     * @param serviceDiscovery serviceDiscovery
     * @param String String
     * @param servicePath 服务路径，不允许为 null
     */
    public DiscoveryProxyTargetResolver(ServiceDiscovery serviceDiscovery, String servicePath) {
        this(serviceDiscovery, servicePath, null, "weight");
    }

    /**
     * 创建 DiscoveryProxyTargetResolver 实例
     * @param serviceDiscovery serviceDiscovery
     * @param String String
     * @param String String
     * @param servicePath 服务路径，不允许为 null
     * @param scatterId scatterID，不允许为 null
     */
    public DiscoveryProxyTargetResolver(ServiceDiscovery serviceDiscovery, String servicePath, String scatterId) {
        this(serviceDiscovery, servicePath, scatterId, "weight");
    }

    /**
     * 创建 DiscoveryProxyTargetResolver 实例
     * @param serviceDiscovery serviceDiscovery
     * @param servicePath servicePath
     * @param scatterId scatterId
     * @param balance balance
     */
    public DiscoveryProxyTargetResolver(ServiceDiscovery serviceDiscovery, String servicePath,
                                        String scatterId, String balance) {
        this.serviceDiscovery = serviceDiscovery;
        this.servicePath = servicePath;
        this.scatterId = scatterId;
        this.balance = balance;
    }

    @Override
    /** 解析 */
    public InetSocketAddress resolve(InetSocketAddress remote) {
        if (serviceDiscovery == null || servicePath == null) {
            return null;
        }
        // 按服务路径 + scatterId + 协议 tcp 查询,负载均衡选择目标
        Discovery target = serviceDiscovery.getService(servicePath, scatterId, balance, "tcp");
        if (target == null || target.getHost() == null || target.getPort() <= 0) {
            return null;
        }
        return new InetSocketAddress(target.getHost(), target.getPort());
    }
}
