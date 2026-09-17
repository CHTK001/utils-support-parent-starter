package com.chua.common.support.value;

import java.io.Serial;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 带过期时间的值包装，是 {@link Value} 的有状态实现。
 *
 * <p>为值附加一个存活时间（TTL），过期后 {@link #getValue()} 不再返回旧值，
 * 并按以下优先级处理（{@code 过期回调 > 重新加载 > 清除}）：</p>
 * <ol>
 *   <li>注册了过期回调（有返回值）时，按其返回值处理：返回 null 表示清除，返回新值表示替换并重新计时</li>
 *   <li>未注册回调但注册了 {@link #loader(Supplier)} 时，从加载器重新加载新值并重新计时；加载器返回 null 则清除</li>
 *   <li>两者均未注册时，直接清除（值变为 null）</li>
 * </ol>
 *
 * <p>过期回调提供两种形式：</p>
 * <ul>
 *   <li>{@link #onExpire(Function)}：有返回值，return null 清除，return 新值 替换</li>
 *   <li>{@link #onExpireNotify(java.util.function.Consumer)}：无返回值通知，内部组合为有返回值回调并 return null，即通知后清除</li>
 * </ul>
 *
 * <p>线程安全：内部状态读写通过实例监视器同步，可在多线程间共享。</p>
 *
 * <p>序列化说明：回调与加载器字段为 transient，注册了回调/加载器的实例参与序列化时，
 * 反序列化后两者重置为 空（过期行为退化为清除），值与过期时间戳可正常保留。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 基础：30 秒后过期，过期值为 null
 * ExpireValue<String> value = ExpireValue.of("data", Duration.ofSeconds(30));
 *
 * // 过期重新加载
 * ExpireValue<Config> config = ExpireValue.of(load(), Duration.ofMinutes(10))
 *     .loader(Config::load)
 *     .onExpire(old -> {
 *         log.info("配置过期: {}", old);
 *         return null;
 *     });
 *
 * // 手动刷新有效期
 * config.refresh();
 *
 * // 核对是否过期（防止传入的过期时间不准或提前过期）
 * if (config.isExpire()) {
 *     config.refresh();
 * }
 * }</pre>）
 * if (配置.是否expire()) {
 * 配置.refresh();
 * }
 * }</pre>
 *
 * @param <T> 值类型
 * @author CH
 * @since 4.0.0.42
*/
public class ExpireValue<T> implements Value<T> {

    @Serial
    private static final long serialVersionUID = 1L; // 串行版本uid

    /** 未设置过期时间的时间戳（值为 空 或已清除时） */
    private static final long UNSET_EXPIRE_AT = 0L;

    /** 永不过期的过期时间戳（ttl 为 空 时使用） */
    private static final long NEVER_EXPIRE_AT = Long.MAX_VALUE;

    /** 当前值，清除后为 空 */
    private volatile T value;
    /** 过期时间戳（毫秒），未设置时为 0 */
    private volatile long expireAt;
    /** 过期回调（有返回值）：返回 空 清除，返回 新值 替换并重新计时 */
    private volatile transient Function<? super T, ? extends T> expireCallback;
    /** 重新加载提供者，过期或手动刷新时从此处加载新值 */
    private volatile transient Supplier<? extends T> loader;
    /** 存活时间 */
    private final Duration ttl;

    /**
    * 构造函数，通过工厂方法 {@link #of(Object, Duration)} 创建实例。
    *
    * @param value 初始值，允许 空（空 表示初始即为已清除状态）
    * @param ttl 存活时间，可为 空（空 表示永不过期）
    */
    private ExpireValue(T value, Duration ttl) {
        this.value = value;
        this.ttl = ttl;
        this.expireAt = value == null ? UNSET_EXPIRE_AT : nextExpireAt();
    }

    /**
    * 计算下一次过期时间戳：ttl 为 空 时永不过期。
    *
    * @return 过期时间戳（毫秒），或 {@link #NEVER_EXPIRE_AT}
    */
    private long nextExpireAt() {
        return ttl == null ? NEVER_EXPIRE_AT : System.currentTimeMillis() + ttl.toMillis();
    }

    // ==================== 工厂方法 ====================

    /**
    * 创建 expire值 实例。
    *
    * @param value 初始值，允许 空（空 表示初始即为已清除状态）
    * @param ttl 存活时间，可为 空（空 表示永不过期），不允许为负
    * @param <T> 值类型
    * @return ExpireValue 实例
    * @throws IllegalArgumentException ttl 为负时
    */
    public static <T> ExpireValue<T> of(T value, Duration ttl) {
        if (ttl != null && ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 不能为负: " + ttl);
        }
        return new ExpireValue<>(value, ttl);
    }

    /**
    * 创建 expire值 实例（按时间单位）。
    *
    * @param value 初始值，允许 空（空 表示初始即为已清除状态）
    * @param amount 存活时长数值，不允许为负
    * @param unit 存活时长单位，可为 空（空 表示永不过期）
    * @param <T> 值类型
    * @return ExpireValue 实例
    * @throws IllegalArgumentException amount 为负时
    */
    public static <T> ExpireValue<T> of(T value, long amount, TimeUnit unit) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount 不能为负: " + amount);
        }
        return of(value, unit == null ? null : Duration.of(amount, unit.toChronoUnit()));
    }

    // ==================== 过期处理 ====================

    /**
    * 获取值，过期时自动处理。
    *
    * <p>未过期直接返回值；已过期时按优先级处理：
    * 过期回调（返回 空 清除，返回 新值 替换并重新计时）>
    * 重新加载（从 {@link #loader(Supplier)} 加载并重新计时）> 清除（值变为 空）。</p>
    *
    * @return 当前值，清除后返回 空
    */
    @Override
    public T getValue() {
        T current = value;
        if (current == null) {
            return null;
        }
        if (System.currentTimeMillis() < expireAt) {
            return current;
        }
        synchronized (this) {
            current = value;
            if (current == null) {
                return null;
            }
            if (System.currentTimeMillis() < expireAt) {
                return current;
            }
            value = handleExpired(current);
            return value;
        }
    }

    /**
    * 过期统一处理（调用方必须已持有实例监视器）。
    *
    * <p>按 过期回调 > 重新加载 > 清除 的优先级处理过期值。</p>
    *
    * @param expired 已过期的旧值
    * @return 处理后的新值（null 表示已清除）
    */
    private T handleExpired(T expired) {
        var now = System.currentTimeMillis();
        var callback = expireCallback;
        if (callback != null) {
            T replaced = callback.apply(expired);
            if (replaced == null) {
                return null;
            }
            expireAt = ttl == null ? NEVER_EXPIRE_AT : now + ttl.toMillis();
            return replaced;
        }
        var reload = loader;
        if (reload != null) {
            T loaded = reload.get();
            if (loaded == null) {
                return null;
            }
            expireAt = ttl == null ? NEVER_EXPIRE_AT : now + ttl.toMillis();
            return loaded;
        }
        return null;
    }

    /**
    * 注册过期回调（有返回值形式）。
    *
    * <p>{@link #getValue()} 检测到过期时自动触发：
    * 回调返回 空 表示清除值，返回新值表示替换旧值并按 TTL 重新计时。</p>
    *
    * @param callback 回调，入参为过期的旧值；返回值 空 清除、非 空 替换；不能为 空
    * @return 当前实例（链式调用）
    * @throws NullPointerException callback 为 空 时
    */
    public ExpireValue<T> onExpire(Function<? super T, ? extends T> callback) {
        if (callback != null) {
            this.expireCallback = callback;
        }
        return this;
    }

    /**
    * 注册过期通知（无返回值形式）。
    *
    * <p>内部组合为有返回值回调并 return null，即通知完成后清除值；
    * 与 {@link #onExpire(Function)} 互斥，后调用者覆盖先调用者。</p>
    *
    * @param listener 通知，入参为过期的旧值；不能为 空
    * @return 当前实例（链式调用）
    * @throws NullPointerException 监听器 为 空 时
    */
    public ExpireValue<T> onExpireNotify(Consumer<? super T> listener) {
        if (listener == null) {
            return this;
        }
        return onExpire(expired -> {
            listener.accept(expired);
            return null;
        });
    }

    /**
    * 注册重新加载提供者。
    *
    * <p>设置了加载器后，过期（且未注册过期回调）时会从此处重新加载新值并按 TTL 重新计时；
    * 加载器返回 空 则清除。同时供 {@link #refresh()} 手动重新加载使用。</p>
    *
    * @param loader 重新加载提供者，不能为 空
    * @return 当前实例（链式调用）
    * @throws NullPointerException 加载 为 空 时
    */
    public ExpireValue<T> loader(Supplier<? extends T> loader) {
        if (loader != null) {
            this.loader = loader;
        }
        return this;
    }

    // ==================== 刷新与核对 ====================

    /**
    * 手动刷新。
    *
    * <p>注册了重新加载提供者时先从加载器刷新值（加载器返回 null 时保留旧值），
    * 然后按 TTL 延长有效期。未过期时等同于续期；已清除的值不会被复活，仅重置过期标记。</p>
    *
    * @return 当前实例（链式调用）
    */
    public ExpireValue<T> refresh() {
        synchronized (this) {
            var reload = loader;
            if (reload != null) {
                T loaded = reload.get();
                if (loaded != null) {
                    value = loaded;
                }
            }
            if (value == null) {
                expireAt = UNSET_EXPIRE_AT;
            } else {
                expireAt = nextExpireAt();
            }
            return this;
        }
    }

    /**
    * 核对是否已过期。
    *
    * <p>纯核对，不触发任何过期回调与重新加载逻辑。
    * 用于在取值前核实实际状态，防止传入的过期时间不准或值被提前清除的情况，
    * 核对结果为已过期时调用方可先 {@link #refresh()} 再取值。</p>
    *
    * @return true 表示值已清除或当前时间已到达/超过过期时间
    */
    public boolean isExpire() {
        return value == null || System.currentTimeMillis() >= expireAt;
    }

    /**
    * 原始读取当前值（纯读取，不触发任何过期处理逻辑）。
    *
    * <p>与 {@link #getValue()} 的区别：本方法不处理过期，
    * 用于核对与拷贝场景；需要过期自愈行为请使用 {@link #getValue()}。</p>
    *
    * @return 当前原始值，清除后为 空
    */
    public T peek() {
        return value;
    }

    /**
    * 获取过期时间戳（毫秒）。
    *
    * @return 过期时间戳，值为 空 或已清除时为 0
    */
    public long expireAt() {
        return expireAt;
    }

    /**
    * 获取存活时间。
    *
    * @return 存活时间，可为 空（空 表示永不过期）
    */
    public Duration ttl() {
        return ttl;
    }

    // ==================== Value 契约 ====================

    /**
    * 获取转换过程中产生的异常。
    *
    * @return 始终返回 空（本实现不记录转换异常）
    */
    @Override
    public Throwable getThrowable() {
        return null;
    }

    /**
    * 判断当前值是否为 空（原始读取，不触发过期处理）。
    *
    * @return true 表示值已清除或从未设置
    */
    @Override
    public boolean isNull() {
        return value == null;
    }

    /**
    * 判断当前值是否等于指定值（原始读取，不触发过期处理）。
    *
    * @param target 指定值，允许 空
    * @return true 表示相等（空 与 空 相等）
    */
    @Override
    public boolean is(T target) {
        var current = value;
        return current == target || (current != null && current.equals(target));
    }
}
