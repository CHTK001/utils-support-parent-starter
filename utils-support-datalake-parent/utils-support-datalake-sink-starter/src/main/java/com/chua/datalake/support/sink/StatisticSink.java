package com.chua.datalake.support.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.AccessSink;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 统计 Sink — 计数 / 聚合快照，后续扩展指标计算。
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@Spi("statistic")
public class StatisticSink implements AccessSink {

    @Override
    public String type() {
        return "statistic";
    }

    @Override
    public void start() {
        log.info("StatisticSink started");
    }

    @Override
    public void stop() {
        log.info("StatisticSink stopped");
    }

    @Override
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (envelope == null) {
            return false;
        }
        log.info("[SINK:statistic] pipelineId={}, timestamp={}",
                envelope.getPipelineId(), envelope.getTimestamp());
        return true;
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}