package com.chua.datalake.support.subscriber;

import java.util.Collections;
import java.util.Set;

/**
 * 订阅器抽象。绑定 subscriberid 与 偏移量 推进逻辑。
 *
 * <p>订阅器通过 {@code OffsetFlow} 持久化 offset；
 * 接收方以本地 {@link #onPush(PushPayload)} 抽象推送。</p>
 *
 * <p>订阅器实例需登记到 {@link SubscriberRegistry}（或经 {@code SubscriberManager} 登记），
 * 才会被 {@code RealTimeSink} 的投递链路命中；只在本地 new 出来的订阅器收不到任何数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Subscriber {

    /**
     * 返回订阅器唯一 标识
     * @return 结果字符串
     */
    String subscriberId();

    /**
     * 订阅的主题集合，用于投递前的路由过滤。
     *
     * @return 主题集合；为空表示接收全部主题
     */
    default Set<String> topics() {
        return Collections.emptySet();
    }

    /**
     * 推送一条数据
     *
     * <p>实现方若消费失败应抛出异常，位点不得推进，该条数据可重投。</p>
     *
     * @param payload 推送数据载荷
     */
    void onPush(PushPayload payload);
}
