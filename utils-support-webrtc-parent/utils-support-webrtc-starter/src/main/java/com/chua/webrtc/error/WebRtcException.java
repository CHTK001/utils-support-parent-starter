package com.chua.webrtc.error;

/**
 * WebRTC Server 运行时异常。
 *
 * @author CH
 * @since 4.0.0.50
 */
public class WebRtcException extends RuntimeException {

    public WebRtcException(String message) {
        super(message);
    }

    public WebRtcException(String message, Throwable cause) {
        super(message, cause);
    }

    public WebRtcException(Throwable cause) {
        super(cause);
    }
}
