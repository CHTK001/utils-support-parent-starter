package com.chua.common.support.objects.resolver;

import com.chua.common.support.objects.definition.BeanDefinition;

import java.lang.annotation.Annotation;
import java.util.function.Function;

/**
 * 构造器参数解析器 SPI 接口。
 *
 * <p>各容器（Spring、Quarkus 等）可实现此接口，
 * 在 {@link BeanDefinition#getBean()} 创建实例时解析构造器参数。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@FunctionalInterface
public interface BeanConstructorResolver {

    /**
     * 解析构造器参数。
     *
     * @param paramType    参数类型
     * @param paramName    参数名称
     * @param annotations  参数上的注解
     * @param typeProvider 按类型查找 Bean 的回调
     * @param nameProvider 按名称查找 Bean 的回调
     * @param beanDefinition 当前 Bean 定义
     * @return 解析后的参数值，无法解析返回 null
     */
    Object resolve(
            Class<?> paramType,
            String paramName,
            Annotation[] annotations,
            Function<Class<?>, Object> typeProvider,
            Function<String, Object> nameProvider,
            BeanDefinition beanDefinition);
}
