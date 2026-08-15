package com.chua.common.support.expression.impl;

import com.chua.common.support.expression.ExpressionResolver;
import com.chua.common.support.spi.annotations.Spi;

import java.util.Map;

/**
 * 默认表达式解析器，处理 {@code #{...}} 中的简单变量访问。
 *
 * <p>支持格式：</p>
 * <ul>
 *   <li>{@code #{变量名}} — 从 root 或 variables 中取值</li>
 *   <li>{@code #{this}} — 返回根对象的字符串表示</li>
 *   <li>{@code #{} 包裹的其他内容} — 提取内部内容直接返回</li>
 * </ul>
 *
 * <p>复杂表达式（方法调用、三目运算、Bean 引用）由高优先级解析器
 * （如 Spring SpEL 实现）负责。本实现作为兜底，保证非 Spring 环境可用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = "default", order = -100)
public class DefaultExpressionResolver implements ExpressionResolver {

    /**
     * SpEL 表达式前缀标识
     */
    private static final String PREFIX = "#{";

    /**
     * SpEL 表达式后缀标识
     */
    private static final String SUFFIX = "}";

    @Override
    public boolean isSupport(String expression) {
        return expression != null && expression.startsWith(PREFIX) && expression.endsWith(SUFFIX);
    }

    @Override
    public String resolve(String expression, Object root, Map<String, Object> variables) {
        if (expression == null) {
            return null;
        }
        // 提取 #{ } 包裹的内部内容
        String inner = expression.substring(PREFIX.length(), expression.length() - SUFFIX.length());
        if (inner.isEmpty()) {
            return null;
        }
        // 表达式为 this 时返回根对象
        if ("this".equals(inner)) {
            return root != null ? root.toString() : null;
        }
        // 表达式为变量名时优先从上下文变量取值
        if (variables != null && variables.containsKey(inner)) {
            Object v = variables.get(inner);
            return v != null ? v.toString() : null;
        }
        return inner;
    }
}