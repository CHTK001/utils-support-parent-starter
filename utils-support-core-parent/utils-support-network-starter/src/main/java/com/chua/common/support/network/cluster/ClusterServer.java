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

    private final ClusterSetting setting;
    private final ClusterNode node;
    private final ClusterManager manager;

    private ClusterServer(ClusterSetting setting, ClusterNode node, ClusterManager manager) {
        this.setting = setting;
        this.node = node;
        this.manager = manager;
    }

    /** 开始构建集群服务器。 */
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

    /** 集群管理器（路由/视图/故障退避；路由排除本节点）。 */
    public ClusterManager manager() {
        return manager;
    }

    /** 暴露服务发现（注册/查询集群服务）。 */
    public com.chua.common.support.scatter.ScatterServiceDiscovery discovery() {
        return node.discovery();
    }

    public int getHttpPort() {
        return node.getHttpPort();
    }

    public int getTcpPort() {
        return node.getTcpPort();
    }

    @Override
    public void close() throws Exception {
        node.close();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static class Builder {

        private final ClusterSetting setting = new ClusterSetting();
        private final List<ServerEntry> entries = new ArrayList<>();

        private Builder() {
        }

        /** 节点 ID */
        public Builder nodeId(String nodeId) {
            setting.setNodeId(nodeId);
            return this;
        }

        /** 本机 host */
        public Builder host(String host) {
            setting.setHost(host);
            return this;
        }

        /** 业务端口 */
        public Builder port(int port) {
            setting.setPort(port);
            return this;
        }

        /** 业务分组标识 */
        public Builder scatterId(String scatterId) {
            setting.setScatterId(scatterId);
            return this;
        }

        /** 集群标识（为空回落 scatterId） */
        public Builder clusterId(String clusterId) {
            setting.setClusterId(clusterId);
            return this;
        }

        /** 种子节点地址（host:port） */
        public Builder seeds(String... seeds) {
            setting.setSeeds(java.util.Arrays.asList(seeds));
            return this;
        }

        /** 本节点服务路径 */
        public Builder servicePaths(List<String> paths) {
            setting.setServicePaths(paths);
            return this;
        }

        /** 是否启用 HTTP 入口（默认 true） */
        public Builder httpEnabled(boolean enabled) {
            setting.setHttpEnabled(enabled);
            return this;
        }

        /** 是否启用 TCP 入口（默认 true） */
        public Builder tcpEnabled(boolean enabled) {
            setting.setTcpEnabled(enabled);
            return this;
        }

        /** 负载均衡策略（weight/round/random） */
        public Builder balance(String balance) {
            setting.setBalance(balance);
            return this;
        }

        /** 请求超时毫秒 */
        public Builder timeoutMillis(long millis) {
            setting.setTimeoutMillis(millis);
            return this;
        }

        /**
         * 添加一台远端服务（path/host/port/protocol，protocol 不限制，混用同 path 允许）。
         *
         * @param servicePath 服务路径，如 "/api"
         * @param host        目标主机
         * @param port        目标端口
         * @param protocol    协议：http / tcp / udp
         */
        public Builder addServer(String servicePath, String host, int port, String protocol) {
            entries.add(new ServerEntry(servicePath, host, port, protocol, null));
            return this;
        }

        /**
         * 默认 http 协议的便捷重载。
         */
        public Builder addServer(String servicePath, String host, int port) {
            return addServer(servicePath, host, port, "http");
        }

        /**
         * 设置集群 master 节点（域名/主入口），用于网关模式下的流量入口标记。
         * 仅记录元数据，不影响 scatter 对等发现逻辑。
         */
        public Builder master(String master) {
            setting.setMaster(master);
            return this;
        }

        /**
         * 构建集群服务器实例（未启动）。
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
