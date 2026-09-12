package com.chua.crypto.support;

/**
* 加密模块运行时异常
*
* <p>统一承载密钥生成/派生、载体读写校验、数据加解密、配置文件加解密过程中的失败，
* 保证上层无需感知底层 JCE 异常类型。所有异常消息均为脱敏描述，不携带任何密钥材料。
*
* @author CH
* @since 2026-08-26
 */
public class CryptoException extends RuntimeException {

    /**
    * 构造异常
    *
    * @param message 脱敏后的错误描述
     */
    public CryptoException(String message) {
        super(message);
    }

    /**
    * 构造异常
    *
    * @param message 脱敏后的错误描述
    * @param cause   根因（打印前请确认不包含敏感信息）
     */
    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
