package com.chua.common.support.network.cluster;

import lombok.extern.slf4j.Slf4j;

/**
 * 集群门面:一行启动无中心化集群节点。
 *
 * <pre>
 * ClusterSetting setting = new ClusterSetting();
 * setting.setHost("127.0.0.1");
 * setting.setPort(0);
 * setting.setScatterId("order");
 * setting.setSeeds(List.of("127.0.0.1:19001", "127.0.0.1:19002"));
 * setting.setServicePaths(List.of("/api"));
 *
 * try (ClusterServer server = ClusterServer.create(setting)) {
 *     server.start();   // 加入集群:自动发现/注册/HTTP+TCP 代理入口
 * }
 * </pre>
 *
 * @author CH
 * @since 2026/08/16
 */
@Slf4j
public class ClusterServer implements AutoCloseable {

    /** Cluster设置 */
    private final ClusterSetting clusterSetting;
    /** 节点 */
    private final ClusterNode node;

    /**
     * 创建 ClusterServer 实例
     * @param clusterSetting clusterSetting
     */
    private ClusterServer(ClusterSetting clusterSetting) throws Exception {
        this.clusterSetting = clusterSetting;
        this.node = new ClusterNode(clusterSetting);
    }

    /**
     * 创建集群节点(含服务发现初始化,未启动业务入口)。
     *
     * @param clusterSetting 集群配置
     * @return 集群门面
     */
    public static ClusterServer create(ClusterSetting clusterSetting) throws Exception {
        return new ClusterServer(clusterSetting);
    }

    /**
     * 启动节点:启动 HTTP/TCP 代理入口并注册服务到集群。
     */
    public void start() throws Exception {
        node.start();
        log.info("ClusterServer 已加入集群: node={}, scatterId={}, httpPort={}, tcpPort={}",
                clusterSetting.getNodeId(), clusterSetting.getScatterId(),
                node.getHttpPort(), node.getTcpPort());
    }

    /**
     * 集群管理器(路由/视图/故障退避;路由排除本节点)。
     */
    public ClusterManager manager() {
        return new ClusterManager(node.discovery(), clusterSetting.getBalance(), clusterSetting.getNodeId());
    }

    /**
     * 暴露服务发现(注册/查询集群服务)。
     */
    public com.chua.common.support.scatter.ScatterServiceDiscovery discovery() {
        return node.discovery();
    }

    /** 获取HttpPort */
    public int getHttpPort() {
        return node.getHttpPort();
    }

    /** 获取TcpPort */
    public int getTcpPort() {
        return node.getTcpPort();
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        node.close();
    }
}
