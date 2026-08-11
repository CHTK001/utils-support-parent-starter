package com.chua.common.support.setting;

import com.chua.common.support.application.GlobalSettingFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局配置工厂自动注册。
 * <p>
 * 将 {@link GlobalSettingFactory} 单例注册为 Spring Bean，
 * 确保 {@link GlobalSettingAutoConfiguration} 中的扫描逻辑
 * （{@code applicationContext.containsBean("globalSettingFactory")}）能正确执行。
 * </p>
 *
 * @author CH
 * @since 2024/8/13
 */
@Configuration(proxyBeanMethods = false)
public class GlobalSettingFactoryAutoConfiguration {

    @Bean(name = "globalSettingFactory")
    @ConditionalOnMissingBean(name = "globalSettingFactory")
    public GlobalSettingFactory globalSettingFactory() {
        return GlobalSettingFactory.getInstance();
    }
}
