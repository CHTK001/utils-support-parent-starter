package com.chua.utils.support.appimage.exception;

/**
 * AppImage 自定义异常
 *
 * @author CH
 */
public class AppImageException extends RuntimeException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 创建 AppImageException 实例
     * @param message message
     */
    public AppImageException(String message) {
        super(message);
    }

    /**
     * 创建 AppImageException 实例
     * @param message message
     * @param Throwable Throwable
     */
    public AppImageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 创建 AppImageException 实例
     * @param cause cause
     */
    public AppImageException(Throwable cause) {
        super(cause);
    }
}
