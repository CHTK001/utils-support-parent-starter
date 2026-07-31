package com.chua.datasync.agent.support;

import reactor.core.publisher.Flux;
import java.util.Map;

/**
 * 数据同步 Sink 接口，定义数据写入能力。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataSyncAgentSink {

    /**
     * 获取 Sink 实例唯一标识。
     *
     * @return Sink 实例 ID
     */
    String sinkId();

    /**
     * 写入数据流。
     *
     * @param data 数据流
     */
    void write(Flux<Map<String, Object>> data);

    /**
     * 关闭 Sink，释放资源。
     */
    void close();
}
