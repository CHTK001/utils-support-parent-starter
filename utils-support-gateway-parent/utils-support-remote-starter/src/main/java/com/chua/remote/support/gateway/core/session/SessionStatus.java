package com.chua.remote.support.gateway.core.session;


/**
 * 会话状态枚举
 * <p>标识远程会话当前的生命周期阶段。
 *
 * @author CH
 */
public enum SessionStatus {
    /** 活跃 — 会话正常运行，可收发数据 */
    ACTIVE,
    /** 暂停 — 会话暂时挂起，不处理数据 */
    PAUSED,
    /** 已关闭 — 会话终止，资源已释放 */
    CLOSED,
    /** 重连中 — Agent 断线后等待重新建立连接 */
    RECONNECTING
}
