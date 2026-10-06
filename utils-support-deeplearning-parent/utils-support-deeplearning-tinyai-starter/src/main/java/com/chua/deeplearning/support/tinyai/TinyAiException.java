package com.chua.deeplearning.support.tinyai;

import java.io.Serial;
import java.io.Serializable;

/**
 * TinyAI 建模异常。
 *
 * <p>用于表达「TinyAI 训练 / 预测 / 评估流程本身出了问题」，
 * 与调用方业务异常区分开：训练数据非法、特征编码失败、
 * 引擎训练失败、模型未训练等场景均抛本异常。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TinyAiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 异常消息
     */
    public TinyAiException(String message) {
        super(message);
    }

    /**
     * 构造异常。
     *
     * @param message 异常消息
     * @param cause   根因
     */
    public TinyAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
