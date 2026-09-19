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
     * <p>配置非法(地址为空、超时不大于 0)时在启动阶段直接抛出, 不延后到首次查询。</p>
     *
     * @param properties 配置
     * @return 客户端
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PrometheusClient prometheusClient(PrometheusProperties properties) {
        String url = properties.getUrl();
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalStateException("配置项 prometheus.url 不能为空");
        }
        PrometheusClient.Builder builder = PrometheusClient.builder()
                .baseUrl(url)
                .timeoutMs(properties.getTimeoutMs());
        if (properties.getUsername() != null && !properties.getUsername().isEmpty()) {
            builder.basicAuth(properties.getUsername(), properties.getPassword());
        }
        PrometheusClient client = builder.build();
        log.info("[Prometheus] 客户端初始化: {} (超时 {} ms)", url, properties.getTimeoutMs());
        return client;
    }

    /**
     * 创建默认 prometheusengine
     * <p>显式声明销毁回调, 容器关闭时释放引擎登记的数据源与其底层 HTTP 客户端线程。</p>
     *
     * @param client 默认客户端
     * @return 引擎
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PrometheusEngine prometheusEngine(PrometheusClient client) {
        PrometheusEngine engine = new PrometheusEngine();
        engine.addDataSource("default", client);
        return engine;
    }
}
