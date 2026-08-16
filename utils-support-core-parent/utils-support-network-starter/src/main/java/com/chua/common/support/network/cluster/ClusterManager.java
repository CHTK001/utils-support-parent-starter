package com.chua.common.support.network.cluster;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.server.proxy.DiscoveryProxyTargetResolver;
import com.chua.common.support.scattergather.ScatterGatherServiceDiscovery;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

/**
 * 集群管理器:集群视图 + 路由决策 + 故障退避。
 *
 * <p>基于服务发现(节点 hash 交换后的节点表)提供:</p>
 * <ul>
 *   <li><b>集群视图</b>:查询指定服务路径/业务分组的全部节点。</li>
 *   <li><b>路由</b>:按 path + scatterId + 协议 经负载均衡选目标节点。</li>
 *   <li><b>故障退避</b>:目标解析失败/节点失效时,依赖 discovery 的剔除与重试
 *       (getService 不再返回失效节点),并提供失败重试的解析器。</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/16
 */
@Slf4j
public class ClusterManager {

    private final ScatterGatherServiceDiscovery discovery;
    private final String balance;

    public ClusterManager(ScatterGatherServiceDiscovery discovery, String balance) {
        this.discovery = discovery;
        this.balance = balance == null || balance.isBlank() ? "weight" : balance;
    }

    /**
     * 集群视图:获取指定服务路径下全部节点。
     *
     * @param servicePath 服务路径
     * @return 节点集合
     */
    public Set<Discovery> nodes(String servicePath) {
        return discovery.getServiceAll(servicePath);
    }

    /**
     * 按业务分组获取节点视图。
     *
     * @param servicePath 服务路径
     * @param scatterId   业务分组
     * @param protocol    协议(http/tcp)
     * @return 同分组节点集合
     */
    public Set<Discovery> nodes(String servicePath, String scatterId, String protocol) {
        return discovery.getServiceAll(servicePath).stream()
                .filter(d -> scatterId == null || scatterId.equals(d.getScatterId()))
                .filter(d -> protocol == null || protocol.equalsIgnoreCase(d.getProtocol()))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 路由:选一个目标节点(负载均衡)。
     *
     * @param servicePath 服务路径
     * @param scatterId   业务分组
     * @param protocol    协议(http/tcp)
     * @return 目标节点,无可用节点返回 null
     */
    public Discovery route(String servicePath, String scatterId, String protocol) {
        return discovery.getService(servicePath, scatterId, balance, protocol);
    }

    /**
     * 构建故障退避的 TCP 代理目标解析器:每次解析都实时查 discovery,
     * 失效节点被剔除后自动不再被选中。
     *
     * @param servicePath 服务路径
     * @param scatterId   业务分组
     * @return 目标解析器
     */
    public DiscoveryProxyTargetResolver tcpResolver(String servicePath, String scatterId) {
        return new DiscoveryProxyTargetResolver(discovery, servicePath, scatterId, balance);
    }

    /**
     * 可用节点数(健康度指标)。
     *
     * @param servicePath 服务路径
     * @param scatterId   业务分组
     * @param protocol    协议
     * @return 节点数
     */
    public int healthyCount(String servicePath, String scatterId, String protocol) {
        return nodes(servicePath, scatterId, protocol).size();
    }

    /**
     * 检查指定节点是否可路由(故障退避辅助)。
     *
     * @param servicePath 服务路径
     * @param scatterId   业务分组
     * @param target      目标节点
     * @return true=仍在集群视图中(健康)
     */
    public boolean isHealthy(String servicePath, String scatterId, Discovery target) {
        if (target == null) {
            return false;
        }
        for (Discovery d : nodes(servicePath, scatterId, target.getProtocol())) {
            if (d.getServerId().equals(target.getServerId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 便捷:获取集群节点列表(全部服务路径去重)。
     *
     * @return 节点列表
     */
    public List<Discovery> allNodes() {
        return discovery.getServiceAll("/").stream().toList();
    }
}
