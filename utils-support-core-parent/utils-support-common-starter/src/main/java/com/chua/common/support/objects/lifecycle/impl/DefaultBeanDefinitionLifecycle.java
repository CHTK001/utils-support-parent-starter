package com.chua.common.support.objects.lifecycle.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.function.InitializingAware;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.lifecycle.BeanDefinitionLifecycle;
import com.chua.common.support.objects.lifecycle.AutoPostConstruct;
import com.chua.common.support.objects.lifecycle.AutoPreDestroy;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import org.jspecify.annotations.NullUnmarked;

/**
 * 默认生命周期处理器，支持 InitializingAware 接口、@PostConstruct、@PreDestroy。
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
@Slf4j
@Spi("default")
@SpiDescribe("默认生命周期处理器")
public class DefaultBeanDefinitionLifecycle implements BeanDefinitionLifecycle {

    @Override
    public boolean isSupport(BeanDefinition beanDefinition) {
        return true;
    }

    @Override
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
        // @PostConstruct
        invokeAnnotatedMethods(bean, AutoPostConstruct.class);
    }

    @Override
    public void destroy(BeanDefinition beanDefinition, Object bean) throws Exception {
        if (bean == null) {
            return;
        }
        // @PreDestroy
        invokeAnnotatedMethods(bean, AutoPreDestroy.class);
    }

    private void invokeAnnotatedMethods(Object bean, Class<? extends java.lang.annotation.Annotation> annotationType) {
        for (Method method : ClassUtils.getLocalMethods(bean.getClass())) {
            if (method.isAnnotationPresent(annotationType) && method.getParameterCount() == 0) {
                try {
                    ClassUtils.setAccessible(method);
                    method.invoke(bean);
                } catch (Exception e) {
                    log.warn("调用生命周期方法失败: {}", method.getName(), e);
                }
            }
        }
    }
}