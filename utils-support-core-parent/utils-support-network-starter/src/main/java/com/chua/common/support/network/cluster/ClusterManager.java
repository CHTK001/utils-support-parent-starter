package com.chua.common.support.network.cluster;

import com.chua.common.support.network.cluster.ServerEntry;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.scatter.DefaultScatterServiceDiscovery;
import com.chua.common.support.scatter.ScatterServiceDiscovery;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.scatter.TcpScatterBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 集群管理器：集群视图 + 路由决策 + 故障退避。
 *
 * <p>基于 Scatter 无中心化服务发现网格，提供：</p>
 * <ul>
 *   <li><b>集群视图</b>：按 path 查询全部已知节点。</li>
 *   <li><b>路由</b>：按 path + scatterId + protocol 经负载均衡选目标节点。</li>
 *   <li><b>故障退避</b>：失效节点被剔除后自动不再被选中。</li>
 * </ul>
 *
 * <p><b>服务注册规范</b>：</p>
 * <ul>
 *   <li>必须通过 {@link #addServer(ServerEntry)} 显式注册要代理的服务（含本节点自身）。</li>
 *   <li>注册后由 Scatter 自动扩散，无需手动维护节点表。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ClusterManager {

    /** Discovery */
    private final ScatterServiceDiscovery discovery;
    /** Balance */
    private final String balance;
    /** Self 节点 标识 */
    private final String selfNodeId;
    /** 分组 标识 */
    private final String groupId;
    /**
     * 已注册的 服务端entry（含本节点与远端目标）
     *
     /**
      * cluster管理器。
      * @param discovery discovery
      * @param balance balance
      */
     * @param discovery discovery
     * @param balance balance
     * @param selfNodeId self节点标识
     */
    private final List<ServerEntry> entries = new ArrayList<>();

    /**
     * cluster管理器。
     * @param discovery discovery
     * @param balance balance
     * @param selfNodeId self节点id
     /**
      * cluster管理器。
      * @param discovery discovery
      * @param balance balance
      */
     */
    public ClusterManager(ScatterServiceDiscovery discovery, String balance) {
        this(discovery, balance, null);
    }

    public ClusterManager(ScatterServiceDiscovery discovery, String balance, String selfNodeId) {
        this.discovery = discovery;
        this.balance = balance == null || balance.isBlank() ? "weight" : balance;
        this.selfNodeId = selfNodeId;
        this.groupId = discovery.getGroupId() == null ? "default" : discovery.getGroupId();
    }

    /**
     * 注册一台服务器进集群。
     *
     * @param entry 服务元数据
     * @return 当前实例（链式调用）
     */
    public ClusterManager addServer(ServerEntry entry) {
        if (entry == null) {
            return this;
        }
        entry.validate();
        entries.add(entry);
        Discovery disco = toDiscovery(entry);
        discovery.registerService(entry.getServicePath(), disco);
        log.info("addServer: {} -> {}:{} ({}) 加入集群 {}", entry.getServicePath(), entry.getHost(),
                entry.getPort(), entry.normalizedProtocol(), groupId);
        return this;
    }

    /**
     * 批量注册多台服务器。
     *
     * @param entries 服务元数据列表
     * @return 当前实例
     */
    public ClusterManager addServers(List<ServerEntry> entries) {
        if (entries == null) {
            return this;
        }
        for (ServerEntry entry : entries) {
            addServer(entry);
        }
        return this;
    }

    /**
     * 获取指定服务路径下的全部节点。
     * @param servicePath 服务路径
     * @return 节点的结果
     */
    public Set<Discovery> nodes(String servicePath) {
        return discovery.getServiceAll(servicePath);
    }

    /**
     * 按业务分组和协议过滤节点。
     * @param servicePath 服务路径
     * @param scatterId scatterid
     * @param protocol 协议
     * @return 节点的结果
     */
    public Set<Discovery> nodes(String servicePath, String scatterId, String protocol) {
        return discovery.getServiceAll(servicePath).stream()
                .filter(d -> scatterId == null || scatterId.equals(d.getScatterId()))
                .filter(d -> protocol == null || protocol.equalsIgnoreCase(d.getProtocol()))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 路由：选一个目标节点（负载均衡），排除本节点避免死循环。
     *
     * @param servicePath 服务路径
     * @param scatterId   业务分组
     * @param protocol    协议（http/tcp）
     * @return 目标节点，无可用节点返回 空
     */
    public Discovery route(String servicePath, String scatterId, String protocol) {
        if (selfNodeId == null || selfNodeId.isBlank()) {
            return discovery.getService(servicePath, scatterId, balance, protocol);
        }
        Discovery target = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Discovery d = discovery.getService(servicePath, scatterId, balance, protocol);
            if (d == null) {
                return null;
            }
 // 排除以本 节点标识 为前缀的 服务端标识（形如 节点标识-http / 节点标识-tcp）
            if (!d.getServerId().startsWith(selfNodeId + "-")) {
                return d;
            }
            target = d;
        }
        return target;
    }

    /**
     * 构建故障退避的 TCP 代理目标解析器。
     */
    public com.chua.common.support.network.server.proxy.DiscoveryProxyTargetResolver
            tcpResolver(String servicePath, String scatterId) {
        return new com.chua.common.support.network.server.proxy.DiscoveryProxyTargetResolver(
                discovery, servicePath, scatterId, balance);
    }

    /**
     * 可用节点数。
     * @param servicePath 服务路径
     * @param scatterId scatterid
     * @param protocol 协议
     * @return healthy数量的结果
     */
    public int healthyCount(String servicePath, String scatterId, String protocol) {
        return nodes(servicePath, scatterId, protocol).size();
    }

    /**
     * 检查节点是否健康（仍在集群视图中）。
     * @param servicePath 服务路径
     * @param scatterId scatterid
     * @param target Target
     * @return 是否healthy的结果
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
     * 获取已注册的所有服务元数据。
     * @return 获取entries的结果
     */
    public List<ServerEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * 便捷：获取集群全部节点列表。
     * @return 全部节点的结果
     */
    public List<Discovery> allNodes() {
        return discovery.getServiceAll("/").stream().toList();
    }

    /**
     * 服务端entry → Discovery 转换。
     *
     * @param entry entry
     * @return 转为discovery的结果
     */
    private Discovery toDiscovery(ServerEntry entry) {
        String proto = entry.normalizedProtocol();
        String serverId = entry.getHost() + ":" + entry.getPort();
        return Discovery.builder()
                .id(serverId)
                .serverId(serverId)
                .scatterId(entry.getScatterId() != null && !entry.getScatterId().isBlank()
                        ? entry.getScatterId() : groupId)
                .protocol(proto)
                .host(entry.getHost())
                .port(entry.getPort())
                .weight(1D)
                .build();
    }
}
