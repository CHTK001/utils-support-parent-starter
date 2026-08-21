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
    private ClusterProperties clusterProperties;

    @Bean
    @ConditionalOnMissingBean
    public ClusterServer clusterServer() throws Exception {
        ClusterProperties props = clusterProperties;

        // 构建 ClusterSetting
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

        // 转换 serverEntries
        List<ServerEntry> entries = props.getServerEntries().stream()
                .map(e -> new ServerEntry(
                        e.getServicePath(),
                        e.getHost(),
                        e.getPort(),
                        e.getProtocol(),
                        e.getScatterId()))
                .collect(Collectors.toList());
        setting.setServerEntries(entries);

        // 构建 ClusterServer（未启动，由 ApplicationContext 管理生命周期）
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
