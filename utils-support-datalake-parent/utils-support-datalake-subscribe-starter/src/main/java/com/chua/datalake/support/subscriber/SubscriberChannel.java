package com.chua.datalake.support.subscriber;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.realtime.RealTimeChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * 订阅端实时通道 —— 把访问型 Sink 的数据投递给 {@link SubscriberRegistry} 中的订阅器。
 *
 * <p>本类是 {@link RealTimeChannel} 的默认实现，由 SPI 加载，因此 Sink 侧无需依赖订阅模块。
 * 没有任何订阅器登记时返回 false，让上层 Sink 明确感知"数据未被消费"。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("subscriber")
public class SubscriberChannel implements RealTimeChannel {

    @Override
    public String name() {
        return "subscriber";
    }

    @Override
    public boolean deliver(DataEnvelope envelope) {
        if (envelope == null) {
            return false;
        }
        return SubscriberRegistry.getInstance().dispatch(envelope) > 0;
    }
}
