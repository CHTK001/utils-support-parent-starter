package com.chua.datalake.support.subscriber;

/**
* 订阅器抽象。绑定 subscriberid 与 偏移量 推进逻辑。
*
* <p>订阅器通过 {@code OffsetFlow} 持久化 offset；
* 接收方以本地 {@link #onPush(PushPayload)} 抽象推送。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface Subscriber {

    /**
    * 返回订阅器唯一 标识
     */
    String subscriberId();

    /**
    * 推送一条数据
    *
    * @param payload 推送数据载荷
     */
    void onPush(PushPayload payload);
}