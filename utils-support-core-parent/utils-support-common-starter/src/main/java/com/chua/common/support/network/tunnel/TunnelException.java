package com.chua.common.support.network.tunnel;


/**
 * 隧道操作异常。
 *
 * @author CH
 * @since 2026/07/31
*/
public class TunnelException extends RuntimeException {

    /**
    * 创建 TunnelException 实例
    * @param message message
    */
    public TunnelException(String message) {
        super(message);
    }

    /**
    * 创建 TunnelException 实例
    * @param message message
    * @param Throwable Throwable
    */
    public TunnelException(String message, Throwable cause) {
        super(message, cause);
    }
}
