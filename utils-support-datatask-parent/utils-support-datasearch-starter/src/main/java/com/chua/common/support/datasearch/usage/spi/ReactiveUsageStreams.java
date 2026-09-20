package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 响应式用量流聚合工具：将多个解析器的流按并发度合并，
 * 供数据同步等下游以背压方式消费，避免一次性全量装载导致 OOM。
 *
 * <p>本类是用量数据的统一出口，因此在合并结果上接了 {@link UsageFieldCompleter}：
 * 多数解析器直接覆写 {@code streamAll()} 各自产出原始 Token 计数，费用与汇总字段
 * 在此集中补齐。补全只填空值且幂等，故下游重复经手工调用无副作用。</p>
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
     * 并发合并多个解析器的流式输出，并补全费用与汇总字段。
     *
     * @param parsers     解析器列表
     * @param concurrency 并发度（同时处于抓取/解析状态的解析器数量）
     * @return 合并后的用量记录流
     */
    public static Flux<AiUsage> merge(List<? extends UsageParser> parsers, int concurrency) {
        return UsageFieldCompleter.complete(Flux.fromIterable(parsers)
                .flatMap(p -> p.streamAll()
                                .subscribeOn(Schedulers.boundedElastic()),
                        Math.max(concurrency, 1)));
    }

    /**
     * 串行合并（逐个解析器顺序产出），并补全费用与汇总字段。
     * @param parsers parsers
     * @return 连接的结果
     */
    public static Flux<AiUsage> concat(List<? extends UsageParser> parsers) {
        return UsageFieldCompleter.complete(Flux.fromIterable(parsers)
                .concatMap(UsageParser::streamAll));
    }
}
