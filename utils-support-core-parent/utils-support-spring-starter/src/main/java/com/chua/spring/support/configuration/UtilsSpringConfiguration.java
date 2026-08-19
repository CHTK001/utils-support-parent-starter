package com.chua.spring.support.configuration;

import com.chua.spring.support.aop.DistributedLockAdvisor;
import com.chua.spring.support.convert.DateConvertConfiguration;
import com.chua.spring.support.convert.FormatterConfiguration;
import com.chua.spring.support.proxy.intercept.DistributedLockIntercept;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;

/**
 * Spring 集成总配置：导入日期转换与 MVC Formatter，启用 AOP 并注入默认拦截器。
 * <p>
 * 纯 Spring 环境可通过 {@code @Import(UtilsSpringConfiguration.class)} 启用；
 * Spring Boot 环境由 {@code utils-support-springboot-starter} 自动装配。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Configuration
@EnableAspectJAutoProxy
@Import({DateConvertConfiguration.class, FormatterConfiguration.class})
public class UtilsSpringConfiguration {

    @Bean
    /** Distributed锁Intercept */
    public DistributedLockIntercept distributedLockIntercept() {
        return new DistributedLockIntercept();
    }

    @Bean
    /** Distributed锁Advisor */
    public DistributedLockAdvisor distributedLockAdvisor(DistributedLockIntercept intercept) {
        return new DistributedLockAdvisor(intercept);
    }
}