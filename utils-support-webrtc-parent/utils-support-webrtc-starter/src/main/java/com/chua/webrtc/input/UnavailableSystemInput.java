package com.chua.webrtc.input;

/**
 * 系统-level 限制（例如 Robot 不可用）时抛出的异常。
 *
 * @author CH
 * @since 4.0.0.50
 */
public class UnavailableSystemInput extends RuntimeException {

    public UnavailableSystemInput(String message) {
        super(message);
    }

    public UnavailableSystemInput(String message, Throwable cause) {
        super(message, cause);
    }

    public UnavailableSystemInput(Throwable cause) {
        super(cause);
    }
}
