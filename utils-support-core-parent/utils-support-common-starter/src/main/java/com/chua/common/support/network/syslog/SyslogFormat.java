package com.chua.common.support.network.syslog;

/**
 * Syslog 报文格式。
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum SyslogFormat {

    /**
     * RFC 3164（BSD 旧格式）：{@code <PRI>Mmm dd hh:mm:ss HOSTNAME TAG: MSG}。
     * 兼容性最好，常见于传统 syslogd 与网络设备。
     */
    RFC3164,

    /**
     * RFC 5424（IETF 新格式）：{@code <PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID SD MSG}。
     * 时间戳为 ISO 8601（UTC），支持结构化数据，推荐使用。
     */
    RFC5424
}
