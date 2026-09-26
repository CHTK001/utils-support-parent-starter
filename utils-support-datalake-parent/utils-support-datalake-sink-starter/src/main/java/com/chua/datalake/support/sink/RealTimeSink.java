package com.chua.datalake.support.sink;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.realtime.RealTimeChannel;
import com.chua.datalake.support.spi.sink.AccessSink;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时 Sink —— 把管线下发的每条数据交给已登记的 {@link RealTimeChannel}。
 *
 * <p>本类不再自己决定"数据去了哪里"：投递由通道实现承担（订阅端是
 * {@code SubscriberChannel}），因此没有通道在册、或所有通道都没收下时，
 * {@link #write} 返回 {@code false}，由管线记为 SINK_FAIL 而不是静默丢弃。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("realtime")
public class RealTimeSink implements AccessSink {

    /**
     * 已发现的实时通道，按实现类去重后缓存
     */
    private volatile List<RealTimeChannel> channels = Collections.emptyList();

    @Override
    /**
     * 类型
    */
    public String type() {
        return "realtime";
    }

    @Override
    /**
     * 开始
    */
    public void start() {
        log.info("[datalake-sink] RealTimeSink 启动，实时通道 {} 个", refreshChannels().size());
    }

    @Override
    /**
     * 停止
    */
    public void stop() {
        log.info("[datalake-sink] RealTimeSink 停止");
    }

    @Override
    /**
     * 写入
    */
    public boolean write(DataEnvelope envelope, Map<String, Object> config) {
        if (envelope == null) {
            return false;
        }
        if (envelope.isLog()) {
            log.debug("[datalake-sink] 跳过日志型 envelope: traceId={}", envelope.getTraceId());
            return true;
        }
        List<RealTimeChannel> current = channels;
        if (current.isEmpty()) {
            current = refreshChannels();
        }
        if (current.isEmpty()) {
            log.warn("[datalake-sink] 无可用实时通道，pipelineId={} 的数据未被投递", envelope.getPipelineId());
            return false;
        }
        boolean delivered = false;
        for (RealTimeChannel channel : current) {
            try {
                delivered |= channel.deliver(envelope);
            } catch (Exception e) {
                log.error("[datalake-sink] 实时通道投递异常: channel=" + channel.name(), e);
            }
        }
        if (!delivered) {
            log.warn("[datalake-sink] {} 个通道均未收下数据: pipelineId={}", current.size(), envelope.getPipelineId());
        }
        return delivered;
    }

    @Override
    public EngineDataSource<?> getDataSource() {
        return null;
    }

    /**
     * 重新发现实时通道。
     *
     * <p>同一实现可能因"文件登记名 + 注解别名"被解析出两次，按实现类去重，
     * 否则一条数据会被投递两遍。</p>
     *
     * @return 去重后的通道列表
     */
    private synchronized List<RealTimeChannel> refreshChannels() {
        Collection<RealTimeChannel> discovered;
        try {
            discovered = ServiceProvider.of(RealTimeChannel.class).collect();
        } catch (Exception e) {
            log.error("[datalake-sink] 实时通道发现失败", e);
            discovered = Collections.emptyList();
        }
        Map<String, RealTimeChannel> distinct = new LinkedHashMap<>();
        for (RealTimeChannel channel : discovered) {
            if (channel != null) {
                distinct.putIfAbsent(channel.getClass().getName(), channel);
            }
        }
        channels = List.copyOf(distinct.values());
        return channels;
    }
}
