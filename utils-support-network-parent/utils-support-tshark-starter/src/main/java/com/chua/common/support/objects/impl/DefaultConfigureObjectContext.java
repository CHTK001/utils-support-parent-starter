package com.chua.common.support.objects.impl;

import com.chua.common.support.objects.ConfigureObjectContext;
import com.chua.common.support.objects.ObjectContextSetting;

/**
 * configure对象上下文 的默认占位实现。所有 Bean 查询返回 空/空，获取mappingBeandefinition注册 返回一个空的注册器实例。
 * <p>用于单元测试或框架未启用任何注册逻辑的场景占位。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultConfigureObjectContext implements ConfigureObjectContext {

    /**
     * 构造函数，接收上下文配置项（当前实现忽略）。
     *
     * @param setting 对象上下文配置项
     */
    public DefaultConfigureObjectContext(ObjectContextSetting setting) {
    }

    @Override
    /** 获取Bean */
    public <T> T getBean(String name, Class<T> type) { return null; }

    @Override
    /** 获取Bean的类型 */
    public <T> T getBeanOfType(Class<T> type) { return null; }

    @Override
    /** 获取Bean的类型 */
    public <T> java.util.Map<String, T> getBeansOfType(Class<T> type) { return java.util.Collections.emptyMap(); }

    /**
     * 初始化上下文（默认实现为空操作）。
     *
     * @param setting 对象上下文配置项
     */
    @Override
    public void initialize(ObjectContextSetting setting) {
    }

    /**
     * @return 默认实现始终返回 空
     */
    @Override
    public com.chua.common.support.objects.environment.Environment getEnvironment() {
        return null;
    }

    /**
     * @return 新建的空 mappingbeandefinition注册 实例
     */
    @Override
    public com.chua.common.support.objects.register.impl.MappingBeanDefinitionRegister getMappingBeanDefinitionRegister() {
        return new com.chua.common.support.objects.register.impl.MappingBeanDefinitionRegister();
    }
}
