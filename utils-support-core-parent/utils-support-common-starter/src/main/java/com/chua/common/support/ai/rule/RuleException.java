package com.chua.common.support.ai.rule;

/**
 * 规则求值过程中抛出的异常。
 *
 * <p>与 {@link RuleEffect#ERROR} 分开：异常表示<b>求值过程本身</b>失败
 * （缺必需变量、函数抛错、配置非法），是调用方的 bug 或环境问题；
 * {@code ERROR} 档位是<b>业务判定结果</b>之一，是正常输出。
 * 两者混用会导致「判定不通过」和「判定过程挂了」无法区分。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RuleException extends RuntimeException {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造规则异常。
     *
     * @param message 异常信息
     */
    public RuleException(String message) {
        super(message);
    }

    /**
     * 构造规则异常。
     *
     * @param message 异常信息
     * @param cause   原始异常
     */
    public RuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
