package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.lock.LockFlow;
import com.chua.common.support.concurrent.lock.LockProvider;
import com.chua.spring.support.aop.DistributedLockAdvisor;
import com.chua.spring.support.proxy.intercept.DistributedLockIntercept;
import com.chua.springboot.support.properties.LockProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 分布式锁自动配置。
 *
 * <p>注入可配置的 {@link LockProvider}、拦截器和 AOP Advisor。
 * 锁类型、公平性、等待时间与租约时间均可通过 {@code plugin.lock.*} 配置。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
@AutoConfiguration
@ConditionalOnClass(LockProvider.class)
@ConditionalOnProperty(prefix = LockProperties.PRE, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LockProperties.class)
public class LockAutoConfiguration {

    /**
     * 默认锁名称
     */
    private static final String DEFAULT_LOCK_NAME = "default";

    /**
     * 创建分布式锁提供者。
     *
     * @param lockProperties 锁配置属性
     * @return LockProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public LockProvider lockProvider(LockProperties lockProperties) {
        return LockFlow.of(DEFAULT_LOCK_NAME)
                .lockType(lockProperties.getType())
                .fair(lockProperties.isFair())
                .waitTime(lockProperties.getWaitTime())
                .leaseTime(lockProperties.getLeaseTime())
                .provider();
    }

    /**
     * 创建分布式锁拦截器。
     *
     * @return DistributedLockIntercept 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public DistributedLockIntercept distributedLockIntercept() {
        return new DistributedLockIntercept();
    }

    /**
     * 创建分布式锁 AOP 通知器。
     *
     * @param intercept 分布式锁拦截器
     * @return DistributedLockAdvisor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public DistributedLockAdvisor distributedLockAdvisor(DistributedLockIntercept intercept) {
        return new DistributedLockAdvisor(intercept);
    }
}
