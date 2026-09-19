package com.chua.common.support.value;

import com.chua.common.support.task.loader.Loader;
import com.chua.common.support.task.loader.SingletonLoader;

import java.io.Serial;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 懒加载值包装，是 {@link Value} 的延迟求值实现。
 *
 * <p>与 {@link DefaultValue} 的立即求值不同，{@code LazyValue} 将值的计算委托给一个
 * {@link Loader 加载器}，仅在首次 {@link #getValue()} 时触发加载并缓存结果，后续调用
 * 直接返回缓存，避免重复计算。适用于创建代价较高、但访问频率较低的值（如远程配置、
 * 重量级资源、单例对象等）。</p>
 *
 * <p>线程安全：并发加载与缓存由底层 {@link Loader} 保证（{@link SingletonLoader} 采用
 * 双重检查锁定）。{@link #isLoaded()} 与 {@link #reset()} 透传加载器状态，
 * 其中 {@link #reset()} 可清除缓存使下次 {@link #getValue()} 重新加载。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 基于加载器（可复用既有单例加载器）
 * LazyValue<DataSource> ds = LazyValue.of(loader);
 *
 * // 基于 Supplier（便捷，内部包装为线程安全的单例加载器）
 * LazyValue<DataSource> ds = LazyValue.ofSupplier(() -> createDataSource());
 *
 * // 首次取值时触发加载，之后返回缓存
 * DataSource conn = ds.getValue();
 *
 * // 核对是否已加载 / 清除缓存重新加载
 * boolean loaded = ds.isLoaded();
 * ds.reset();
 * }</pre>boolean loaded = ds.isLoaded();
 * ds.reset();
 * }</pre>
 *
 * <p>序列化说明：本类通过 {@code Value} 接口实现 {@code Serializable}，
 * 实际能否序列化取决于底层 {@code Loader} 是否可序列化；{@link SingletonLoader} 通常不可序列化。</p>
 *
 * @param <T> 值类型
 * @author CH
 * @since 4.0.0.42
 */
public final class LazyValue<T> implements Value<T> {

    @Serial
    private static final long serialVersionUID = 1L; // 串行版本uid

    /** 懒加载器，负责实际计算并缓存值 */
    private final Loader<T> loader;

    /**
    * 构造函数，包装一个 {@link Loader}。
    *
    * @param loader 懒加载器，可为 空（空 表示该值恒为 空）
    */
    private LazyValue(Loader<T> loader) {
        this.loader = loader;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建 lazy值 实例（基于自定义加载器）。
     *
     * @param loader 懒加载器，可为 空（空 表示该值恒为 空）
     * @param <T> 值类型
     * @return LazyValue 实例
     */
    public static <T> LazyValue<T> of(Loader<T> loader) {
        return new LazyValue<>(loader);
    }

    /**
     * 创建 lazy值 实例（基于 {@link Supplier}）。
     *
     * <p>内部使用 {@link SingletonLoader} 将供应商包装为线程安全的单例懒加载器，
     * 首次 {@link #getValue()} 时执行供应商，之后返回缓存结果。
     * 供应商为 空 时等价于一个恒为 空 的空值。</p>
     *
     * @param supplier 值提供者，可为 空
     * @param <T> 值类型
     * @return LazyValue 实例
     */
    public static <T> LazyValue<T> ofSupplier(Supplier<T> supplier) {
        return of(supplier == null ? null : new SingletonLoader<>(supplier));
    }

    // ==================== Value 契约 ====================

    /**
     * 获取值，首次调用时触发懒加载，之后返回缓存结果。
     *
     * @return 加载后的值，加载器为 空 或其返回 空 时均为 空
     */
    @Override
    public T getValue() {
        return loader == null ? null : loader.get();
    }

    /**
     * 始终返回 空（本实现不记录转换异常）。
     *
     * @return null
     */
    @Override
    public Throwable getThrowable() {
        return null;
    }

    /**
     * 判断加载后的值是否为 空。
     *
     * <p>会触发懒加载（若尚未加载）。当加载器为 null 或返回 null 时判定为 null。</p>
     *
     * @return true 表示加载后的值为 空
     */
    @Override
    public boolean isNull() {
        return getValue() == null;
    }

    /**
     * 判断加载后的值是否等于指定值。
     *
     * <p>会触发懒加载（若尚未加载）。</p>
     *
     * @param value 指定值，允许 空
     * @return true 表示相等
     */
    @Override
    public boolean is(T value) {
        return Objects.equals(getValue(), value);
    }

    // ==================== 加载器能力透传 ====================

    /**
     * 判断是否已加载（缓存中是否已有实例），不触发加载。
     *
     * @return true 表示已加载；加载器为 空 时恒为 false
     */
    public boolean isLoaded() {
        return loader != null && loader.isLoaded();
    }

    /**
     * 清除缓存，下次 {@link #getValue()} 时重新加载。加载器为 空 时无操作。
     */
    public void reset() {
        if (loader != null) {
            loader.reset();
        }
    }

    /**
     * 获取底层加载器。
     *
     * @return 底层 {@link Loader}，可能为 空
     */
    public Loader<T> getLoader() {
        return loader;
    }
}
