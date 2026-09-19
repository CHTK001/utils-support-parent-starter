package com.chua.common.support.objects.definition;

import com.chua.common.support.objects.register.BeanDefinitionRegister;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * Bean 定义增强接口，扩展标准 Beandefinition 的接口、注解、生命周期与映射定义查询。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EnhancedBeanDefinition {
    /**
     * 获取Interfaces。
     *
     * @return 结果列表，无数据时为空列表
     */
    Collection<Class<?>> getInterfaces();
    /**
     * 获取Bean类型。
     *
     * @return Class 对象
     */
    Class<?> getBeanType();
    /**
     * 获取方法Definitions。
     *
     * @return 结果列表，无数据时为空列表
     */
    Collection<MethodDefinition> getMethodDefinitions();
    /**
     * 获取对象。
     *
     * @return 对象 对象
     */
    Object getObject();
    /**
     * 获取MappingDefinition。
     *
     * @return 结果映射，无数据时为空映射
     */
    MappingDefinition getMappingDefinition();
    /**
     * 获取LifecycleDefinition。
     *
     * @return LifecycleDefinition 对象
     */
    LifecycleDefinition getLifecycleDefinition();
    /**
     * 是否AnnotationPresent。
     *
     * @param annotationClass 方法入参 annotationClass
     * @return 是否成功（true 表示成功）
     */
    boolean isAnnotationPresent(Class<? extends Annotation> annotationClass);
    /**
     * 获取Annotation。
     *
     * @param annotationClass 方法入参 annotationClass
     * @return T 对象
     */
    <T extends Annotation> T getAnnotation(Class<T> annotationClass);
    /**
     * 获取Registers。
     *
     * @return 结果列表，无数据时为空列表
     */
    List<BeanDefinitionRegister> getRegisters();
    /**
     * 添加注册。
     *
     * @param register 注册，不允许为 null
     */
    void addRegister(BeanDefinitionRegister register);
    /**
     * 移除注册。
     *
     * @param register 注册，不允许为 null
     */
    void removeRegister(BeanDefinitionRegister register);
    /**
     * 获取MethodsWithAnnotation。
     *
     * @param annotationClass 方法入参 annotationClass
     * @return 结果列表，无数据时为空列表
     */
    List<Method> getMethodsWithAnnotation(Class<? extends Annotation> annotationClass);
    /**
     * 获取MethodsWithAnnotation。
     *
     * @param annotationName annotation名称，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    List<Method> getMethodsWithAnnotation(String annotationName);
    /**
     * 获取方法WithAnnotation。
     *
     * @param annotationClass 方法入参 annotationClass
     * @return 方法 对象
     */
    Method getMethodWithAnnotation(Class<? extends Annotation> annotationClass);
    /**
     * 获取方法WithAnnotation。
     *
     * @param annotationName annotation名称，不允许为 null
     * @return 方法 对象
     */
    Method getMethodWithAnnotation(String annotationName);
    /**
     * 是否含有方法WithAnnotation。
     *
     * @param annotationClass 方法入参 annotationClass
     * @return 是否成功（true 表示成功）
     */
    boolean hasMethodWithAnnotation(Class<? extends Annotation> annotationClass);
    /**
     * 创建实例。
     *
     * @param context 上下文，不允许为 null
     * @return 对象 对象
     */
    Object createInstance(com.chua.common.support.objects.ObjectContext context);
    /**
     * 方法definition接口。
     *
     * @author CH
     * @since 4.0.0
     */

    interface MethodDefinition {
    }

    interface LifecycleDefinition {
    }
}
