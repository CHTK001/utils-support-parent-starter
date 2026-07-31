package com.chua.springboot.support.autoconfigure;

import com.chua.spring.support.configuration.ApplicationAwareApplicationContextInitializer;
import com.chua.spring.support.configuration.SpringBeanUtils;
import com.chua.spring.support.configuration.UtilsSpringConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Utils Spring Boot 自动配置类。
 * <p>
 * 导入 {@link UtilsSpringConfiguration} 核心配置，
 * 确保 {@link SpringBeanUtils} 持有 {@link ApplicationContext}。
 *
 * @author CH
 */
@AutoConfiguration
@ConditionalOnClass(SpringBeanUtils.class)
@Import(UtilsSpringConfiguration.class)
public class UtilsSpringBootAutoConfiguration {

    /**
     * 创建并注册 ApplicationAwareApplicationContextInitializer Bean。
     * <p>
     * 此方法仅在容器中不存在同类型 Bean 时执行。
     *
     * @param applicationContext 当前 Spring 应用上下文
     * @return 初始化的 ApplicationAwareApplicationContextInitializer 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ApplicationAwareApplicationContextInitializer applicationAwareApplicationContextInitializer(
            ApplicationContext applicationContext) {
        SpringBeanUtils.setApplicationContext(applicationContext);
        return new ApplicationAwareApplicationContextInitializer();
    }
}