package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.spring.support.aop.CollapsibleAdvisor;
import com.chua.spring.support.annotation.Collapsible;
import com.chua.spring.support.proxy.intercept.CollapsibleIntercept;
import com.chua.springboot.support.properties.CollapseProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
* 请求折叠自动配置。
*
* <p>为 Spring Boot 环境注册 {@link CollapsibleIntercept} 与 {@link CollapsibleAdvisor}，
* 使标注 {@link Collapsible} 注解的方法开箱即用地获得请求折叠能力
* （合并拆分 / 同参折叠，见 {@link Collapsible} 语义说明）。</p>
*
* <p>总开关：{@code collapse.executor.enabled=false} 可关闭（默认开启）；
* 折叠执行器实现（utils-support-collapse-starter）缺失时自动降级为直接执行。</p>
*
* @author CH
* @since 2026/09/03
 */
@AutoConfiguration(before = UtilsSpringBootAutoConfiguration.class)
@ConditionalOnClass(CollapsibleAdvisor.class)
@ConditionalOnProperty(prefix = "collapse.executor", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CollapseProperties.class)
public class CollapseAutoConfiguration {

    /**
    * 创建折叠拦截器（注入全局默认配置，注解未显式指定的属性读取全局默认值）。
    *
    * @param properties 折叠全局默认配置
    * @return CollapsibleIntercept 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CollapsibleIntercept collapsibleIntercept(CollapseProperties properties) {
        return new CollapsibleIntercept(toGlobalConfig(properties));
    }

    /**
    * 将全局默认配置转换为折叠配置。
    *
    * @param properties 折叠全局默认配置
    * @return CollapseConfig 实例
     */
    private static CollapseConfig toGlobalConfig(CollapseProperties properties) {
        CollapseConfig config = new CollapseConfig();
        config.setWaitThreshold(properties.getWaitThreshold());
        config.setCollectingWaitTime(properties.getCollectingWaitTime());
        return config;
    }

    /**
    * 创建折叠 AOP 通知器。
    *
    * @param intercept 折叠拦截器
    * @return CollapsibleAdvisor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CollapsibleAdvisor collapsibleAdvisor(CollapsibleIntercept intercept) {
        return new CollapsibleAdvisor(intercept);
    }
}
