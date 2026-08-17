package com.chua.common.support.scatter;

/**
 * Scatter 远程客户端。
 * <p>负责向远程节点发送查询请求并获取响应，按传输协议（udp/tcp/kcp）SPI 实现。</p>
 *
 * @param <T> 数据类型
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterRemoteClient<T> {

    /**
     * 向指定节点发起远程调用。
     *
     * @param context       查询上下文
     * @param node          目标节点
     * @param timeoutMillis 超时时间（毫秒）
     * @return 查询结果
     * @throws Exception 调用异常
     */
    ScatterResult<T> invoke(ScatterContext context, ScatterNode node, long timeoutMillis) throws Exception;

    /**
     * 关闭所有连接并释放资源。
     */
    default void closeAll() {
        // 默认空实现，子类可覆盖
    }
}
