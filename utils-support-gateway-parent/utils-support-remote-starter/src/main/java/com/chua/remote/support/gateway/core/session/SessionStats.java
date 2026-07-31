package com.chua.remote.support.gateway.core.session;


/**
 * 会话统计摘要
 * <p>记录网关全局的会话数量和数据传输统计。
 *
 * @param totalSessions         累计创建的会话总数
 * @param activeSessions        当前活跃的会话数
 * @param totalBytesSent        累计发送字节数
 * @param totalBytesReceived    累计接收字节数
 * @param totalFramesTransferred 累计传输帧数
 * @author CH
 */
public record SessionStats(int totalSessions,
    int activeSessions,
    long totalBytesSent,
    long totalBytesReceived,
    long totalFramesTransferred) {}
