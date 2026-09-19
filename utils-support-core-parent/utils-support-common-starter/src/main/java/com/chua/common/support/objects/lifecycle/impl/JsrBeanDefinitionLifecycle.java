package com.chua.common.support.objects.lifecycle.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.objects.lifecycle.BeanDefinitionLifecycle;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * JSR 标准生命周期处理器，通过反射处理 @postconstruct 和 @pre销毁 注解。
 *
 * <p>支持的 JSR 标准：
 * <ul>
 *   <li>JSR-250：@PostConstruct（javax.annotation.PostConstruct / jakarta.annotation.PostConstruct）</li>
 *   <li>JSR-250：@PreDestroy（javax.annotation.PreDestroy / jakarta.annotation.PreDestroy）</li>
 * </ul></p>
 *
 * <p>所有注解均通过反射按类名检测，不依赖编译时注解 API。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jsr")
@SpiDescribe("JSR 标准生命周期处理器（@PostConstruct、@PreDestroy）")
public class JsrBeanDefinitionLifecycle implements BeanDefinitionLifecycle {

    /**
     * Post_Construct_javax
    */
    private static final String POST_CONSTRUCT_JAVAX = "javax.annotation.PostConstruct";
    /**
     * Post_Construct_jakarta
    */
    private static final String POST_CONSTRUCT_JAKARTA = "jakarta.annotation.PostConstruct";
    /**
     * Pre_销毁_javax
    */
    private static final String PRE_DESTROY_JAVAX = "javax.annotation.PreDestroy";
    /**
     * Pre_销毁_jakarta
    */
    private static final String PRE_DESTROY_JAKARTA = "jakarta.annotation.PreDestroy";

    @Override
    /**
     * 是否支持
    */
    public boolean isSupport(BeanDefinition beanDefinition) {
        return true;
    }

    @Override
    /**
     * 初始化
    */
    public void init(BeanDefinition beanDefinition, Object bean) throws Exception {
        if (bean == null) {
            return;
        }
        invokeAnnotatedMethods(bean, POST_CONSTRUCT_JAVAX, POST_CONSTRUCT_JAKARTA);
    }

    @Override
    /**
     * 销毁
    */
    public void destroy(BeanDefinition beanDefinition, Object bean) throws Exception {
        if (bean == null) {
            return;
        }
        invokeAnnotatedMethods(bean, PRE_DESTROY_JAVAX, PRE_DESTROY_JAKARTA);
    }

    /**
     * 调用annotated方法
     *
     * @param bean Bean
     * @param annotationNames 注解名称
     */
    private void invokeAnnotatedMethods(Object bean, String... annotationNames) {
        for (Method method : ClassUtils.getLocalMethods(bean.getClass())) {
            if (method.getParameterCount() > 0) {
                continue;
            }
            if (hasAnyAnnotation(method, annotationNames)) {
                try {
                    ClassUtils.setAccessible(method);
                    ReflectUtils.invoke(bean, method.getName(), method.getReturnType(), method.getParameterTypes(), new Object[0], new Object[0]);
                } catch (Exception e) {
                    log.warn("调用 JSR 生命周期方法失败: {}", method.getName(), e);
                }
            }
        }
    }

    /**
     * 是否拥有任意注解
     *
     * @param method 方法
     * @param annotationNames 注解名称
     * @return 是否包含任意注解的结果
     */
    private boolean hasAnyAnnotation(Method method, String... annotationNames) {
        for (Annotation ann : method.getAnnotations()) {
            String name = ann.annotationType().getName();
            for (String target : annotationNames) {
                if (target.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
