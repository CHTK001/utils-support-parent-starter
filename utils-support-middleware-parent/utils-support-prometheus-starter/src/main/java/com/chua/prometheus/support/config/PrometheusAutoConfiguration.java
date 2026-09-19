package com.chua.prometheus.support.config;

import com.chua.prometheus.support.client.PrometheusClient;
import com.chua.prometheus.support.engine.PrometheusEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Prometheus 自动配置
 * <p>
 * 默认注册 {@link PrometheusClient} 与 {@link PrometheusEngine},
 * 可通过 {@code prometheus.enabled=false} 关闭。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(PrometheusProperties.class)
@ConditionalOnProperty(prefix = "prometheus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PrometheusAutoConfiguration {

    /**
     * 创建默认 prometheus客户端
     *
     * @param properties 配置
     * @return 客户端
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PrometheusClient prometheusClient(PrometheusProperties properties) {
        PrometheusClient.Builder builder = PrometheusClient.builder()
                .baseUrl(properties.getUrl())
                .timeoutMs(properties.getTimeoutMs());
        if (properties.getUsername() != null && !properties.getUsername().isEmpty()) {
            builder.basicAuth(properties.getUsername(), properties.getPassword());
        }
        PrometheusClient client = builder.build();
        log.info("[Prometheus] 客户端初始化: {}", properties.getUrl());
        return client;
    }

    /**
     * 创建默认 prometheusengine
     *
     * @param client 默认客户端
     * @return 引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public PrometheusEngine prometheusEngine(PrometheusClient client) {
        PrometheusEngine engine = new PrometheusEngine();
        engine.addDataSource("default", client);
        return engine;
    }
}
