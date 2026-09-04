package com.chua.springboot.support.autoconfigure;

import com.chua.spring.support.aop.CollapsibleAdvisor;
import com.chua.spring.support.annotation.Collapsible;
import com.chua.spring.support.proxy.intercept.CollapsibleIntercept;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@AutoConfiguration
@ConditionalOnClass(CollapsibleAdvisor.class)
@ConditionalOnProperty(prefix = "collapse.executor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CollapseAutoConfiguration {

    /**
     * 创建折叠拦截器。
     *
     * @return CollapsibleIntercept 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CollapsibleIntercept collapsibleIntercept() {
        return new CollapsibleIntercept();
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
