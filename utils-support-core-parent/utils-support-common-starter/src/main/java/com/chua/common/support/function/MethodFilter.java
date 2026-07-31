package com.chua.common.support.function;

import java.lang.reflect.Method;

/**
 * 方法过滤器接口，用于根据特定条件筛选方法。
 *
 * @author CH
 */
public interface MethodFilter {

    /**
     * 预定义的方法过滤器：匹配用户声明的方法。
     * <p>排除桥接方法、合成方法以及 {@link Object} 类中声明的方法。
     */
    MethodFilter USER_DECLARED_METHODS =
            (method -> !method.isBridge() && !method.isSynthetic() && (method.getDeclaringClass() != Object.class));

    /**
     * 判断给定的方法是否匹配此过滤器。
     *
     * @param method 要检查的方法
     * @return 如果方法匹配则返回 {@code true}，否则返回 {@code false}
     */
    boolean matches(Method method);

    /**
     * 基于当前过滤器和提供的过滤器创建一个组合过滤器（逻辑与）。
     * <p>如果当前过滤器不匹配，则不会应用下一个过滤器。
     *
     * @param next 下一个 {@code MethodFilter}
     * @return 一个组合的 {@code MethodFilter}
     * @throws IllegalArgumentException 如果 MethodFilter 参数为 {@code null}
     * @since 5.3.2
     */
    default MethodFilter and(MethodFilter next) {
        return method -> matches(method) && next.matches(method);
    }
}