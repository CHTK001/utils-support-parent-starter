package com.chua.common.support.network.syslog;

/**
 * Syslog 传输协议。
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum SyslogTransport {

    /**
     * UDP 传输（RFC 5426）：无连接、fire-and-forget，标准端口 514。
     * 常规日志收集的首选，无阻塞、开销最低；极端情况下可能丢包。
     */
    UDP,

    /**
     * TCP 传输（RFC 6587）：面向连接、逐行分帧，不丢包但引入建连/背压开销。
     * 适合对可靠性要求更高的链路。
     */
    TCP
}
