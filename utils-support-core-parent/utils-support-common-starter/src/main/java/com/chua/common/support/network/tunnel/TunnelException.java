package com.chua.common.support.network.tunnel;

import org.jspecify.annotations.NullUnmarked;

/**
 * 隧道操作异常。
 *
 * @author CH
 * @since 2026/07/31
 */
@NullUnmarked
public class TunnelException extends RuntimeException {

    public TunnelException(String message) {
        super(message);
    }

    public TunnelException(String message, Throwable cause) {
        super(message, cause);
    }
}
