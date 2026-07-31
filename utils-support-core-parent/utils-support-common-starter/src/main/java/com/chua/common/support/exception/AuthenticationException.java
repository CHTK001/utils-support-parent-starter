package com.chua.common.support.exception;

/**
 * 认证异常类。
 * <p>
 * 当用户身份验证失败或认证过程中发生错误时抛出此异常。
 * 例如：登录凭证无效、令牌过期、权限不足等场景。
 * </p>
 *
 * @author CH
 */
public class AuthenticationException extends RuntimeException {

    /**
     * 默认构造函数，创建一个没有详细消息的认证异常。
     */
    public AuthenticationException() {
        super();
    }

    /**
     * 使用指定的详细消息创建认证异常。
     *
     * @param message 描述异常原因的详细信息
     */
    public AuthenticationException(String message) {
        super(message);
    }

    /**
     * 使用指定的详细消息和导致此异常的 Cause（根本原因）创建认证异常。
     *
     * @param message 描述异常原因的详细信息
     * @param cause   引起此异常的 Throwable 对象（通常用于记录底层错误）
     */
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用指定的 Cause（根本原因）创建认证异常。
     *
     * @param cause 引起此异常的 Throwable 对象
     */
    public AuthenticationException(Throwable cause) {
        super(cause);
    }

    /**
     * 使用指定的详细消息、Cause、是否允许抑制异常以及堆栈跟踪是否可写来创建认证异常。
     * <p>
     * 此构造函数主要用于高级场景，允许控制异常的抑制行为和堆栈跟踪生成。
     * </p>
     *
     * @param message               描述异常原因的详细信息
     * @param cause                 引起此异常的 Throwable 对象
     * @param enableSuppression     指定是否启用异常抑制（即是否允许调用 suppressedExceptions）
     * @param writableStackTrace    指定是否生成可写的堆栈跟踪（如果为 false，则不生成堆栈信息以节省性能）
     */
    public AuthenticationException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
