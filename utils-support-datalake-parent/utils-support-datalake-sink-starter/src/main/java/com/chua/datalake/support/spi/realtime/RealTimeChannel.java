package com.chua.datalake.support.spi.realtime;

import com.chua.datalake.support.model.DataEnvelope;

/**
 * 实时通道 SPI —— 访问型 Sink 的落地出口扩展点。
 *
 * <p>{@code RealTimeSink} 只判定"这条数据有没有被下游真的收下"，交给谁由本接口的实现决定：
 * 订阅端提供 {@code SubscriberChannel} 把数据投给已注册的订阅器，
 * 第三方也可登记自己的通道（WebSocket、SSE、浏览器推送等）而无需改动框架。</p>
 *
 * <p>登记方式：在 {@code META-INF/extensions/com.chua.datalake.support.spi.realtime.RealTimeChannel}
 * 中写实现类全限定名（或 {@code 别名=实现类全限定名}），实现类需具备无参构造。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RealTimeChannel {

    /**
     * 通道名称，用于日志与追踪定位。
     *
     * @return 名称
     */
    String name();

    /**
     * 投递一条数据信封。
     *
     * @param envelope 数据信封，调用方保证非空
     * @return 至少有一个下游成功收下返回 true；无人认领或全部失败返回 false
     */
    boolean deliver(DataEnvelope envelope);
}
