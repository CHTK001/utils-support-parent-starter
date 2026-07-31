package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.lock.LockFlow;
import com.chua.common.support.concurrent.lock.LockProvider;
import com.chua.spring.support.aop.DistributedLockAdvisor;
import com.chua.spring.support.proxy.intercept.DistributedLockIntercept;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 分布式锁自动配置。
 *
 * <p>注入默认的 chronicle {@link LockProvider}、拦截器和 AOP Advisor。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
@AutoConfiguration
@ConditionalOnClass(LockProvider.class)
public class LockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockProvider lockProvider() {
        return LockFlow.of("default")
                .lockType("chronicle")
                .provider();
    }

    @Bean
    @ConditionalOnMissingBean
    public DistributedLockIntercept distributedLockIntercept() {
        return new DistributedLockIntercept();
    }

    @Bean
    @ConditionalOnMissingBean
    public DistributedLockAdvisor distributedLockAdvisor(DistributedLockIntercept intercept) {
        return new DistributedLockAdvisor(intercept);
    }
}