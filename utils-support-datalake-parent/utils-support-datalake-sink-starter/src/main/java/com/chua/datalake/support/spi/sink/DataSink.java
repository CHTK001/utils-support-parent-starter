package com.chua.datalake.support.spi.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.datalake.support.model.DataEnvelope;

import java.util.Map;

/**
 * 数据落地基础 SPI。管线末端会将处理完成的 envelope 分发至已注册的 Sink。
 *
 * <p>两类 Sink：</p>
 * <ul>
 *   <li><b>存储型 Sink（如 JdbcSink）</b> — 对齐外部存储，返回有效 {@link #getDataSource()}</li>
 *   <li><b>访问型 Sink（如 RealTimeSink）</b> — 通过 {@link AccessSink} 标记，供 {@code SubscriberManager}
 *       实时推送，{@link #getDataSource()} 应返回 {@code null}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface DataSink {

    /**
     * 返回 Sink 类型标识，同时对应发布到 DispatcherProvider 的 topic
     *
     * @return 类型标识（例如 "jdbc"、"real-time"、"stats"、"log"）
     */
    String type();

    /**
     * 启动 Sink，建立资源连接
     */
    void start();

    /**
     * 停止 Sink，释放资源
     */
    void stop();

    /**
     * 处理一条完成通道传递来的 DataEnvelope
     *
     * @param envelope 携带待处理数据的 DataEnvelope
     * @param config   可由管线配置传入的附加参数
     * @return true 表示处理成功，false 表示注册失败
     */
    boolean write(DataEnvelope envelope, Map<String, Object> config);

    /**
     * 获取封装的底层数据源。
     * 存储型 Sink 返回 DataSource，访问型 Sink 返回 null。
     *
     * @return EngineDataSource 或 null
     */
    EngineDataSource<?> getDataSource();
}