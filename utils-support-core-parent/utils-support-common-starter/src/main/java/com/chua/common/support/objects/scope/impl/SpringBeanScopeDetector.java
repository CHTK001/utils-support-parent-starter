package com.chua.common.support.objects.scope.impl;

import com.chua.common.support.objects.definition.BeanScope;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.objects.scope.BeanScopeDetector;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.lang.annotation.Annotation;

/**
* Spring 作用域检测器。
*
* <p>通过反射检测 Spring 作用域注解，不依赖 Spring 编译时 API。
* 支持的注解：
* <ul>
*   <li>@Scope("prototype") → PROTOTYPE</li>
*   <li>@Prototype → PROTOTYPE</li>
*   <li>@Scope("singleton") / 默认值 → SINGLETON</li>
* </ul></p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("spring")
@SpiDescribe("Spring 作用域检测器")
public class SpringBeanScopeDetector implements BeanScopeDetector {

    @Override
    /** Detect */
    public BeanScope detect(Class<?> beanClass) {
        if (beanClass == null) {
            return null;
        }
        for (Annotation ann : beanClass.getAnnotations()) {
            String name = ann.annotationType().getName();
            if ("org.springframework.context.annotation.Scope".equals(name)) {
                String value = getAnnotationValue(ann, "value", "");
                if ("prototype".equals(value)
                        || "org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE".equals(value)) {
                    return BeanScope.PROTOTYPE;
                }
                return BeanScope.SINGLETON;
            }
            if ("org.springframework.context.annotation.Prototype".equals(name)) {
                return BeanScope.PROTOTYPE;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    /**
    * 获取注解值
    *
    * @param annotation 注解
    * @param attribute attribute
    * @param defaultValue 默认值
    * @return 获取注解值的结果
     */
    private static <T> T getAnnotationValue(Annotation annotation, String attribute, T defaultValue) {
        try {
return (T) ReflectUtils.invoke(annotation, attribute, Object.class, new Class<?>[0]);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
