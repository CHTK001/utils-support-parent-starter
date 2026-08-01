package com.chua.common.support.objects.definition;

import com.chua.common.support.objects.register.BeanDefinitionRegister;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * Bean 定义增强接口，扩展标准 BeanDefinition 的接口、注解、生命周期与映射定义查询。
 *
 * @author CH
 * @since 4.0.0
 */
public interface EnhancedBeanDefinition {
    Collection<Class<?>> getInterfaces();
    Class<?> getBeanType();
    Collection<MethodDefinition> getMethodDefinitions();
    Object getObject();
    MappingDefinition getMappingDefinition();
    LifecycleDefinition getLifecycleDefinition();
    boolean isAnnotationPresent(Class<? extends Annotation> annotationClass);
    <T extends Annotation> T getAnnotation(Class<T> annotationClass);
    List<BeanDefinitionRegister> getRegisters();
    void addRegister(BeanDefinitionRegister register);
    void removeRegister(BeanDefinitionRegister register);
    List<Method> getMethodsWithAnnotation(Class<? extends Annotation> annotationClass);
    List<Method> getMethodsWithAnnotation(String annotationName);
    Method getMethodWithAnnotation(Class<? extends Annotation> annotationClass);
    Method getMethodWithAnnotation(String annotationName);
    boolean hasMethodWithAnnotation(Class<? extends Annotation> annotationClass);
    Object createInstance(com.chua.common.support.objects.ObjectContext context);

    interface MethodDefinition {
    }

    interface LifecycleDefinition {
    }
}
