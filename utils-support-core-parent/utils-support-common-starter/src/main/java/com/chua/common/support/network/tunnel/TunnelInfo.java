package com.chua.common.support.network.tunnel;

import java.time.LocalDateTime;
import org.jspecify.annotations.NullUnmarked;

/**
 * 隧道实时信息快照。
 *
 * @param port       本地绑定端口号
 * @param status     隧道状态：OPEN、CLOSED、ERROR
 * @param type       隧道类型：LOCAL、REMOTE、DYNAMIC
 * @param bindAddress 绑定地址
 * @param targetHost  目标主机
 * @param targetPort  目标端口
 * @param message     附加信息或错误描述
 * @param updateTime  信息更新时间
 * @author CH
 * @since 2026/07/31
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public record TunnelInfo(
        int port,
        TunnelStatus status,
        TunnelType type,
        String bindAddress,
        String targetHost,
        int targetPort,
        String message,
        LocalDateTime updateTime
) {

    /**
     * 创建一个隧道信息实例。
     *
     * @param port        本地绑定端口号
     * @param status      隧道状态
     * @param type        隧道类型
     * @param bindAddress 绑定地址
     * @param targetHost  目标主机
     * @param targetPort  目标端口
     * @return 隧道信息实例
     */
    public static TunnelInfo of(int port, TunnelStatus status, TunnelType type,
                                String bindAddress, String targetHost, int targetPort) {
        return new TunnelInfo(port, status, type, bindAddress, targetHost, targetPort, null, LocalDateTime.now());
    }

    /**
     * 创建一个带错误信息的隧道信息实例。
     *
     * @param status  隧道状态（通常为 ERROR）
     * @param message 错误描述
     * @return 隧道信息实例
     */
    public static TunnelInfo error(TunnelStatus status, String message) {
        return new TunnelInfo(0, status, null, null, null, 0, message, LocalDateTime.now());
    }
}