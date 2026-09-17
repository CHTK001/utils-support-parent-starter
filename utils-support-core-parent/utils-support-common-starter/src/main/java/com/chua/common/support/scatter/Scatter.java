package com.chua.common.support.scatter;

/**
 * scatter 聚合入口：统一管理发现服务与节点服务端生命周期。
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface Scatter extends AutoCloseable {

    /**
    * 启动。
    *
    * @throws Exception 启动异常
    */
    void start() throws Exception;

    /**
    * 停止。
    *
    * @throws Exception 停止异常
    */
    void stop() throws Exception;

    /**
    * 获取发现服务。
    *
    * @return 发现服务
    */
    ScatterServiceDiscovery discovery();

    /**
    * 获取实际监听端口。
    *
    * @return 端口
    */
    int getPort();

    @Override
    default void close() throws Exception {
        stop();
    }
}
