package com.chua.common.support.scatter.node;

/**
 * scatter 节点服务端接口：短连接帧请求-响应服务。
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface ScatterNodeServer extends AutoCloseable {

    /**
    * 启动节点服务端。
    *
    * @throws Exception 启动异常
    */
    void start() throws Exception;

    /**
    * 停止节点服务端。
    *
    * @throws Exception 停止异常
    */
    void stop() throws Exception;

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
