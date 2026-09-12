package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.network.cluster.ClusterNode;
import com.chua.common.support.network.cluster.ClusterServer;
import com.chua.common.support.network.cluster.ClusterSetting;
import com.chua.common.support.network.cluster.ServerEntry;
import com.chua.springboot.support.properties.ClusterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Scatter / Cluster 集群自动配置。
 *
 * <p>当 classpath 中存在 {@code ClusterServer} 且配置属性 {@code chua.cluster.enabled=true}
 *（默认开启）时，自动创建并启动 {@link ClusterServer} Bean。</p>
 *
 * <p>使用方式：在 application.yml 中配置 {@code chua.cluster.*}，Spring Boot 启动后
 * {@link ClusterServer} 即可通过 {@code @Autowired} 注入使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "chua.cluster", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterAutoConfiguration {

    @Autowired
    private ClusterProperties clusterProperties; // cluster属性

    @Autowired
    private org.springframework.core.env.Environment environment; // 环境

    /**
     * 零配置增强：未显式配置时自动从 Spring 环境推导。
     * <ul>
     *   <li>host 未配置(仍为默认 127.0.0.1) → NetUtils 自动探测本机局域网 IP</li>
     *   <li>scatterId 未配置(仍为 "default") → 取 spring.application.name</li>
     *   <li>nodeId 未配置 → ip:port 保证同机多实例唯一</li>
     * </ul>
     */
    private void autoDetect() {
        // 1. host: Spring server.address > chua.cluster.host > NetUtils 探测
        String springHost = environment.getProperty("server.address");
        if ("127.0.0.1".equals(clusterProperties.getHost()) && springHost != null && !springHost.isBlank()) {
            clusterProperties.setHost(springHost);
        }
        if ("127.0.0.1".equals(clusterProperties.getHost())) {
            clusterProperties.setHost(
                    com.chua.common.support.network.net.NetUtils.getLocalHost());
        }

        // 2. scatterId: spring.application.name > chua.cluster.scatter-id
        if ("default".equals(clusterProperties.getScatterId())) {
            String appName = environment.getProperty("spring.application.name");
            if (appName != null && !appName.isBlank()) {
                clusterProperties.setScatterId(appName);
            }
        }

 // 3. 节点标识: ip:端口（保证同机多实例唯一）
        if (clusterProperties.getNodeId() == null || clusterProperties.getNodeId().isBlank()) {
            String rawId = clusterProperties.getHost() + ":" + clusterProperties.getPort();
            clusterProperties.setNodeId(
                    com.chua.common.support.utils.DigestUtils.md5Hex(rawId).substring(0, 12));
        }
    }

    @Bean
    @ConditionalOnMissingBean
    /**
     * cluster服务端。
     * @return cluster服务端的结果
     */
    public ClusterServer clusterServer() throws Exception {
        autoDetect();
        ClusterProperties props = clusterProperties;

 // 构建 clustersetting
        ClusterSetting setting = new ClusterSetting();
        setting.setNodeId(props.getNodeId());
        setting.setHost(props.getHost());
        setting.setScatterId(props.getScatterId());
        setting.setClusterId(props.getClusterId());
        setting.setSeeds(props.getSeeds());
        setting.setServicePaths(props.getServicePaths());
        setting.setPort(props.getPort());
        setting.setScatterPort(props.getScatterPort());
        setting.setHttpEnabled(props.isHttpEnabled());
        setting.setTcpEnabled(props.isTcpEnabled());
        setting.setBalance(props.getBalance());
        setting.setTimeoutMillis(props.getTimeoutMillis());
        setting.setAutoDiscoveryIntervalMillis(props.getAutoDiscoveryIntervalMillis());

 // 转换 服务端entries
        List<ServerEntry> entries = props.getServerEntries().stream()
                .map(e -> new ServerEntry(
                        e.getServicePath(),
                        e.getHost(),
                        e.getPort(),
                        e.getProtocol(),
                        e.getScatterId()))
                .collect(Collectors.toList());
        setting.setServerEntries(entries);

 // 构建 cluster服务端（未启动，由 application上下文 管理生命周期）
        ClusterServer server = ClusterServer.builder()
                .nodeId(props.getNodeId())
                .host(props.getHost())
                .port(props.getPort())
                .scatterId(props.getScatterId())
                .clusterId(props.getClusterId())
                .seeds(props.getSeeds().toArray(new String[0]))
                .servicePaths(props.getServicePaths())
                .httpEnabled(props.isHttpEnabled())
                .tcpEnabled(props.isTcpEnabled())
                .balance(props.getBalance())
                .timeoutMillis(props.getTimeoutMillis())
                .build();

        log.info("ClusterServer Bean 创建完成: scatterId={}, entries={}",
                props.getScatterId(), entries.size());
        return server;
    }
}
