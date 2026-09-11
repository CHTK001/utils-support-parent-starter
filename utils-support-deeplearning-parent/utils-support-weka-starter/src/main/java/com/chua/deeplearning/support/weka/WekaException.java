package com.chua.deeplearning.support.weka;

/**
 * Weka 建模运行时异常。
 *
 * <p>包装 Weka 底层受检异常（训练、预测、评估、文件读写等），
 * 便于在调用方以运行时异常形式处理，保留原始 cause 以便排查。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WekaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 创建异常。
     *
     * @param message 错误信息
     */
    public WekaException(String message) {
        super(message);
    }

    /**
     * 创建异常。
     *
     * @param message 错误信息
     * @param cause   原始原因
     */
    public WekaException(String message, Throwable cause) {
        super(message, cause);
    }
}
