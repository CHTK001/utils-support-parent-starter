package com.chua.common.support.function;

import java.util.function.Consumer;

/**
 * 安全的消费者接口，继承自 {@link Consumer}。
 * 在执行消费操作时捕获并忽略所有异常。
 *
 * @param <T> 输入类型
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see Consumer
 */
public interface SafeConsumer<T> extends Consumer<T> {
    /**
     * 消费给定的参数，内部调用 {@link #safeAccept(Object)} 并捕获所有异常。
     *
     * @param t 输入参数
     */
    @Override
    default void accept(T t) {
        try {
            safeAccept(t);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 安全地消费给定的参数，允许抛出异常。
     *
     * @param t 输入参数
     * @throws Throwable 如果发生异常
     */
    void safeAccept(T t) throws Throwable;
}
