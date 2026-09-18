package com.chua.common.support.objects;

/**
* 配置型对象上下文接口，封装 Bean 查找、初始化与 Beandefinition 注册等能力。
*
* @author CH
* @since 4.0.0.42
 */
public interface ConfigureObjectContext {
    /**
     * 获取Bean。
     *
     * @param name 名称，不允许为 null
     * @param type 类型，不允许为 null
     * @return T 对象
     */
    <T> T getBean(String name, Class<T> type);
    /**
     * 获取BeanOf类型。
     *
     * @param type 类型，不允许为 null
     * @return T 对象
     */
    <T> T getBeanOfType(Class<T> type);
    /**
     * 获取BeansOf类型。
     *
     * @param type 类型，不允许为 null
     * @return 结果值
     */
    <T> java.util.Map<String, T> getBeansOfType(Class<T> type);
    /**
     * initialize。
     *
     * @param setting 方法入参 setting
     */
    void initialize(ObjectContextSetting setting);
    /**
     * 获取Environment。
     *
     * @return 结果值
     */
    com.chua.common.support.objects.environment.Environment getEnvironment();
    /**
     * 获取MappingBeanDefinition注册。
     *
     * @return 结果值
     */
    com.chua.common.support.objects.register.impl.MappingBeanDefinitionRegister getMappingBeanDefinitionRegister();
}
