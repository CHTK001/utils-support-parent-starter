package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.rate.RateLimiterProvider;
import com.chua.common.support.concurrent.rate.provider.GuavaRateLimiterProvider;
import com.chua.spring.support.aop.RateLimiterAdvisor;
import com.chua.spring.support.proxy.intercept.RateLimiterIntercept;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 限流自动配置。
 *
 * <p>注入默认的 Guava {@link RateLimiterProvider}、拦截器和 AOP Advisor。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
@AutoConfiguration
@ConditionalOnClass(RateLimiterProvider.class)
public class RateLimiterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterProvider rateLimiterProvider() {
        return new GuavaRateLimiterProvider("default", 1000);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterIntercept rateLimiterIntercept() {
        return new RateLimiterIntercept();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterAdvisor rateLimiterAdvisor(RateLimiterIntercept intercept) {
        return new RateLimiterAdvisor(intercept);
    }
}