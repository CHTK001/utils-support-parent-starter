package com.chua.spring.support.configuration;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link ConditionalOnMissingBeanDefinition} 的条件实现：
 * 在 {@link ConfigurationPhase#REGISTER_BEAN} 阶段检查 BeanDefinitionRegistry
 * 是否已存在指定名称的 Bean 定义。
 *
 * @author CH
 * @since 2026/09/04
 */
public class OnMissingBeanDefinitionCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        BeanDefinitionRegistry registry = context.getRegistry();
        if (registry == null) {
            return true;
        }
        var attributes = metadata.getAnnotationAttributes(ConditionalOnMissingBeanDefinition.class.getName());
        if (attributes == null) {
            return true;
        }
        String[] beanNames = (String[]) attributes.get("value");
        for (String beanName : beanNames) {
            if (registry.containsBeanDefinition(beanName)) {
                return false;
            }
        }
        return true;
    }
}
