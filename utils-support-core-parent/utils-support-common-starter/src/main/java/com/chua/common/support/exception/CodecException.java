package com.chua.common.support.exception;

/**
 * 编解码异常。
 * <p>当编码器或解码器在原生层、参数校验或数据格式上失败时抛出此异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CodecException extends RuntimeException {

    /**
     * 构造一个不带详细消息的 {@code CodecException}。
     */
    public CodecException() {
        super();
    }

    /**
     * 构造一个带有详细错误消息的 {@code CodecException}。
     *
     * @param message 描述异常原因的错误消息。
     */
    public CodecException(String message) {
        super(message);
    }

    /**
     * 构造一个带有格式化错误消息的 {@code CodecException}。
     *
     * @param message 错误消息模板，使用 {@link String#format} 语法。
     * @param args    模板参数。
     */
    public CodecException(String message, Object... args) {
        super(args == null || args.length == 0 ? message : String.format(message, args));
    }

    /**
     * 构造一个带有错误消息和根因异常的 {@code CodecException}。
     *
     * @param message 描述异常原因的错误消息。
     * @param cause   根因异常。
     */
    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造一个仅包含根因异常的 {@code CodecException}。
     *
     * @param cause 根因异常。
     */
    public CodecException(Throwable cause) {
        super(cause);
    }
}
