package com.chua.spring.support.configuration;

import com.chua.common.support.task.script.ScriptProviders;
import com.chua.spring.support.script.SpringScriptProviderResolver;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 脚本引擎发现注册器：Spring 宿主下把引擎发现切到 SpringFactoriesLoader。
 *
 * <p>Spring 容器初始化时由 {@code META-INF/spring.factories} 的
 * {@code org.springframework.context.ApplicationContextInitializer} 键触发，将
 * {@link ScriptProviders} 的发现实现替换为 {@link SpringScriptProviderResolver}，
 * 使脚本引擎改由 Spring 自带的 {@code SpringFactoriesLoader} 统一发现，与 Spring 的
 * SPI 机制保持一致。</p>
 *
 * @author CH
 * @since 4.0.0.43
 * @see SpringScriptProviderResolver
 */
public class ScriptProviderSpringRegistrar implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /**
     * 初始化：把脚本引擎发现实现切到基于 SpringFactoriesLoader 的实现。
     *
     * @param applicationContext 可配置的应用上下文
     */
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ScriptProviders.useResolver(new SpringScriptProviderResolver());
    }
}
