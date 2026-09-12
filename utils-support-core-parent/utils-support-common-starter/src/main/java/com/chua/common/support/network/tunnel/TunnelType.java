package com.chua.common.support.network.tunnel;


/**
* 隧道类型枚举。
*
* @author CH
* @since 2026/07/31
 */
public enum TunnelType {

    /** 正向隧道：本地端口 → 远程主机端口 */
    LOCAL,

    /** 反向隧道：远程端口 → 本地主机端口 */
    REMOTE,

    /** 动态隧道：本地 SOCKS5 代理 */
    DYNAMIC
}
