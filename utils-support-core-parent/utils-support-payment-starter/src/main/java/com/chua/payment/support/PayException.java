package com.chua.payment.support;

/**
 * 支付异常
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PayException extends RuntimeException {

    /** 代码 */
    private final String code;

    /**
     * 创建 PayException 实例
     * @param code code
     * @param String String
     */
    public PayException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 创建 PayException 实例
     * @param message message
     */
    public PayException(String message) {
        this(null, message);
    }

    /** 获取Code */
    public String getCode() {
        return code;
    }
}
