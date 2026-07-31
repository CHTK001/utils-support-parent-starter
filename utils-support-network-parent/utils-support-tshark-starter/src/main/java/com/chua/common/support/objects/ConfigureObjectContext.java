package com.chua.common.support.objects;

public interface ConfigureObjectContext {
    <T> T getBean(String name, Class<T> type);
    <T> T getBeanOfType(Class<T> type);
    <T> java.util.Map<String, T> getBeansOfType(Class<T> type);
    void initialize(ObjectContextSetting setting);
    com.chua.common.support.objects.environment.Environment getEnvironment();
    com.chua.common.support.objects.register.impl.MappingBeanDefinitionRegister getMappingBeanDefinitionRegister();
}
