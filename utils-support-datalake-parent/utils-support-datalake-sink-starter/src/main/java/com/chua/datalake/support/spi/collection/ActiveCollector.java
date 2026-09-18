package com.chua.datalake.support.spi.collection;

import java.util.Map;
import java.util.Set;

/**
 * 主动数据采集器 SPI。
 *
 * <p>各中间件（Kafka / Chronicle / MQTT 等）提供 {@code ActiveCollector} 实现，
 * 负责连接数据源、轮询或监听消息，并交由 {@link DataHandler} 处理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ActiveCollector {

    /**
    * 协议名称（如 KAFKA / CHRONICLE / MQTT）。
    *
    * @return 协议名称
    */
    String protocol();

    /**
    * 默认端口。
    *
    * @return 默认端口
    */
    int defaultPort();

    /**
    * 启动采集器。
    *
    * @param port 监听端口
    * @throws Exception 启动异常
    */
    void start(int port) throws Exception;

    /**
    * 停止采集器。
    */
    void stop();

    /**
    * 是否正在运行。
    *
    * @return true 表示运行中
    */
    boolean isRunning();

    /**
    * 注册 topic 到 pipeline 的映射。
    *
    * @param topic      主题
    * @param pipelineId 管道标识
    */
    void registerMapping(String topic, String pipelineId);

    /**
    * 注销 topic 映射。
    *
    * @param topic 主题
    */
    void unregisterMapping(String topic);

    /**
    * 获取 topic 到 pipeline 的映射。
    *
    * @return 不可变映射
    */
    Map<String, String> getMappings();

    /**
    * 已订阅的主题集合。
    *
    * @return 不可变集合
    */
    Set<String> subscribedTopics();

    /**
    * 设置数据处理器。
    *
    * @param handler 数据处理器
    */
    void setHandler(DataHandler handler);

    /**
    * 获取采集器状态。
    *
    * @return 状态键值对
    */
    Map<String, Object> getStatus();
}
