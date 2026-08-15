package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerProvider;
import com.chua.common.support.concurrent.circuitbreaker.provider.InMemoryCircuitBreakerProvider;
import com.chua.spring.support.aop.CircuitBreakerAdvisor;
import com.chua.spring.support.proxy.intercept.CircuitBreakerIntercept;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 熔断降级自动配置。
 *
 * @author CH
 * @since 4.0.0.42
 */
@AutoConfiguration
@ConditionalOnClass(CircuitBreakerProvider.class)
public class CircuitBreakerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerProvider circuitBreakerProvider() {
        return new InMemoryCircuitBreakerProvider("default", 5, 2, 60000);
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerIntercept circuitBreakerIntercept() {
        return new CircuitBreakerIntercept();
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerAdvisor circuitBreakerAdvisor(CircuitBreakerIntercept intercept) {
        return new CircuitBreakerAdvisor(intercept);
    }
}