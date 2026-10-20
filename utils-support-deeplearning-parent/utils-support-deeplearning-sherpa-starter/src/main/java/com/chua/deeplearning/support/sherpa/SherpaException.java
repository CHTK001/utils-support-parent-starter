package com.chua.deeplearning.support.sherpa;

/**
 * sherpa-onnx 集成异常。
 *
 * <p>用于表达「原生库缺失」「模型目录结构不符」「推理失败」等本模块特有的失败原因，
 * 避免向上抛出裸 {@link UnsatisfiedLinkError} 或 {@link IllegalArgumentException}，
 * 调用方可以按需捕获并降级到其它 ASR / TTS 引擎。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SherpaException extends RuntimeException {

    /**
     * 以消息构造异常。
     *
     * @param message 异常消息
     */
    public SherpaException(String message) {
        super(message);
    }

    /**
     * 以消息与根因构造异常。
     *
     * @param message 异常消息
     * @param cause   根因
     */
    public SherpaException(String message, Throwable cause) {
        super(message, cause);
    }
}
