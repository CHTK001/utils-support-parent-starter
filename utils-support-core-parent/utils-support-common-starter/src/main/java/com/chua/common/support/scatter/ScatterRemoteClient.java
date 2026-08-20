package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;

/**
 * scatter 远程客户端：向对端节点发起同步请求（拉取/推送服务 hash）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterRemoteClient {

    /**
     * 向节点发起同步请求。
     *
     * @param context      请求上下文
     * @param node         目标节点
     * @param timeoutMillis 超时毫秒
     * @return 结果（成功携带对端服务表）
     */
    ScatterResult<Discovery> invoke(ScatterContext context, ScatterNode node, long timeoutMillis);

    /**
     * 关闭释放资源。
     */
    void close();
}
