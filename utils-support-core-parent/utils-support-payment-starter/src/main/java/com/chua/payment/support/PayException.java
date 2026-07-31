package com.chua.payment.support;

/**
 * 支付异常
 *
 * @author CH
 * @since 2026/07/19
 */
public class PayException extends RuntimeException {

    private final String code;

    public PayException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PayException(String message) {
        this(null, message);
    }

    public String getCode() {
        return code;
    }
}
