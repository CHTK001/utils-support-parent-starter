package com.chua.common.support.objects;

/**
 * 配置型对象上下文接口，封装 Bean 查找、初始化与 BeanDefinition 注册等能力。
 *
 * @author CH
 * @since 4.0.0
 */
public interface ConfigureObjectContext {
    <T> T getBean(String name, Class<T> type);
    <T> T getBeanOfType(Class<T> type);
    <T> java.util.Map<String, T> getBeansOfType(Class<T> type);
    void initialize(ObjectContextSetting setting);
    com.chua.common.support.objects.environment.Environment getEnvironment();
    com.chua.common.support.objects.register.impl.MappingBeanDefinitionRegister getMappingBeanDefinitionRegister();
}
