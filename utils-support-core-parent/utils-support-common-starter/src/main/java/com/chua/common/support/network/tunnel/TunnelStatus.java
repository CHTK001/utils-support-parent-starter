package com.chua.common.support.network.tunnel;


/**
 * 隧道状态枚举。
 *
 * @author CH
 * @since 2026/07/31
*/
public enum TunnelStatus {

    /** 隧道已开启，正在运行 */
    OPEN,

    /** 隧道已关闭 */
    CLOSED,

    /** 隧道发生错误 */
    ERROR
}
