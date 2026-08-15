package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.rate.RateLimiterProvider;
import com.chua.common.support.concurrent.rate.provider.GuavaRateLimiterProvider;
import com.chua.spring.support.aop.RateLimiterAdvisor;
import com.chua.spring.support.proxy.intercept.RateLimiterIntercept;
import com.chua.springboot.support.properties.RateLimiterProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 限流自动配置。
 *
 * <p>注入可配置的 Guava {@link RateLimiterProvider}、拦截器和 AOP Advisor。
 * QPS 与预热时间可通过 {@code plugin.rate-limiter.*} 配置。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
@AutoConfiguration
@ConditionalOnClass(RateLimiterProvider.class)
@ConditionalOnProperty(prefix = RateLimiterProperties.PRE, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterAutoConfiguration {

    /**
     * 创建限流提供者。
     *
     * @param rateLimiterProperties 限流配置属性
     * @return RateLimiterProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterProvider rateLimiterProvider(RateLimiterProperties rateLimiterProperties) {
        long warmupPeriod = rateLimiterProperties.getWarmupPeriod();
        if (warmupPeriod > 0L) {
            return new GuavaRateLimiterProvider(
                    rateLimiterProperties.getName(),
                    rateLimiterProperties.getPermitsPerSecond(),
                    warmupPeriod);
        }
        return new GuavaRateLimiterProvider(
                rateLimiterProperties.getName(),
                rateLimiterProperties.getPermitsPerSecond());
    }

    /**
     * 创建限流拦截器。
     *
     * @return RateLimiterIntercept 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterIntercept rateLimiterIntercept() {
        return new RateLimiterIntercept();
    }

    /**
     * 创建限流 AOP 通知器。
     *
     * @param intercept 限流拦截器
     * @return RateLimiterAdvisor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterAdvisor rateLimiterAdvisor(RateLimiterIntercept intercept) {
        return new RateLimiterAdvisor(intercept);
    }
}