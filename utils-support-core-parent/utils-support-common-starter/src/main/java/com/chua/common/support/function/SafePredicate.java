package com.chua.common.support.function;

import java.util.function.Predicate;

/**
* 安全的断言接口
* <p>在执行测试时捕获所有异常，并在发生异常时返回 {@code false}。</p>
*
* @param <T> 输入类型
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
public interface SafePredicate<T> extends Predicate<T> {

    /**
    * 对给定的参数进行断言测试。
    * <p>内部调用 {@link #safeTest(Object)}，并捕获所有 {@link Throwable}，
    * 如果发生异常则返回 {@code false}。</p>
    *
    * @param t 输入参数
    * @return 如果输入参数匹配断言条件则返回 {@code true}，否则或发生异常时返回 {@code false}
    */
    @Override
    default boolean test(T t) {
        try {
            return safeTest(t);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
    * 实际执行断言测试的方法，允许抛出受检异常。
    *
    * @param t 输入参数
    * @return 如果输入参数匹配断言条件则返回 {@code true}，否则返回 {@code false}
    * @throws Throwable 允许抛出任何异常
    */
    boolean safeTest(T t) throws Throwable;
}
