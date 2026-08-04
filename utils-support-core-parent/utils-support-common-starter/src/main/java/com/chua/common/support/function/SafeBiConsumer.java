package com.chua.common.support.function;

import java.util.function.BiConsumer;
import org.jspecify.annotations.NullUnmarked;

/**
 * 安全的 BiConsumer，捕获并忽略执行过程中的异常
 *
 * @author CH
 * @version 1.0.0
 */
@NullUnmarked
public interface SafeBiConsumer<T, U> extends BiConsumer<T, U> {
    /**
     * 消费给定的参数，内部捕获所有异常
     *
     * @param t 第一个输入参数
     * @param u 第二个输入参数
     */
    @Override
    default void accept(T t, U u) {
        try {
            safeAccept(t, u);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 安全地消费给定的参数，允许抛出 Throwable
     *
     * @param t 第一个输入参数
     * @param u 第二个输入参数
     * @throws Throwable 如果发生异常
     */
    void safeAccept(T t, U u) throws Throwable;
}