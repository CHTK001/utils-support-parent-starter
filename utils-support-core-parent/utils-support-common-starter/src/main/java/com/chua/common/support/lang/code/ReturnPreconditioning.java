package com.chua.common.support.lang.code;

import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.ToString;

/**
 * 返回前置条件
 * <p>
 * 合并了原 condition 包(ReturnCondition / Boolean/Null/Number Condition)
 * 与 preconditioning 包(AbstractReturnPreconditioning 及各子类)的全部能力,
 * 并提供 when/then/otherwise 条件分支能力。
 * <p>
 * 内置条件以 {@link Predicate} 常量形式提供:
 * <ul>
 *   <li>{@link #NOT_NULL} 非空</li>
 *   <li>{@link #BOOLEAN}  布尔为真</li>
 *   <li>{@link #NUMBER}   数值大于 0</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@ToString
@SuppressWarnings({"ALL", "unchecked"})
public class ReturnPreconditioning<T> {

    /**
     * 非空条件
     */
    public static final Predicate<Object> NOT_NULL = data -> null != data;
    /**
     * 布尔条件(为真)
     */
    public static final Predicate<Boolean> BOOLEAN = data -> null != data && data;
    /**
     * 数值条件(大于 0)
     */
    public static final Predicate<Number> NUMBER = data -> null != data && data.intValue() > 0;

    /**
     * 数据
     */
    private final T data;
    /**
     * 成功条件
     */
    private Predicate<T> condition;
    /**
     * 成功消息
     */
    private String successMessage;
    /**
     * 失败消息
     */
    private String errorMessage = "服务器异常, 请稍后重试!";
    /**
     * 异常
     */
    private Throwable throwable;
    /**
     * 失败 code
     */
    private ResultCode errorCode;

    /**
     * 创建 ReturnPreconditioning 实例
     * @param data data
     * @param Predicate Predicate
     * @param condition condition
     */
    protected ReturnPreconditioning(T data, Predicate<T> condition) {
        this.data = data;
        this.condition = condition;
    }

    // ==================== 静态工厂 ====================

    /**
     * 基于数据自动推断条件
     *
     * @param data 数据
     * @param <T>  类型
     * @return 前置条件
     */
    public static <T> ReturnPreconditioning<T> of(T data) {
        Predicate<T> condition;
        if (data instanceof Boolean) {
            condition = (Predicate<T>) BOOLEAN;
        } else if (data instanceof Number) {
            condition = (Predicate<T>) NUMBER;
        } else {
            condition = (Predicate<T>) NOT_NULL;
        }
        return new ReturnPreconditioning<>(data, condition);
    }

    /**
     * 基于供给函数
     *
     * @param supplier 供给
     * @param <T>      类型
     * @return 前置条件
     */
    public static <T> ReturnPreconditioning<T> of(Supplier<T> supplier) {
        return of(null == supplier ? null : supplier.get());
    }

    /**
     * 基于 Callable(异步/可能抛异常)
     *
     * @param callable callable
     * @param <T>      类型
     * @return 前置条件
     */
    public static <T> ReturnPreconditioning<T> ofCallable(Callable<T> callable) {
        try {
            return of(null == callable ? null : callable.call());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 链式配置 ====================

    /**
     * 指定成功条件
     *
     * @param condition 条件
     * @return this
     */
    public ReturnPreconditioning<T> withCondition(Predicate<T> condition) {
        this.condition = condition;
        return this;
    }

    /**
     * 成功消息
     *
     * @param message 消息
     * @return this
     */
    public ReturnPreconditioning<T> withSuccessMessage(String message) {
        this.successMessage = message;
        return this;
    }

    /**
     * 失败消息
     *
     * @param message 消息
     * @return this
     */
    public ReturnPreconditioning<T> withErrorMessage(String message) {
        this.errorMessage = message;
        return this;
    }

    /**
     * 数据转换(替代原各子类的 withConverter)
     *
     * @param function 转换函数
     * @param <R>      目标类型
     * @return 新的前置条件
     */
    public <R> ReturnPreconditioning<R> withConverter(Function<T, R> function) {
        return of(function.apply(data));
    }

    /**
     * 设置异常(失败时根据异常类型自动推断 code)
     *
     * @param throwable 异常
     * @return this
     */
    public ReturnPreconditioning<T> withThrowable(Throwable throwable) {
        this.throwable = throwable;
        return this;
    }

    /**
     * 设置失败 code
     *
     * @param errorCode 状态码
     * @return this
     */
    public ReturnPreconditioning<T> withErrorCode(ResultCode errorCode) {
        this.errorCode = errorCode;
        return this;
    }

    // ==================== 分支能力 ====================

    /**
     * 条件成立时执行分支, 返回结果
     *
     * @param onSuccess 成立时的处理
     * @param onFailure 不成立时的处理
     * @param <R>       返回类型
     * @return 分支结果
     */
    public <R> R when(Function<T, R> onSuccess, Supplier<R> onFailure) {
        if (isSuccessful()) {
            return onSuccess.apply(data);
        }
        return onFailure.get();
    }

    /**
     * 条件成立时映射为结果
     *
     * @param onSuccess 成立时的处理
     * @param <R>       返回类型
     * @return 结果
     */
    public <R> ReturnResult<R> then(Function<T, R> onSuccess) {
        if (isSuccessful()) {
            return ReturnResult.ok(onSuccess.apply(data), successMessage);
        }
        return ReturnResult.error(errorMessage);
    }

    /**
     * 条件不成立时提供默认值
     *
     * @param other 默认值
     * @return 数据或默认值
     */
    public T otherwise(T other) {
        return isSuccessful() ? data : other;
    }

    /**
     * 条件不成立时提供默认值
     *
     * @param supplier 默认值供给
     * @return 数据或默认值
     */
    public T otherwiseGet(Supplier<T> supplier) {
        return isSuccessful() ? data : supplier.get();
    }

    // ==================== 结果 ====================

    /**
     * 是否成功
     *
     * @return 是否成功
     */
    public boolean isSuccessful() {
        return null != condition && condition.test(data);
    }

    /**
     * 转换为结果
     *
     * @return 结果
     */
    public ReturnResult<T> asResult() {
        if (isSuccessful()) {
            return ReturnResult.ok(data, successMessage);
        }
        if (null != throwable) {
            ReturnCode returnCode = ReturnCode.fromThrowable(throwable);
            return ReturnResult.error(returnCode.getCode(), errorMessage);
        }
        if (null != errorCode) {
            return ReturnResult.error(errorCode.getCode(), errorMessage);
        }
        return ReturnResult.error(ReturnCode.SYSTEM_SERVER_BUSINESS_ERROR.getCode(), errorMessage);
    }
}
