package com.chua.common.support.network.tunnel;


/**
 * 隧道操作异常。
 *
 * @author CH
 * @since 2026/07/31
 */
public class TunnelException extends RuntimeException {

    public TunnelException(String message) {
        super(message);
    }

    public TunnelException(String message, Throwable cause) {
        super(message, cause);
    }
}
