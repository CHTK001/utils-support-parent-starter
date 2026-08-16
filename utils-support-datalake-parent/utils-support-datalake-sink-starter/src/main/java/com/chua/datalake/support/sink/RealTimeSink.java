package com.chua.datalake.support.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.AccessSink;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 实时 Sink — 将数据实时推送给 SubscriberManager。
 *
 * <p>实现 {@link AccessSink}，管线下发的每条数据将被实时感知。
 * 实际分发逻辑与 {@code SubscriberManager} 交互完成。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("realtime")
public class RealTimeSink implements AccessSink {

    @Override
    public String type() {
        return "realtime";
    }

    @Override
    public void start() {
        log.info("[datalake-sink] RealTimeSink 启动");
    }

    @Override
    public void stop() {
        log.info("[datalake-sink] RealTimeSink 停止");
    }

    @Override
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (envelope == null) {
            return false;
        }
        if (envelope.isLog()) {
            log.debug("[datalake-sink] 跳过日志型 envelope: traceId={}", envelope.getTraceId());
            return true;
        }
        log.info("[datalake-sink] 实时推送: pipelineId={}, timestamp={}, data={}",
                envelope.getPipelineId(),
                envelope.getTimestamp(),
                envelope.getParsed());
        return true;
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}