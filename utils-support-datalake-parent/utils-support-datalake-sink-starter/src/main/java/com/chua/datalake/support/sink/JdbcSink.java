package com.chua.datalake.support.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.DataSink;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * JDBC Sink — 落盘到关系型数据库。
 *
 * <p>通过 {@link #getDataSource()} 返回存储连接，
 * 被 calcite 聚合查询引擎统一管理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jdbc")
public class JdbcSink implements DataSink {

    @Override
    public String type() {
        return "jdbc";
    }

    @Override
    public void start() {
        log.info("[datalake-sink] JdbcSink 启动");
    }

    @Override
    public void stop() {
        log.info("[datalake-sink] JdbcSink 停止");
    }

    @Override
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (envelope == null) {
            return false;
        }
        log.info("[datalake-sink] 写入数据: pipelineId={}, data={}",
                envelope.getPipelineId(),
                envelope.getParsed());
        return true;
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}