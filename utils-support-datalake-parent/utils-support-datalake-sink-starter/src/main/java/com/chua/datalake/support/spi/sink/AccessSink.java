package com.chua.datalake.support.spi.sink;

/**
 * 标记型 Sink 接口。只供实时传递/日志/统计类消费方实现。
 *
 * <p>实现本接口的 Sink 将被 DispatcherProvider 自动绑定到 topic {@code "#"}，
 * 管线下发的每条数据都会被立即感知处理（无存储落盘行为）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface AccessSink extends DataSink {

    // 无额外方法，标记用途
}