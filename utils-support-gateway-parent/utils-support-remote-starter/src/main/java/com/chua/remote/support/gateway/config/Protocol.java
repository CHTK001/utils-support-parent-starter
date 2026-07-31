package com.chua.remote.support.gateway.config;


/**
 * 支持的目标协议枚举
 * <p>定义了网关可代理转发的所有远程协议类型，覆盖桌面、终端、数据库、HTTP 等常见协议。
 *
 * @author CH
 */
public enum Protocol {
    /** Windows 远程桌面协议 */
    RDP,
    /** SSH 终端协议 */
    SSH,
    /** VNC 远程桌面协议 */
    VNC,
    /** HTTP 协议 */
    HTTP,
    /** HTTPS 协议 */
    HTTPS,
    /** HTTP/2 协议 */
    HTTP2,
    /** MySQL 数据库协议 */
    MYSQL,
    /** PostgreSQL 数据库协议 */
    POSTGRESQL,
    /** Redis 缓存协议 */
    REDIS,
    /** 通用桌面远程协议（自定义编码） */
    DESKTOP,
    /** 会议/WebRTC 协议 */
    CONFERENCE,
    /** SOCKS5 代理协议 */
    SOCKS5,
    /** 自定义远程协议（扩展用） */
    CUSTOM_REMOTE,
    /** RustDesk 远程桌面协议 */
    RUSTDESK,
    /** 未知/未识别协议 */
    UNKNOWN
}
