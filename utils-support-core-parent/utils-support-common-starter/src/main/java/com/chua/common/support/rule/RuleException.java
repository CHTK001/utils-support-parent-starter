package com.chua.common.support.rule;

/**
 * 规则引擎异常。
 *
 * <p>用于表达规则定义错误、规则库冲突与规则执行期错误三类问题。
 * 与 {@link IllegalArgumentException} 区分在于：本异常代表规则引擎
 * 自身的一致性问题，而不是调用方传入参数不合法。</p>
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
     * 创建规则引擎异常。
     *
     * @param message 异常描述
     */
    public RuleException(String message) {
        super(message);
    }

    /**
     * 创建规则引擎异常。
     *
     * @param message 异常描述
     * @param cause   原始异常
     */
    public RuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
