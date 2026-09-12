package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
* 响应式用量流聚合工具：将多个解析器的流按并发度合并，
* 供数据同步等下游以背压方式消费，避免一次性全量装载导致 OOM。
*
* @author CH
* @since 4.0.0.42
 */
public final class ReactiveUsageStreams {

    /**
    * 响应式usage流。
     */
    private ReactiveUsageStreams() {
    }

    /**
    * 并发合并多个解析器的流式输出。
    *
    * @param parsers     解析器列表
    * @param concurrency 并发度（同时处于抓取/解析状态的解析器数量）
    * @return 合并后的用量记录流
     */
    public static Flux<AiUsage> merge(List<? extends UsageParser> parsers, int concurrency) {
        return Flux.fromIterable(parsers)
                .flatMap(p -> p.streamAll()
                                .subscribeOn(Schedulers.boundedElastic()),
                        Math.max(concurrency, 1));
    }

    /**
    * 串行合并（逐个解析器顺序产出）。
    * @param parsers parsers
    * @return 连接的结果
     */
    public static Flux<AiUsage> concat(List<? extends UsageParser> parsers) {
        return Flux.fromIterable(parsers).concatMap(UsageParser::streamAll);
    }
}