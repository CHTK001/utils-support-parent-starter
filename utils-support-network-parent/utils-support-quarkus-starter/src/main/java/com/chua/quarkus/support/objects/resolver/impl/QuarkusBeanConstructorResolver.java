package com.chua.quarkus.support.objects.resolver.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.exception.BeanDefinitionException;
import com.chua.common.support.objects.resolver.BeanConstructorResolver;
import com.chua.common.support.spi.annotations.Spi;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.lang.annotation.Annotation;
import java.util.function.Function;

/**
 * Quarkus (CDI) 构造器参数解析器。
 *
 * <p>直接使用 Jakarta {@link Inject} / {@link Named} 注解，
 * 从容器按 名称 名称或参数类型查找 Bean。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("quarkus")
public class QuarkusBeanConstructorResolver implements BeanConstructorResolver {

    /**
     * 日志
     */
    private static final System.Logger LOG =
            System.getLogger(QuarkusBeanConstructorResolver.class.getName());

    /**
     * 解析
     * @param paramType 参数类型
     * @param paramName 参数名称
     * @param annotations 注解
     * @param typeProvider 类型提供者
     * @param nameProvider 名称提供者
     * @param beanDefinition Beandefinition
     */
    @Override
    public Object resolve(Class<?> paramType, String paramName, Annotation[] annotations,
                          Function<Class<?>, Object> typeProvider,
                          Function<String, Object> nameProvider,
                          BeanDefinition beanDefinition) {
        if (annotations == null || annotations.length == 0) {
            return null;
        }

        boolean hasInject = false;
        String namedValue = null;

        for (Annotation ann : annotations) {
            if (ann instanceof Inject) {
                hasInject = true;
            } else if (ann instanceof Named n) {
                String v = n.value();
                if (!v.isEmpty()) {
                    namedValue = v;
                }
            }
        }

        if (!hasInject) {
            return null;
        }

        if (namedValue != null && nameProvider != null) {
            try {
                Object result = nameProvider.apply(namedValue);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "[quarkus] 解析参数失败，已忽略: " + e.getMessage(), e);
            }
        }

        if (typeProvider != null) {
            try {
                Object result = typeProvider.apply(paramType);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "[quarkus] 解析参数失败，已忽略: " + e.getMessage(), e);
            }
        }

        if (nameProvider != null) {
            try {
                Object result = nameProvider.apply(paramName);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "[quarkus] 解析参数失败，已忽略: " + e.getMessage(), e);
            }
        }

        throw new BeanDefinitionException("@Inject 参数无法解析: " + paramType.getName() + " " + paramName);
    }
}
