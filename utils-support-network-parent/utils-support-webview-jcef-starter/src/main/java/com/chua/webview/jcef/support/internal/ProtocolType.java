package com.chua.webview.jcef.support.internal;

/**
 * 协议类型枚举。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum ProtocolType {

    /** HTTP 协议 */
    HTTP,
    /** HTTPS 协议 */
    HTTPS,
    /** 进程间通信 */
    IPC,
    /** WebSocket 协议 */
    WEBSOCKET,
    /** TCP 协议 */
    TCP,
    UDP
}