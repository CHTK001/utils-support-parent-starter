package com.chua.common.support.network.cluster;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
* 集群门面：一行启动无中心化集群节点。
*
* <pre>
* try (ClusterServer server = ClusterServer.builder()
*         .scatterId("order")
*         .seeds("127.0.0.1:19001", "127.0.0.1:19002")
*         .addServer("/api", "192.168.1.10", 8080, "http")
*         .addServer("/pay", "10.0.0.5", 9001, "tcp")
*         .build()) {
*     server.start();   // 加入集群：自动发现/注册/HTTP+TCP 代理入口
* }
* </pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class ClusterServer implements AutoCloseable {

    private final ClusterSetting setting; // setting
    private final ClusterNode node; // 节点
    private final ClusterManager manager; // 管理器

    /**
    * cluster服务端。
    * @param setting setting
    * @param node 节点
    * @param manager 管理器
    */
    private ClusterServer(ClusterSetting setting, ClusterNode node, ClusterManager manager) {
        this.setting = setting;
        this.node = node;
        this.manager = manager;
    }

    /**
    * 开始构建集群服务器。
    *
    * @return 构建器的结果
    */
    public static Builder builder() {
        return new Builder();
    }

    /**
    * 启动节点：启动 HTTP/TCP 代理入口并注册服务到集群。
    */
    public void start() throws Exception {
        node.start();
        log.info("ClusterServer 已加入集群: nodeId={}, scatterId={}, httpPort={}, tcpPort={}",
                setting.getNodeId(), setting.getScatterId(),
                node.getHttpPort(), node.getTcpPort());
    }

    /**
    * 集群管理器（路由/视图/故障退避；路由排除本节点）。
    *
    * @return 管理器的结果
    */
    public ClusterManager manager() {
        return manager;
    }

    /**
    * 暴露服务发现能力（注册/查询集群服务）。
    * 返回本节点持有的 Scatter 无中心化服务发现实例，
    * 用于向集群注册本节点服务以及查询远端节点服务。
    *
    * @return 散列服务发现实例
    */
    public com.chua.common.support.scatter.ScatterServiceDiscovery discovery() {
        return node.discovery();
    }

    public int getHttpPort() {
        return node.getHttpPort();
    }

    public int getTcpPort() {
        return node.getTcpPort();
    }

    public int getScatterPort() {
        return node.getScatterPort();
    }

    @Override
    public void close() throws Exception {
        node.close();
    }

    // -------------------------------------------------------------------------
 // 构建器
    // -------------------------------------------------------------------------
    /**
    * 构建器类。
    *
    * @author CH
    * @since 4.0.0
    */

    public static class Builder {

        private final ClusterSetting setting = new ClusterSetting(); // setting
        private final List<ServerEntry> entries = new ArrayList<>(); // entries

        /**
        * 构建器。
        */
        private Builder() {
        }

        /**
        * 节点 标识
        *
        * @param nodeId 节点标识
        * @return 节点id的结果
        */
        public Builder nodeId(String nodeId) {
            setting.setNodeId(nodeId);
            return this;
        }

        /**
        * 本机 主机
        *
        * @param host 主机
        * @return 主机的结果
        */
        public Builder host(String host) {
            setting.setHost(host);
            return this;
        }

        /**
        * 业务端口
        *
        * @param port 端口
        * @return 端口的结果
        */
        public Builder port(int port) {
            setting.setPort(port);
            return this;
        }

        /**
        * 业务分组标识
        *
        * @param scatterId scatterid
        * @return scatterId的结果
        */
        public Builder scatterId(String scatterId) {
            setting.setScatterId(scatterId);
            return this;
        }

        /**
        * 集群标识（为空回落 scatterid）
        *
        * @param clusterId clusterid
        * @return clusterId的结果
        */
        public Builder clusterId(String clusterId) {
            setting.setClusterId(clusterId);
            return this;
        }

        /**
        * 种子节点地址（主机:端口）
        *
        * @param seeds seeds
        * @return seeds的结果
        */
        public Builder seeds(String... seeds) {
            setting.setSeeds(java.util.Arrays.asList(seeds));
            return this;
        }

        /**
        * 本节点服务路径
        *
        * @param paths 路径
        * @return 服务路径的结果
        */
        public Builder servicePaths(List<String> paths) {
            setting.setServicePaths(paths);
            return this;
        }

        /**
        * 是否启用 HTTP 入口（默认 true）
        *
        * @param enabled 已启用
        * @return http已启用的结果
        */
        public Builder httpEnabled(boolean enabled) {
            setting.setHttpEnabled(enabled);
            return this;
        }

        /**
        * 是否启用 TCP 入口（默认 true）
        *
        * @param enabled 已启用
        * @return tcp已启用的结果
        */
        public Builder tcpEnabled(boolean enabled) {
            setting.setTcpEnabled(enabled);
            return this;
        }

        /**
        * 负载均衡策略（权重/round/随机）
        *
        * @param balance balance
        * @return balance的结果
        */
        public Builder balance(String balance) {
            setting.setBalance(balance);
            return this;
        }

        /**
        * 请求超时毫秒
        *
        * @param millis millis
        * @return 超时millis的结果
        */
        public Builder timeoutMillis(long millis) {
            setting.setTimeoutMillis(millis);
            return this;
        }

        /**
        * 添加一台远端服务（路径/主机/端口/协议，协议 不限制，混用同 路径 允许）。
        *
        * @param servicePath 服务路径，如 "/api"
        * @param host        目标主机
        * @param port        目标端口
        * @param protocol    协议：http / tcp / udp
        * @return 添加服务端的结果
        */
        public Builder addServer(String servicePath, String host, int port, String protocol) {
            entries.add(new ServerEntry(servicePath, host, port, protocol, null));
            return this;
        }

        /**
        * 默认 http 协议的便捷重载。
        * @param servicePath 服务路径
        * @param host 主机
        * @param port 端口
        * @return 添加服务端的结果
        */
        public Builder addServer(String servicePath, String host, int port) {
            return addServer(servicePath, host, port, "http");
        }

        /** 设置集群 master 节点（域名/主入口，用于标识集群中的引导节点）。
        * @param master master
        * @return master的结果
        * 仅作为元数据记录，不影响 scatter 对等发现逻辑。 */
        public Builder master(String master) {
            setting.setMaster(master);
            return this;
        }

        /**
        * 构建集群服务器实例（未启动）。
        * @return 构建的结果
        */
        public ClusterServer build() throws Exception {
            setting.setServerEntries(new ArrayList<>(entries));
            ClusterNode node = new ClusterNode(setting);
            ClusterManager mgr = new ClusterManager(node.discovery(),
                    setting.getBalance(), setting.getNodeId());
            return new ClusterServer(setting, node, mgr);
        }
    }
}
