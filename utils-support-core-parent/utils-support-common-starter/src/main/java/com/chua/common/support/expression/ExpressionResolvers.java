package com.chua.common.support.expression;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 表达式解析器链，按优先级依次尝试所有 SPI 实现。
 *
 * <p>遍历 {@code ExpressionResolver} SPI 实现，首个 {@code isSupport} 返回 true 的解析器负责解析。
 * 解析失败返回 null，由调用方回退到占位符或字面量。</p>
 *
 * @since 4.0.0.42
 */
public final class ExpressionResolvers {

    /**
     * 解析器列表，按优先级排序（值大优先）
     */
    private static final List<ExpressionResolver> RESOLVERS;

    static {
        RESOLVERS = ServiceProvider.of(ExpressionResolver.class).collect().stream()
                .sorted(Comparator.comparingInt(ExpressionResolver::getOrder).reversed())
                .toList();
    }

    private ExpressionResolvers() {
    }

    /**
     * 解析表达式，按优先级链尝试所有解析器。
     *
     * @param expression 原始表达式，如 {@code #{method.name}}
     * @param root       根对象
     * @param variables  上下文变量
     * @return 解析后的值，无法解析返回 null
     */
    public static String resolve(String expression, Object root, Map<String, Object> variables) {
        for (ExpressionResolver resolver : RESOLVERS) {
            if (resolver.isSupport(expression)) {
                String value = resolver.resolve(expression, root, variables);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }
}