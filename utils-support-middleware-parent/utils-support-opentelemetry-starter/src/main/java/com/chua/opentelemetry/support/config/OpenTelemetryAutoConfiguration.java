// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.config;

import com.chua.opentelemetry.support.client.OpenTelemetryClient;
import com.chua.opentelemetry.support.engine.OpenTelemetryEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * OpenTelemetry 自动配置
 * <p>
 * 默认注册 {@link OpenTelemetryClient} 与 {@link OpenTelemetryEngine},
 * 可通过 {@code opentelemetry.enabled=false} 关闭。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(OpenTelemetryProperties.class)
@ConditionalOnProperty(prefix = "opentelemetry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenTelemetryAutoConfiguration {

    /**
     * 创建默认 OpenTelemetry 客户端
     * <p>未指定 {@code opentelemetry.url} 时, 使用内存导出器缓存 Span 并开放 {@link OpenTelemetryClient#traces()} 查询。</p>
     *
     * @param properties 配置
     * @return 客户端
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public OpenTelemetryClient openTelemetryClient(OpenTelemetryProperties properties) {
        OpenTelemetryClient client = OpenTelemetryClient.builder()
                .serviceName(properties.getServiceName())
                .sampler(OpenTelemetrySampler.of(properties.getSampler(), properties.getRatio()))
                .build();
        log.info("[OpenTelemetry] 客户端初始化: {} (采样器 {}, 采样比 {})",
                properties.getServiceName(), properties.getSampler(), properties.getRatio());
        return client;
    }

    /**
     * 创建默认 OpenTelemetry 引擎
     * <p>显式声明销毁回调, 容器关闭时释放引擎登记的数据源与其底层 SDK 工作线程。</p>
     *
     * @param client 默认客户端
     * @return 引擎
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public OpenTelemetryEngine openTelemetryEngine(OpenTelemetryClient client) {
        OpenTelemetryEngine engine = new OpenTelemetryEngine();
        engine.addDataSource("default", client);
        return engine;
    }
}
