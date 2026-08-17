package com.chua.common.support.scatter;

/**
 * Scatter 对等节点门面。
 * <p>一个节点 = 服务发现(hash 表) + 自身即 TCP 代理服务器：
 * 同一端口承载数据同步与心跳，支持按 builder 策略动态转发。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Scatter extends AutoCloseable {

    /**
     * 启动节点（启动发现与代理，共用本节点端口）。
     *
     * @throws Exception 启动异常
     */
    void start() throws Exception;

    /**
     * 停止节点。
     *
     * @throws Exception 停止异常
     */
    void stop() throws Exception;

    /**
     * 获取服务发现（节点表/路由能力）。
     *
     * @return 服务发现实例
     */
    ScatterServiceDiscovery discovery();

    /**
     * 获取通信端口。
     *
     * @return 端口
     */
    int getPort();

    /**
     * 默认关闭实现，委托 stop。
     *
     * @throws Exception 异常
     */
    @Override
    default void close() throws Exception {
        stop();
    }
}
