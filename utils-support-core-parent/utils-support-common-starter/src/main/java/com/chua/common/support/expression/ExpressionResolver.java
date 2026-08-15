package com.chua.common.support.expression;

import com.chua.common.support.spi.annotations.Spi;

import java.util.Map;

/**
 * 表达式解析器 SPI，统一处理注解属性中的 {@code #{...}} 表达式。
 *
 * <p>由 {@link ExpressionResolvers} 按 SPI 链遍历，首个 {@link #isSupport} 返回 true 的解析器负责解析。
 * 典型实现：</p>
 * <ul>
 *   <li>默认实现 — 基础变量访问（common-starter）</li>
 *   <li>SpEL 实现 — Spring SpEL 全特性，支持 Bean 引用（spring-starter）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi
public interface ExpressionResolver {

    /**
     * 是否支持该表达式格式。
     *
     * @param expression 原始表达式，如 {@code #{method.name}}
     * @return true 表示支持
     */
    boolean isSupport(String expression);

    /**
     * 解析表达式。
     *
     * @param expression 原始表达式
     * @param root       根对象，表达式可访问其属性
     * @param variables  上下文变量（如 method、args 等）
     * @return 解析后的值，无法解析返回 null
     */
    String resolve(String expression, Object root, Map<String, Object> variables);

    /**
     * 解析器优先级，值越大优先尝试。
     *
     * @return 优先级，默认 0
     */
    default int getOrder() {
        return 0;
    }
}