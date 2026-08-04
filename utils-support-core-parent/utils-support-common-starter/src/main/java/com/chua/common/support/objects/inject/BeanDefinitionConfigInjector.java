package com.chua.common.support.objects.inject;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.environment.Environment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.jspecify.annotations.NullUnmarked;

/**
 * Bean 配置注入器 SPI，用于将配置值注入到目标字段或方法参数。
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi
@SuppressWarnings("NullAway")
@NullUnmarked
public interface BeanDefinitionConfigInjector {

    /**
     * 判断当前注入器是否支持对指定字段进行配置注入。
     *
     * @param field            待检查的目标字段
     * @param beanDefinition   对应的 Bean 定义信息
     * @return 如果支持注入则返回 true，否则返回 false
     */
    boolean isSupport(Field field, BeanDefinition beanDefinition);

    /**
     * 执行具体的配置注入逻辑，将环境中的配置值设置到目标字段中。
     *
     * @param field            目标字段，将被注入配置值
     * @param bean             目标 Bean 实例，包含需要被注入的字段
     * @param beanDefinition   Bean 的定义信息，提供上下文元数据
     * @param environment      环境配置对象，提供可读取的配置源
     * @return 注入后的新值；如果无法注入或不适配，则返回 null
     */
    Object inject(Field field, Object bean, BeanDefinition beanDefinition, Environment environment);

    /**
     * 判断当前注入器是否支持对指定方法进行配置注入。
     *
     * @param method          目标方法
     * @param beanDefinition  Bean 定义
     * @return 如果支持注入则返回 true，否则返回 false
     */
    default boolean isSupport(Method method, BeanDefinition beanDefinition) {
        return false;
    }

    /**
     * 执行方法配置注入：按参数依次解析配置值并返回参数数组。
     *
     * @param method          目标方法
     * @param bean            目标 Bean 实例
     * @param beanDefinition  Bean 定义
     * @param environment     环境配置对象
     * @return 参数值数组，每项为对应位置的配置值；无法解析的位为 null
     */
    default Object[] inject(Method method, Object bean, BeanDefinition beanDefinition, Environment environment) {
        return null;
    }
}