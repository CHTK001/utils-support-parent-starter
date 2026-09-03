package com.chua.deeplearning.support.dl4j;

/**
 * 训练过程运行时异常。
 *
 * <p>包装底层受检异常，便于在回调/线程/表达式等不便于声明 {@code throws} 的场景下
 * 以运行时异常形式抛出。保留原始 cause 以便排查。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TrainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 创建异常。
     *
     * @param message 错误信息
     */
    public TrainException(String message) {
        super(message);
    }

    /**
     * 创建异常。
     *
     * @param message 错误信息
     * @param cause   原始原因
     */
    public TrainException(String message, Throwable cause) {
        super(message, cause);
    }
}
