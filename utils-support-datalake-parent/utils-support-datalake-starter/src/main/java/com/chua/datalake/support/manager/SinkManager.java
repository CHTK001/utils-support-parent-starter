package com.chua.datalake.support.manager;

import com.chua.datalake.support.spi.sink.AccessSink;
import com.chua.datalake.support.spi.sink.DataSink;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sink 管理器，负责注册及管理所有 Sink 实例。
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class SinkManager {

    /**
     * 所有已注册 sink
     */
    private final Map<String, DataSink> sinkMap;

    /**
     * 构造 SinkManager
     *
     * @param sinkMap type → implementation map
     */
    public SinkManager(Map<String, DataSink> sinkMap) {
        this.sinkMap = sinkMap;
    }

    /**
     * 启动所有已注册 sink
     */
    public void start() {
        log.info("[datalake-server] SinkManager 启动 {} 个 sink", sinkMap.size());
        for (DataSink sink : sinkMap.values()) {
            sink.start();
        }
    }

    /**
     * 停止所有 sink
     */
    public void stop() {
        for (DataSink sink : sinkMap.values()) {
            sink.stop();
        }
    }

    /**
     * 查询存储型 sink（有 DataSource）
     *
     * @return 存储型 sink 列表
     */
    public List<DataSink> storeSinks() {
        List<DataSink> result = new ArrayList<>();
        for (DataSink sink : sinkMap.values()) {
            if (!(sink instanceof SinkAccessSink) && sink.getDataSource() != null) {
                result.add(sink);
            }
        }
        return result;
    }

    /**
     * 内部标记型接口，用于在包内识别 {@link AccessSink} 类型而避免外部依赖。
     */
    private interface SinkAccessSink extends com.chua.datalake.support.spi.sink.AccessSink {
    }
}