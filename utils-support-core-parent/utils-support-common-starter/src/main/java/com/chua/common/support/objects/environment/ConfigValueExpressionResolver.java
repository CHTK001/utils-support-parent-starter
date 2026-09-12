package com.chua.common.support.objects.environment;

import com.chua.common.support.spi.annotations.Spi;

/**
* 配置值表达式解析器 SPI，处理 {@code ${key:default}}、{@code #{...}} 等表达式格式。
*
* <p>由 {@link com.chua.common.support.objects.inject.BeanDefinitionConfigInjector} 调用，
* 按 SPI 链尝试匹配，首个 {@link #isSupport} 返回 true 的解析器负责解析。</p>
*
* @author CH
* @since 2024/12/20
 */
@Spi
public interface ConfigValueExpressionResolver {

    /**
    * 是否支持该表达式格式。
    *
    * @param expression 原始表达式，如 {@code ${server.port:8080}}
    * @return true 表示支持
     */
    boolean isSupport(String expression);

    /**
    * 解析表达式为配置值。
    *
    * @param expression  原始表达式
    * @param targetType  目标类型
    * @param environment 环境配置
    * @param <T>         泛型类型
    * @return 解析后的值，无法解析返回 空
     */
    <T> T resolve(String expression, Class<T> targetType, Environment environment);
}
