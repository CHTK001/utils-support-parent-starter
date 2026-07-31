package com.chua.common.support.objects.impl;

import com.chua.common.support.objects.ConfigureObjectContext;
import com.chua.common.support.objects.ObjectContextSetting;

public class DefaultConfigureObjectContext implements ConfigureObjectContext {
    public DefaultConfigureObjectContext(ObjectContextSetting setting) {
    }

    @Override
    public <T> T getBean(String name, Class<T> type) { return null; }
    @Override
    public <T> T getBeanOfType(Class<T> type) { return null; }
    @Override
    public <T> java.util.Map<String, T> getBeansOfType(Class<T> type) { return java.util.Collections.emptyMap(); }
    @Override
    public void initialize(ObjectContextSetting setting) {
    }

    @Override
    public com.chua.common.support.objects.environment.Environment getEnvironment() {
        return null;
    }

    @Override
    public com.chua.common.support.objects.register.impl.MappingBeanDefinitionRegister getMappingBeanDefinitionRegister() {
        return new com.chua.common.support.objects.register.impl.MappingBeanDefinitionRegister();
    }
}
