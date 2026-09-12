package com.chua.metrics.support;

import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
* 指标 自动配置类。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsAutoConfiguration {

    /**
    * 创建 指标服务 实例。
    *
    * @param properties 配置属性
    * @return MetricsService 实例
     */
    @Bean(destroyMethod = "close")
    public MetricsService metricsService(MetricsProperties properties) {
        if (!properties.isEnabled()) {
            log.info("Metrics 模块已禁用");
            return null;
        }

        long intervalMs = properties.getIntervalMs();
        if (intervalMs <= 0) {
            intervalMs = 1000L;
        }

        MetricsService service = new MetricsService(intervalMs);

        Set<WatcherEvent> events = new HashSet<>();
        events.add(WatcherEvent.ALL_KIND);

        DirectoryPollerEnvironment environment = new DirectoryPollerEnvironment(
                events,
                intervalMs / 2,
                TimeUnit.MILLISECONDS
        );

        service.start(environment);
        log.info("Metrics 模块已启动，采样间隔={}ms", intervalMs);

        return service;
    }
}