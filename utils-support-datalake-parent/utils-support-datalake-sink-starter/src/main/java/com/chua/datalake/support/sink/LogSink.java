package com.chua.datalake.support.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.AccessSink;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * LogSink — 审计型 Sink，打印到日志。
 *
 * <p>实现 {@link AccessSink}，不需要存储 DataSource。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("log")
public class LogSink implements AccessSink {

    @Override
    public String type() {
        return "log";
    }

    @Override
    public void start() {
        log.info("[datalake-sink] LogSink 启动");
    }

    @Override
    public void stop() {
        log.info("[datalake-sink] LogSink 停止");
    }

    @Override
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (envelope == null) {
            log.warn("[datalake-sink] LogSink 接收到空 envelope");
            return false;
        }
        log.info("[datalake-sink] 写入数据: pipelineId={}, traceId={}, data={}",
                envelope.getPipelineId(),
                envelope.getTraceId(),
                envelope.getParsed());
        return true;
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}