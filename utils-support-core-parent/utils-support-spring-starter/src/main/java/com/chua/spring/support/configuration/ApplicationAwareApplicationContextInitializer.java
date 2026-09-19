package com.chua.spring.support.configuration;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;


/**
 * 应用上下文感知初始化器，在 Spring 容器初始化时设置 application上下文 到 springBean工具
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ApplicationAwareApplicationContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /**
     * 初始化应用上下文，将 application上下文 设置到 springBean工具
     *
     * @param applicationContext 可配置的应用上下文
     */
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        SpringBeanUtils.setApplicationContext(applicationContext);
    }
}
