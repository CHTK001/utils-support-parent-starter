package com.chua.playwright.support;

/**
 * 双模式 Playwright 异常。
 */
public class PlaywrightException extends RuntimeException {

    public PlaywrightException(String message) { super(message); }
    public PlaywrightException(Throwable cause) { super(cause); }
    public PlaywrightException(String message, Throwable cause) { super(message, cause); }
}