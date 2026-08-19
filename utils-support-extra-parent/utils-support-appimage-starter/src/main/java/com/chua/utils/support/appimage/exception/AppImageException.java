package com.chua.utils.support.appimage.exception;

/**
 * AppImage 自定义异常
 *
 * @author CH
 */
public class AppImageException extends RuntimeException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    public AppImageException(String message) {
        super(message);
    }

    public AppImageException(String message, Throwable cause) {
        super(message, cause);
    }

    public AppImageException(Throwable cause) {
        super(cause);
    }
}
