package com.chua.datasource.support.middleware.zookeeper;

/**
 * ZooKeeper 连通性探测结果。
 *
 * @param connected 是否连通
 * @param version   服务端版本号，取自四字命令 {@code srvr}，取不到时为空串
 * @param sessionId 会话 ID（十六进制），取不到时为空串
 * @param message   结果说明；失败时为可读原因
 * @author CH
 * @since 4.0.0.51
 */
public record ZookeeperTestResult(Boolean connected, String version, String sessionId, String message) {
}
