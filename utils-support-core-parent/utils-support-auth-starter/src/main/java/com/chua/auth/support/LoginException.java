package com.chua.auth.support;

/**
 * 登录异常
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LoginException extends RuntimeException {

    /** 错误码 */
    /** 代码 */
    private final String code;

    public LoginException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LoginException(String message) {
        this(null, message);
    }

    public String getCode() {
        return code;
    }
}
