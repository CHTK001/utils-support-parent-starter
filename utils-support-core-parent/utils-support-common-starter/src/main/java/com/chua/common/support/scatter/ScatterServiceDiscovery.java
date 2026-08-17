package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.ServiceDiscovery;

/**
 * Scatter 服务发现接口。
 * <p>对等式无中心化发现：节点通过 seeds 引导 / 网段扩散 / gossip 互相发现，
 * 内存(Inmem)维护节点表，按 groupId 业务分组隔离，心跳上报动态权重。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterServiceDiscovery extends ServiceDiscovery {

    /**
     * 设置远程客户端（用于向其他节点拉取服务列表）。
     *
     * @param remoteClient 远程客户端
     * @return 当前实例
     */
    ScatterServiceDiscovery remoteClient(ScatterRemoteClient<Discovery> remoteClient);

    /**
     * 获取业务分组。
     *
     * @return 分组标识
     */
    String getGroupId();

    /**
     * 获取节点配置。
     *
     * @return 配置
     */
    ScatterSetting getSetting();
}
