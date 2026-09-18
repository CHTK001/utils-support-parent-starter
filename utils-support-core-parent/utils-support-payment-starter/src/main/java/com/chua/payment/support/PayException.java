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
    * 创建 薪酬异常 实例
    * @param code 编码
    * @param code 字符串
    * @param message 消息
    */
    public PayException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
    * 创建 薪酬异常 实例
    * @param message 消息
    */
    public PayException(String message) {
        this(null, message);
    }

    /**
    * 获取编码
    *
    * @return 获取编码的结果
    */
    public String getCode() {
        return code;
    }
}
