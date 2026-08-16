package com.chua.winrm.support.client;

/**
 * WinRM 操作异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WinRMException extends RuntimeException {

    public WinRMException(String message, Throwable cause) {
        super(message, cause);
    }

    public WinRMException(String message) {
        super(message);
    }
}
