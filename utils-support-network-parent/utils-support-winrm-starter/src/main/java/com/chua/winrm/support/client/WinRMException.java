package com.chua.winrm.support.client;

/**
* winrm 操作异常。
*
* @author CH
* @since 4.0.0.42
 */
public class WinRMException extends RuntimeException {

    /**
    * 创建 winrm异常 实例
    * @param message 消息
    * @param cause Throwable
    * @param cause cause
    */
    public WinRMException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
    * 创建 winrm异常 实例
    * @param message 消息
    */
    public WinRMException(String message) {
        super(message);
    }
}
