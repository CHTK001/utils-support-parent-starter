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
 * @since 4.0.0.42
 */
@Slf4j
public class SinkManager {

    /**
     * 所有已注册 sink
     */
    private final Map<String, DataSink> sinkMap;

    /**
     * 构造 sink管理器
     *
     * @param sinkMap 类型 → implementation 映射
     */
    public SinkManager(Map<String, DataSink> sinkMap) {
        this.sinkMap = sinkMap;
    }

    /**
     * 启动所有已注册 sink
     */
    public void start() {
        log.info("[datalake-server] SinkManager 启动 {} 个 sink", sinkMap.size());
        int started = 0;
        for (DataSink sink : sinkMap.values()) {
            try {
                sink.start();
                started++;
            } catch (Exception e) {
                // 单个 Sink 启动失败（如数据源地址不可达）不得影响其余通道
                log.error("[datalake-server] sink 启动失败: type=" + sink.type(), e);
            }
        }
        if (started < sinkMap.size()) {
            log.warn("[datalake-server] SinkManager 仅启动 {}/{} 个 sink", started, sinkMap.size());
        }
    }

    /**
     * 停止所有 sink
     */
    public void stop() {
        for (DataSink sink : sinkMap.values()) {
            try {
                sink.stop();
            } catch (Exception e) {
                // 停机阶段同样隔离，保证每个 sink 都有释放资源的机会
                log.error("[datalake-server] sink 停止失败: type=" + sink.type(), e);
            }
        }
    }

    /**
     * 查询存储型 sink（有 数据源）
     *
     * @return 存储型 sink 列表
     */
    public List<DataSink> storeSinks() {
        List<DataSink> result = new ArrayList<>();
        for (DataSink sink : sinkMap.values()) {
            if (!(sink instanceof AccessSink) && sink.getDataSource() != null) {
                result.add(sink);
            }
        }
        return result;
    }
}
