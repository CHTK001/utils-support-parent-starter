package com.chua.common.support.lang.loader;

import java.util.function.Supplier;

/**
* 基于 {@link Supplier} 接口的延迟加载器实现。
* <p>
* 该类用于封装一个延迟初始化逻辑，仅在首次调用 {@link #get()} 时执行 {@code supplier} 中的代码进行初始化，
* 后续调用直接返回已初始化的实例，确保线程安全且仅初始化一次。
* </p>
*
* <p>使用示例：</p>
* <pre>{@code
* // 创建延迟加载器
* SupplierLazyLoader<ExpensiveObject> loader = SupplierLazyLoader.of(() -> {
*     return new ExpensiveObject();
* });
*
* // 首次调用 get() 时触发初始化
* ExpensiveObject obj = loader.get();
* }</pre>
*
* @param <T> 被延迟加载对象的类型
* @author CH
* @version 1.0.0
* @since 2024/12/03
 */
public class SupplierLazyLoader<T> extends LazyLoader<T> {

    /**
    * 用于提供初始化实例的函数式接口。
    */
    private final Supplier<T> supplier;

    /**
    * 构造一个新的延迟加载器。
    *
    * @param supplier 必须非空，用于在需要时提供初始化实例的供应商
    * @throws IllegalArgumentException 如果 {@code supplier} 为 null
    */
    public SupplierLazyLoader(Supplier<T> supplier) {
        if (supplier == null) {
            throw new IllegalArgumentException("Supplier cannot be null");
        }
        this.supplier = supplier;
    }

    /**
    * 创建一个基于给定 {@link Supplier} 的延迟加载器实例。
    *
    * @param supplier 用于提供初始化值的供应商，不能为 null
    * @param <T>      目标对象类型
    * @return 新创建的 {@link SupplierLazyLoader} 实例
    */
    public static <T> SupplierLazyLoader<T> of(Supplier<T> supplier) {
        return new SupplierLazyLoader<>(supplier);
    }

    /**
    * 执行实际的初始化逻辑，通过调用内部持有的 {@code supplier} 获取实例。
    * 此方法由父类 {@link LazyLoader} 在首次需要时调用。
    *
    * @return 由 supplier 提供的初始化实例
    */
    @Override
    public T init() {
        return supplier.get();
    }
}
