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

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
    * 以错误信息创建 Weka 运行时异常。
    *
    * @param message 错误信息描述，可为 空（等价于无消息异常）
    */
    public WekaException(String message) {
        super(message);
    }

    /**
    * 以错误信息与原始原因创建 Weka 运行时异常（用于包装 Weka 底层受检异常，保留 cause 便于排查）。
    *
    * @param message 错误信息描述，可为 空（等价于无消息异常）
    * @param cause   原始异常原因（如 Weka 底层受检异常），可为 空
    */
    public WekaException(String message, Throwable cause) {
        super(message, cause);
    }
}
