package com.chua.common.support.exception;


/**
 * 权限异常类。
 * <p>
 * 当用户尝试访问未授权的资源或执行未授权的操作时抛出此异常。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NoPermissionException extends RuntimeException {

    /** 创建 NoPermissionException 实例 */
    public NoPermissionException() {
        super();
    }

    /**
    * 创建 NoPermissionException 实例
    * @param message message
    */
    public NoPermissionException(String message) {
        super(message);
    }

    /**
     * 创建 NoPermissionException 实例
     * @param message message
     * @param Throwable Throwable
     * @param cause 方法入参 cause
     */
    public NoPermissionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 创建 NoPermissionException 实例
     * @param cause cause
     */
    public NoPermissionException(Throwable cause) {
        super(cause);
    }
}
