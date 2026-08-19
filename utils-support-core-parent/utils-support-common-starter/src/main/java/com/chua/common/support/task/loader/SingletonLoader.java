package com.chua.common.support.task.loader;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 基于 {@link Supplier} 的单例懒加载器实现。
 *
 * <p>通过 {@link #of(Supplier)} 工厂方法快速创建，适用于无需继承
 * {@link AbstractLoaderProvider} 的场景。例如：
 *
 * <pre>{@code
 * SingletonLoader<DataSource> loader = SingletonLoader.of(() -> new HikariDataSource(config));
 * DataSource ds = loader.get();
 * }</pre>
 * </p>
 *
 * @param <T> 被加载的对象类型
 * @author CH
 * @since 2026/07/18
 */
public class SingletonLoader<T> extends AbstractLoaderProvider<T> {

    /** 供应商 */
    private final Supplier<T> supplier;

    /**
     * 构造函数。
     *
     * @param supplier 实例创建供应商，不能为空
     */
    public SingletonLoader(Supplier<T> supplier) {
        this.supplier = Objects.requireNonNull(supplier, "supplier 不能为空");
    }

    @Override
    /** 创建 */
    protected T create() {
        return supplier.get();
    }

    /**
     * 创建单例懒加载器。
     *
     * @param <T>      被加载的对象类型
     * @param supplier 实例创建供应商
     * @return SingletonLoader 实例
     */
    public static <T> SingletonLoader<T> of(Supplier<T> supplier) {
        return new SingletonLoader<>(supplier);
    }
}
