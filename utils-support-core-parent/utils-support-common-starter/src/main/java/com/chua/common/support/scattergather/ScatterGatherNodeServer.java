package com.chua.common.support.scattergather;


/**
 * Scatter-Gather 节点生命周期服务。
 * <p>负责启动和停止节点服务。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterGatherNodeServer extends AutoCloseable {

    /**
     * 启动节点服务。
     *
     * @throws Exception 启动异常
     */
    void start() throws Exception;

    /**
     * 停止节点服务。
     *
     * @throws Exception 停止异常
     */
    void stop() throws Exception;

    /**
     * 默认关闭实现，委托给 stop。
     *
     * @throws Exception 异常
     */
    @Override
    default void close() throws Exception {
        stop();
    }
}
