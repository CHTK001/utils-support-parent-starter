package com.chua.common.support.export;

/**
 * 导出异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ExportException extends RuntimeException {

    /**
     * 序列版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造导出异常。
     *
     * @param message 异常信息
     */
    public ExportException(String message) {
        super(message);
    }

    /**
     * 构造导出异常。
     *
     * @param message 异常信息
     * @param cause   根因
     */
    public ExportException(String message, Throwable cause) {
        super(message, cause);
    }
}