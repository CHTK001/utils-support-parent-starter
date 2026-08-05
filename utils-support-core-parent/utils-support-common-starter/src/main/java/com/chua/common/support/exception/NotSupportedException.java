package com.chua.common.support.exception;


import com.chua.common.support.utils.StringUtils;

/**
 * 不支持的操作异常。
 * <p>当执行的操作不被当前环境、实现或配置支持时抛出此异常。</p>
 *
 * @author CH
 */
public class NotSupportedException extends RuntimeException {

    /**
     * 构造一个不带详细消息的 {@code NotSupportedException}。
     */
    public NotSupportedException() {
        super();
    }

    /**
     * 构造一个带有详细错误消息的 {@code NotSupportedException}。
     *
     * @param message 描述异常原因的错误消息。
     */
    public NotSupportedException(String message) {
        super(message);
    }

    /**
     * 构造一个使用格式化字符串和参数生成详细错误消息的 {@code NotSupportedException}。
     * <p>该方法利用 {@link StringUtils#format(String, Object...)} 进行消息格式化。</p>
     *
     * @param message 包含占位符（如 "{}"）的错误消息模板。
     * @param args    用于替换消息模板中占位符的参数数组。
     */
    public NotSupportedException(String message, Object... args) {
        super(StringUtils.format(message, args));
    }

    /**
     * 构造一个带有详细错误消息和根本原因（cause）的 {@code NotSupportedException}。
     *
     * @param message 描述异常原因的错误消息。
     * @param cause   导致此异常的底层原因（通常由其他异常封装而来）。
     */
    public NotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
