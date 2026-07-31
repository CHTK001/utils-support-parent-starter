package com.chua.remote.support.gateway.config;


/**
 * 连接模式枚举
 * <p>定义网关与目标之间连接的生命周期策略：
 * <ul>
 *   <li>{@link #LONG} — 长连接，保持持久通道，复用 TCP 连接传输多帧数据</li>
 *   <li>{@link #SHORT} — 短连接，每次通信后关闭通道，适合低频信令</li>
 * </ul>
 *
 * @author CH
 */
public enum ConnectionMode {
    /** 长连接 — 持久通道，适合 SSH/桌面远程等持续通信场景 */
    LONG,
    /** 短连接 — 请求响应式，每次通信后关闭 */
    SHORT
}
