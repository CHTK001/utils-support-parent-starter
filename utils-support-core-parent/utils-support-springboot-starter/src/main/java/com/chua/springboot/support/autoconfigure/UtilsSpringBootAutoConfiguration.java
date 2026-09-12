package com.chua.springboot.support.autoconfigure;

import com.chua.spring.support.aop.BulkheadAdvisor;
import com.chua.spring.support.aop.TimeoutAdvisor;
import com.chua.spring.support.configuration.ApplicationAwareApplicationContextInitializer;
import com.chua.spring.support.configuration.SpringBeanUtils;
import com.chua.spring.support.configuration.UtilsSpringConfiguration;
import com.chua.spring.support.proxy.intercept.BulkheadIntercept;
import com.chua.spring.support.proxy.intercept.TimeoutIntercept;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
* 工具 Spring Boot 自动配置类。
* <p>
* 导入 {@link UtilsSpringConfiguration} 核心配置，
* 确保 {@link SpringBeanUtils} 持有 {@link ApplicationContext}。
*
* @author CH
* @since 4.0.0
* @param intercept intercept
* @return 超时advisor的结果
 */
@AutoConfiguration
@ConditionalOnClass(SpringBeanUtils.class)
@Import(UtilsSpringConfiguration.class)
public class UtilsSpringBootAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApplicationAwareApplicationContextInitializer applicationAwareApplicationContextInitializer(
            ApplicationContext applicationContext) {
        SpringBeanUtils.setApplicationContext(applicationContext);
        return new ApplicationAwareApplicationContextInitializer();
    }

    @Bean
    @ConditionalOnMissingBean
    public BulkheadIntercept bulkheadIntercept() {
        return new BulkheadIntercept();
    }

    @Bean
    @ConditionalOnMissingBean
    public TimeoutIntercept timeoutIntercept() {
        return new TimeoutIntercept();
    }

    @Bean
    @ConditionalOnMissingBean
    public BulkheadAdvisor bulkheadAdvisor(BulkheadIntercept intercept) {
        return new BulkheadAdvisor(intercept);
    }

    @Bean
    @ConditionalOnMissingBean
public TimeoutAdvisor timeoutAdvisor(TimeoutIntercept intercept) {
        return new TimeoutAdvisor(intercept);
    }
}