package com.chua.common.support.network.syslog;

import lombok.Builder;
import lombok.Getter;

/**
 * Syslog 服务端配置。
 *
 * @author CH
 * @since 4.0.0.51
 */
@Getter
@Builder
public class SyslogConfig {

    /**
     * 默认绑定地址：所有网卡。
     */
    public static final String DEFAULT_BIND_HOST = "0.0.0.0";

    /**
     * 默认端口：RFC 3164/5426 标准端口 514。
     */
    public static final int DEFAULT_PORT = 514;

    /**
     * 默认单条报文最大长度（字节）。RFC 5426 建议 UDP 载荷不超过 8192。
     */
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 8192;

    /**
     * 默认 TCP accept 队列长度。
     */
    public static final int DEFAULT_BACKLOG = 50;

    /**
     * 绑定地址。
     */
    @Builder.Default
    private final String bindHost = DEFAULT_BIND_HOST;

    /**
     * 监听端口。
     */
    @Builder.Default
    private final int port = DEFAULT_PORT;

    /**
     * 传输协议：UDP（默认）或 TCP。
     */
    @Builder.Default
    private final SyslogTransport transport = SyslogTransport.UDP;

    /**
     * 单条报文最大长度（字节），超长部分被丢弃。
     */
    @Builder.Default
    private final int maxMessageLength = DEFAULT_MAX_MESSAGE_LENGTH;

    /**
     * TCP accept 队列长度。
     */
    @Builder.Default
    private final int backlog = DEFAULT_BACKLOG;

    /**
     * 创建默认配置。
     *
     * @return 默认配置（0.0.0.0:514，UDP）
     */
    public static SyslogConfig defaults() {
        return SyslogConfig.builder().build();
    }
}
