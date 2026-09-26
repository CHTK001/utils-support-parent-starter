package com.chua.datalake.support.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.sink.AccessSink;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 统计 Sink — 累计管线数据量。
 *
 * <p>按 pipelineId 维护计数器，总量与单管线量可分别读取，
 * 供巡检接口或指标导出使用；不落地任何业务数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("statistic")
public class StatisticSink implements AccessSink {

    /**
     * 按管线累计的数据条数
     */
    private final Map<String, LongAdder> pipelineCounters = new ConcurrentHashMap<>();
    /**
     * 总数据条数
     */
    private final LongAdder total = new LongAdder();

    @Override
    /**
     * 类型
    */
    public String type() {
        return "statistic";
    }

    @Override
    /**
     * 开始
    */
    public void start() {
        log.info("[datalake-sink] StatisticSink 启动");
    }

    @Override
    /**
     * 停止
    */
    public void stop() {
        log.info("[datalake-sink] StatisticSink 停止, 累计 {} 条", total.sum());
    }

    @Override
    /**
     * 写入
    */
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (envelope == null) {
            return false;
        }
        total.increment();
        String pipelineId = envelope.getPipelineId();
        if (pipelineId != null) {
            pipelineCounters.computeIfAbsent(pipelineId, key -> new LongAdder()).increment();
        }
        if (log.isDebugEnabled()) {
            log.debug("[datalake-sink] 统计记录: pipelineId={}, timestamp={}, total={}",
                    pipelineId, envelope.getTimestamp(), total.sum());
        }
        return true;
    }

    /**
     * 累计数据条数。
     *
     * @return 总数
     */
    public long total() {
        return total.sum();
    }

    /**
     * 指定管线的累计数据条数。
     *
     * @param pipelineId 管线标识
     * @return 该管线的计数，未见统计时为 0
     */
    public long countOf(String pipelineId) {
        LongAdder adder = pipelineCounters.get(pipelineId);
        return adder == null ? 0L : adder.sum();
    }

    /**
     * 清零全部计数。
     */
    public void reset() {
        total.reset();
        pipelineCounters.clear();
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }
}
