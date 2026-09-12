package com.chua.datalake.support.subscriber;

import com.chua.common.support.concurrent.offset.OffsetFlow;
import com.chua.datalake.support.model.DataEnvelope;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Consumer;

/**
* 实时订阅器：基于 Reactor 推背压处理实时数据流。
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
    * 构造。
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

    @Override
    /** 订阅 */
    public void subscribe() {
        log.info("[datalake-subscribe] 订阅器已订阅: subscriberId={}, offset={}",
                subscriberId, currentOffset());
    }

    /**
    * 推送一条数据。先推进 偏移量，再调用 consumer。
    * 由 数据湖服务端 内部用。返回 {@link Mono} 以适配 响应式 背压。
    *
    * @param envelope envelope
    * @return push的结果
     */
    public Mono<Void> push(DataEnvelope envelope) {
        if (envelope == null) {
            return Mono.empty();
        }
        long newOffset = advance();
        return Mono.fromRunnable(() -> consumer.accept(envelope))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.fromRunnable(() -> {
                    if (envelope.getTimestamp() > newOffset) {
                        reset(envelope.getTimestamp());
                    }
                }))
                .then();
    }

    /**
    * 批量推送。
    *
    * @param envelopes envelope 数据流
    * @return 表示批量推送完成的 {@link Mono}
     */
    public Mono<Void> pushBatch(Flux<DataEnvelope> envelopes) {
        if (envelopes == null) {
            return Mono.empty();
        }
        return envelopes
                .flatMap(this::push)
                .then();
    }
}