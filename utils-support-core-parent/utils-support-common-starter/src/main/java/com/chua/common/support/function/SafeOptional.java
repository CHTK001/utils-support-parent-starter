package com.chua.common.support.function;

import com.chua.common.support.utils.ClassUtils;

import java.util.function.Function;
import java.util.function.Supplier;

/**
* 安全可选对象，用于链式条件判断与值转换。
* 类似于 Optional，但支持 if-else 逻辑分支。
*
* @param <T> 输入参数类型
* @param <R> 返回值类型
* @author CH
* @since 4.0.0.42
 */
public class SafeOptional<T, R> {

    private static final SafeOptional<?, ?> EMPTY = new SafeOptional<>(false, null);
    
    /**
    * 标识当前是否已获取到有效值（true表示已匹配/有值，false表示空/未匹配）
    */
    private final boolean isNone;
    
    /**
    * 存储转换后的结果值
    */
    private final R apply;

    /**
    * 创建 SafeOptional 实例
    * @param isNone isNone
    * @param R R
    */
    public SafeOptional(boolean isNone, R apply) {
        this.isNone = isNone;
        this.apply = apply;
    }

    /**
    * 获取结果值
    *
    * @return 结果值，如果未匹配则返回 null
    */
    public R get() {
        return apply;
    }

    /**
    * else 分支：如果当前已获取到有效值，则直接返回当前实例；
    * 否则，使用提供的值和函数进行计算并返回新的 SafeOptional。
    *
    * @param value    输入值
    * @param function 转换函数
    * @return 新的 SafeOptional 实例
    */
    public SafeOptional<T, R> elseCapable(T value, Function<T, R> function) {
        if (isNone) {
            return new SafeOptional<>(true, apply);
        }
        return ifCapable(value, function);
    }

    /**
    * else 分支（带条件）：如果当前已获取到有效值，则直接返回当前实例；
    * 否则，根据条件判断是否使用提供的值和函数进行计算。
    *
    * @param condition 条件判断
    * @param value     输入值
    * @param function  转换函数
    * @return 新的 SafeOptional 实例
    */
    public SafeOptional<T, R> elseCapable(boolean condition, T value, Function<T, R> function) {
        if (isNone) {
            return new SafeOptional<>(true, apply);
        }
        if (!condition) {
            return (SafeOptional<T, R>) EMPTY;
        }
        return ifCapable(value, function);
    }

    /**
    * else 分支（Supplier）：如果当前已获取到有效值，则直接返回当前实例；
    * 否则，使用 Supplier 获取结果并返回新的 SafeOptional。
    *
    * @param function 结果提供者
    * @return 新的 SafeOptional 实例
    */
    public SafeOptional<T, R> elseCapable(Supplier<R> function) {
        if (isNone) {
            return new SafeOptional<>(true, apply);
        }
        return new SafeOptional<>(true, function.get());
    }

    /**
    * if 分支：如果输入值为空或 Void，则返回空的 SafeOptional；
    * 否则，使用函数转换值并返回包含结果的 SafeOptional。
    *
    * @param value    输入值
    * @param function 转换函数
    * @param <T>      输入类型
    * @param <R>      返回类型
    * @return SafeOptional 实例
    */
    public static <T, R> SafeOptional<T, R> ifCapable(T value, Function<T, R> function) {
        if (null == value || ClassUtils.isVoid(value)) {
            return (SafeOptional<T, R>) SafeOptional.EMPTY;
        }

        return new SafeOptional<T, R>(true, function.apply(value));
    }

    /**
    * if 分支（布尔值）：如果条件为 false，则返回空的 SafeOptional；
    * 否则，使用函数转换条件值并返回包含结果的 SafeOptional。
    *
    * @param value    布尔条件
    * @param function 转换函数
    * @param <R>      返回类型
    * @return SafeOptional 实例
    */
    public static <R> SafeOptional<Boolean, R> ifCapable(boolean value, Function<Boolean, R> function) {
        if (!value) {
            return (SafeOptional<Boolean, R>) SafeOptional.EMPTY;
        }

        return new SafeOptional<Boolean, R>(value, function.apply(value));
    }
}
