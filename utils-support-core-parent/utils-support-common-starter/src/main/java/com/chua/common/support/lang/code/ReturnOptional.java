package com.chua.common.support.lang.code;

import lombok.Getter;
import lombok.Setter;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.NullUnmarked;

/**
 * 返回选项
 * <p>
 * 用于包装可能抛出异常的取值逻辑, 并根据条件转换为统一 {@link ReturnResult}。
 *
 * @author CH
 * @since 2024/12/29
 */
@Getter
@Setter
@NullUnmarked
@SuppressWarnings({"ALL", "unchecked"})
public class ReturnOptional<T> {

    /**
     * 取值结果
     */
    private Object result;
    /**
     * 取值过程中捕获的异常
     */
    private Exception error;
    /**
     * 结果为空时是否视为成功
     */
    private boolean nullIsSuccess;
    /**
     * 判定失败的条件(返回 true 表示失败)
     */
    private Function<T, Boolean> errorFunction;
    /**
     * 默认错误消息
     */
    private String errorMessage = "服务器异常, 请稍后重试!";
    /**
     * 成功消息
     */
    private String successMessage;
    /**
     * 判定成功的条件(返回 true 表示成功)
     */
    private Function<T, Boolean> successFunction;
    /**
     * 异常消息
     */
    private String exceptionMessage;
    /**
     * 异常消息转换函数
     */
    private Function<Exception, String> messageFunction;

    public ReturnOptional(Supplier<T> supplier) {
        if (null == supplier) {
            this.result = null;
            initialFunction();
            return;
        }
        try {
            this.result = supplier.get();
            initialFunction();
        } catch (Exception e) {
            this.error = e;
        }
    }

    /**
     * 根据结果类型初始化默认成功条件
     */
    private void initialFunction() {
        if (result instanceof Boolean) {
            this.successFunction = value -> ReturnPreconditioning.BOOLEAN.test((Boolean) value);
        } else {
            this.successFunction = value -> ReturnPreconditioning.NOT_NULL.test(value);
        }
    }

    /**
     * 允许为空
     *
     * @return ReturnOptional
     */
    public ReturnOptional<T> nullIsSuccess() {
        this.nullIsSuccess = true;
        return this;
    }

    /**
     * 设置成功消息
     *
     * @param message 成功消息文本
     * @return 返回当前对象, 支持链式调用
     */
    public ReturnOptional<T> withSuccessMessage(String message) {
        this.successMessage = message;
        return this;
    }

    /**
     * 设置成功判定条件
     *
     * @param function 成功判定函数
     * @return 返回当前对象, 支持链式调用
     */
    public ReturnOptional<T> withSuccessResult(Function<T, Boolean> function) {
        this.successFunction = function;
        return this;
    }

    /**
     * 设置异常消息
     *
     * @param message 异常消息文本
     * @return 返回当前对象, 支持链式调用
     */
    public ReturnOptional<T> withExceptionMessage(String message) {
        this.exceptionMessage = message;
        return this;
    }

    /**
     * 设置异常消息转换函数
     *
     * @param messageFunction 异常消息转换函数
     * @return 返回当前对象, 支持链式调用
     */
    public ReturnOptional<T> withExceptionMessage(Function<Exception, String> messageFunction) {
        this.messageFunction = messageFunction;
        return this;
    }

    /**
     * 设置错误消息
     *
     * @param message 错误消息文本
     * @return 返回当前对象, 支持链式调用
     */
    public ReturnOptional<T> withErrorMessage(String message) {
        this.errorMessage = message;
        return this;
    }

    /**
     * 设置失败判定条件
     *
     * @param function 失败判定函数
     * @return 返回当前对象, 支持链式调用
     */
    public ReturnOptional<T> withErrorResult(Function<T, Boolean> function) {
        this.errorFunction = function;
        return this;
    }

    /**
     * 是否成功
     *
     * @return 是否成功
     */
    public boolean isSuccessful() {
        if (null != error) {
            return false;
        }
        if (null == result) {
            return nullIsSuccess;
        }
        if (null != errorFunction) {
            return !errorFunction.apply((T) result);
        }
        if (null != successFunction) {
            return successFunction.apply((T) result);
        }
        return true;
    }

    /**
     * 转换为结果
     *
     * @return ReturnResult
     */
    public ReturnResult<T> asResult() {
        if (null != error) {
            ReturnCode returnCode = ReturnCode.fromThrowable(error);
            return ReturnResult.<T>error(returnCode.getCode(), resolveExceptionMessage());
        }
        if (isSuccessful()) {
            return ReturnResult.ok((T) result, successMessage);
        }
        return ReturnResult.error(errorMessage);
    }

    /**
     * 解析异常消息
     *
     * @return 异常消息文本
     */
    private String resolveExceptionMessage() {
        if (null != messageFunction) {
            return messageFunction.apply(error);
        }
        if (null != exceptionMessage) {
            return exceptionMessage;
        }
        return errorMessage;
    }
}
