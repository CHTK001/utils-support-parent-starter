package com.chua.auth.support;

/**
 * 登录异常
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LoginException extends RuntimeException {

    /** 错误码 */
    private final String code;

    /**
     * 创建 LoginException 实例
     * @param code code
     * @param String String
     */
    public LoginException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 创建 LoginException 实例
     * @param message message
     */
    public LoginException(String message) {
        this(null, message);
    }

    /** 获取Code */
    public String getCode() {
        return code;
    }
}
