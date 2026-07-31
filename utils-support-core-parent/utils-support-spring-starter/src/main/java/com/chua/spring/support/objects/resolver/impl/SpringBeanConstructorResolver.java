package com.chua.spring.support.objects.resolver.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.exception.BeanDefinitionException;
import com.chua.common.support.objects.resolver.BeanConstructorResolver;
import com.chua.common.support.spi.annotations.Spi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.Annotation;
import java.util.function.Function;

/**
 * Spring 构造器参数解析器。
 *
 * <p>直接使用 Spring 注解 {@link Autowired} / {@link Qualifier}，
 * 从容器按 Qualifier 名称或参数类型查找 Bean。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi("spring")
public class SpringBeanConstructorResolver implements BeanConstructorResolver {

    @Override
    public Object resolve(Class<?> paramType, String paramName, Annotation[] annotations,
                          Function<Class<?>, Object> typeProvider,
                          Function<String, Object> nameProvider,
                          BeanDefinition beanDefinition) {
        if (annotations == null || annotations.length == 0) {
            return null;
        }

        Autowired autowired = null;
        String qualifierName = null;

        for (Annotation ann : annotations) {
            if (ann instanceof Autowired a) {
                autowired = a;
            } else if (ann instanceof Qualifier q) {
                String v = q.value();
                if (!v.isEmpty()) {
                    qualifierName = v;
                }
            }
        }

        if (autowired == null) {
            return null;
        }

        if (qualifierName != null && nameProvider != null) {
            try {
                Object result = nameProvider.apply(qualifierName);
                if (result != null) return result;
            } catch (Exception ignored) {
            }
        }

        if (typeProvider != null) {
            try {
                Object result = typeProvider.apply(paramType);
                if (result != null) return result;
            } catch (Exception ignored) {
            }
        }

        if (nameProvider != null) {
            try {
                Object result = nameProvider.apply(paramName);
                if (result != null) return result;
            } catch (Exception ignored) {
            }
        }

        if (autowired.required()) {
            throw new BeanDefinitionException(
                    "@Autowired(required=true) 参数无法解析: " + paramType.getName() + " " + paramName);
        }

        return null;
    }
}
