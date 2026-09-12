package com.chua.spring.support.configuration;

import com.chua.spring.support.aop.CollapsibleAdvisor;
import com.chua.spring.support.aop.DistributedLockAdvisor;
import com.chua.spring.support.convert.DateConvertConfiguration;
import com.chua.spring.support.convert.FormatterConfiguration;
import com.chua.spring.support.proxy.intercept.CollapsibleIntercept;
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
    @ConditionalOnMissingBeanDefinition({"distributedLockIntercept", "distributedLockAdvisor"})
    /**
    * Distributed锁Intercept
    *
    * @return distributed锁intercept的结果
     */
    public DistributedLockIntercept distributedLockIntercept() {
        return new DistributedLockIntercept();
    }

    @Bean
    @ConditionalOnMissingBeanDefinition({"distributedLockIntercept", "distributedLockAdvisor"})
    /**
    * Distributed锁Advisor
    *
    * @param intercept intercept
    * @return distributed锁advisor的结果
     */
    public DistributedLockAdvisor distributedLockAdvisor(DistributedLockIntercept intercept) {
        return new DistributedLockAdvisor(intercept);
    }

    @Bean
    @ConditionalOnMissingBeanDefinition({"collapsibleIntercept", "collapsibleAdvisor"})
    /**
    * Collapse拦截
    *
    * @return collapsibleIntercept的结果
     */
    public CollapsibleIntercept collapsibleIntercept() {
        return new CollapsibleIntercept();
    }

    @Bean
    @ConditionalOnMissingBeanDefinition({"collapsibleIntercept", "collapsibleAdvisor"})
    /**
    * collapseadvisor
    *
    * @param intercept intercept
    * @return collapsibleAdvisor的结果
     */
    public CollapsibleAdvisor collapsibleAdvisor(CollapsibleIntercept intercept) {
        return new CollapsibleAdvisor(intercept);
    }
}