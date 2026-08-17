package com.chua.common.support.scatter;

import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.network.server.SyncServer;

/**
 * Scatter 节点生命周期服务。
 * <p>负责启动和停止节点服务，同一端口承载数据同步与心跳。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterNodeServer extends AutoCloseable {

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
     * 注册消息处理器。
     *
     * @param topic   主题
     * @param handler 处理器
     */
    default void registerHandler(String topic, SyncMessageHandler handler) {
    }

    /**
     * 获取底层同步服务端。
     *
     * @return SyncServer 实例
     */
    default SyncServer getSyncServer() {
        return null;
    }

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
