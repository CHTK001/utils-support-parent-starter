package com.chua.common.support.exception;

/**
 * 权限异常类。
 * <p>
 * 当用户尝试访问未授权的资源或执行未授权的操作时抛出此异常。
 * </p>
 *
 * @author CH
 */
public class NoPermissionException extends RuntimeException {

    public NoPermissionException() {
        super();
    }

    public NoPermissionException(String message) {
        super(message);
    }

    public NoPermissionException(String message, Throwable cause) {
        super(message, cause);
    }

    public NoPermissionException(Throwable cause) {
        super(cause);
    }
}
