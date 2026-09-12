package com.chua.common.support.objects.resolver.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.resolver.BeanConstructorResolver;
import com.chua.common.support.spi.annotations.Spi;

import java.lang.annotation.Annotation;
import java.util.function.Function;

/**
* 默认构造器参数解析器。
*
* <p>通过容器提供的 {@code typeProvider} / {@code nameProvider} 回调按类型和名称查找 Bean。
* 作为兜底解析器，优先级最低（{@code order = -1000}）。</p>
*
* @author CH
* @since 2024/12/20
 */
@Spi(value = "default", order = -1000)
public class DefaultBeanConstructorResolver implements BeanConstructorResolver {

    @Override
    /**
    * 解析
    * @param paramType 参数类型
    * @param paramName 参数名称
    * @param annotations 注解
    * @param typeProvider 类型提供者
    * @param nameProvider 名称提供者
    * @param beanDefinition Beandefinition
     */
    public Object resolve(Class<?> paramType, String paramName, Annotation[] annotations,
                          Function<Class<?>, Object> typeProvider,
                          Function<String, Object> nameProvider,
                          BeanDefinition beanDefinition) {
        if (typeProvider != null) {
            try {
                Object result = typeProvider.apply(paramType);
                if (result != null) {
                    return result;
                }
            } catch (Exception ignored) {
            }
        }
        if (nameProvider != null) {
            try {
                return nameProvider.apply(paramName);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
