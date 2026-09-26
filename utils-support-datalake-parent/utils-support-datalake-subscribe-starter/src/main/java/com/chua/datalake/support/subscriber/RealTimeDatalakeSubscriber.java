package com.chua.datalake.support.subscriber;

import com.chua.common.support.concurrent.offset.OffsetFlow;
import com.chua.datalake.support.model.DataEnvelope;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 实时订阅器：基于 Reactor 推背压处理实时数据流。
 *
 * <p>投递有两条入口，语义同源：响应式侧走 {@link #push(DataEnvelope)}，
 * 由 {@code RealTimeSink} 经 {@code SubscriberChannel} 触发的同步侧走 {@link #onPush(PushPayload)}。
 * 两者都遵循"消费成功才推进位点"的至少一次语义。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RealTimeDatalakeSubscriber extends AbstractDatalakeSubscriber {

    /**
     * 实际数据消费者（由外部接入业务逻辑）
     */
    private final Consumer<DataEnvelope> consumer;

    /**
     * 构造（不限主题）。
     *
     * @param subscriberId 订阅器 标识
     * @param offsetFlow   偏移量流 门面
     * @param consumer     实际数据消费函数
     */
    public RealTimeDatalakeSubscriber(
            String subscriberId,
            OffsetFlow offsetFlow,
            Consumer<DataEnvelope> consumer) {
        super(subscriberId, offsetFlow);
        this.consumer = consumer;
    }

    /**
     * 构造。
     *
     * @param subscriberId 订阅器 标识
     * @param offsetFlow   偏移量流 门面
     * @param consumer     实际数据消费函数
     * @param topics       订阅主题，空表示全量接收
     */
    public RealTimeDatalakeSubscriber(
            String subscriberId,
            OffsetFlow offsetFlow,
            Consumer<DataEnvelope> consumer,
            Set<String> topics) {
        super(subscriberId, offsetFlow, topics);
        this.consumer = consumer;
    }

    @Override
    /**
     * 订阅
    */
    public void subscribe() {
        SubscriberRegistry.getInstance().register(this);
        log.info("[datalake-subscribe] 订阅器已订阅: subscriberId={}, topics={}, offset={}",
                subscriberId, topics, currentOffset());
    }

    @Override
    /**
     * on推送
    */
    public void onPush(PushPayload payload) {
        if (payload == null || payload.getEnvelope() == null) {
            return;
        }
        consume(payload.getEnvelope());
    }

    /**
     * 推送一条数据：消费成功后才推进 偏移量（至少一次语义）。
     * 由 数据湖服务端 内部用。返回 {@link Mono} 以适配 响应式 背压。
     *
     * <p>消费方抛错时 偏移量 保持原位，本条数据会在重启后重投；
     * 反之若先推进再消费，这条记录就永久丢失。</p>
     *
     * @param envelope envelope
     * @return push的结果
     */
    public Mono<Void> push(DataEnvelope envelope) {
        if (envelope == null) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> consume(envelope))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 批量推送。
     *
     * <p>必须串行：位点是共享自增序号，{@code flatMap} 的并发消费会让快的那条先把位点推过去，
     * 慢的那条失败时已经"看起来被消费过"，至少一次语义随即失效。</p>
     *
     * @param envelopes envelope 数据流
     * @return 表示批量推送完成的 {@link Mono}
     */
    public Mono<Void> pushBatch(Flux<DataEnvelope> envelopes) {
        if (envelopes == null) {
            return Mono.empty();
        }
        return envelopes
                .concatMap(this::push)
                .then();
    }

    /**
     * 消费一条并推进位点，失败时位点保持原位。
     *
     * @param envelope 数据信封
     */
    private void consume(DataEnvelope envelope) {
        consumer.accept(envelope);
        advance();
    }
}
