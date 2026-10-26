package com.chua.common.support.function;

import java.util.function.Supplier;

/**
 * 安全的 Supplier 接口
 * <p>
 * 继承自 {@link Supplier}，允许在获取元素时抛出受检异常，
 * 并在发生异常时记录日志后安全地返回 null。
 * </p>
 *
 * @param <T> 提供的元素类型
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
public interface SafeSupplier<T> extends Supplier<T> {

    /**
     * 获取结果
     * <p>
     * 捕获所有异常并记录日志，如果发生异常则返回 null。
     * </p>
     *
     * @return 结果，如果发生异常则返回 null
     */
    @Override
    default T get() {
        try {
            return safeGet();
        } catch (Throwable e) {
            LogHolder.LOG.debug("[function] 安全获取异常，返回 null: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 日志持有者。
     */
    final class LogHolder {
        /**
         * 日志对象
         */
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger(SafeSupplier.class);
    }

    /**
     * 安全地获取结果，允许抛出异常
     *
     * @return 结果
     * @throws Throwable 如果获取过程中发生异常
     */
    T safeGet() throws Throwable;
}
