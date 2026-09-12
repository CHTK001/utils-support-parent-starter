package com.chua.common.support.objects.lifecycle.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.function.InitializingAware;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.lifecycle.BeanDefinitionLifecycle;
import com.chua.common.support.objects.lifecycle.AutoPostConstruct;
import com.chua.common.support.objects.lifecycle.AutoPreDestroy;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
   * 默认生命周期处理器，支持 初始化aware 接口、@postconstruct、@pre销毁。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("default")
@SpiDescribe("默认生命周期处理器")
public class DefaultBeanDefinitionLifecycle implements BeanDefinitionLifecycle {

    @Override
    /** 是否支持 */
    public boolean isSupport(BeanDefinition beanDefinition) {
        return true;
    }

    @Override
    /** 初始化 */
    public void init(BeanDefinition beanDefinition, Object bean) throws Exception {
        if (bean == null) {
            return;
        }
        String beanName = beanDefinition != null ? beanDefinition.getName() : "unknown";
        // InitializingAware.afterPropertiesSet()
        if (bean instanceof InitializingAware aware) {
            aware.afterPropertiesSet();
            log.debug("InitializingAware.afterPropertiesSet(): {}", beanName);
        }
 // @postconstruct
        invokeAnnotatedMethods(bean, AutoPostConstruct.class);
    }

    @Override
    /** 销毁 */
    public void destroy(BeanDefinition beanDefinition, Object bean) throws Exception {
        if (bean == null) {
            return;
        }
 // @pre销毁
        invokeAnnotatedMethods(bean, AutoPreDestroy.class);
    }

    /**
     * 调用annotated方法
     *
     * @param bean Bean
     * @param annotationType 注解类型
     */
    private void invokeAnnotatedMethods(Object bean, Class<? extends java.lang.annotation.Annotation> annotationType) {
        for (Method method : ClassUtils.getLocalMethods(bean.getClass())) {
            if (method.isAnnotationPresent(annotationType) && method.getParameterCount() == 0) {
                try {
                    ClassUtils.setAccessible(method);
                    ReflectUtils.invoke(bean, method.getName(), method.getReturnType());
                } catch (Exception e) {
                    log.warn("调用生命周期方法失败: {}", method.getName(), e);
                }
            }
        }
    }
}