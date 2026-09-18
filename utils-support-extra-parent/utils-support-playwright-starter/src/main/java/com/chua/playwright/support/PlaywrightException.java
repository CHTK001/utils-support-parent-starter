package com.chua.playwright.support;

/**
* 双模式 Playwright 异常。
* @author CH
* @since 4.0.0
 */
public class PlaywrightException extends RuntimeException {

    /**
    * playwright异常。
    * @param message 消息
    */
    public PlaywrightException(String message) { super(message); }
    /**
    * playwright异常。
    * @param cause cause
    */
    public PlaywrightException(Throwable cause) { super(cause); }
    /**
    * playwright异常。
    * @param message 消息
    * @param cause cause
    */
    public PlaywrightException(String message, Throwable cause) { super(message, cause); }
}
