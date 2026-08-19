package com.chua.winrm.support.client;

/**
 * WinRM 操作异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WinRMException extends RuntimeException {

    /**
     * 创建 WinRMException 实例
     * @param message message
     * @param Throwable Throwable
     */
    public WinRMException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 创建 WinRMException 实例
     * @param message message
     */
    public WinRMException(String message) {
        super(message);
    }
}
