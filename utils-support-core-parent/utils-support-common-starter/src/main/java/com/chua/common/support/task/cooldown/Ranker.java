package com.chua.common.support.task.cooldown;

import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * 候选排序 —— 「都能用的时候先用谁」。
 *
 * <p>这是与冷却分开的一层：冷却只回答「谁能用」，排序只回答「先用谁」。
 * 这样「快过期的优先」「免费优先」「便宜的优先」「延迟低的优先」都是可插拔的
 * 排序器，冷却引擎不需要理解额度、价格、延迟这些业务语义。</p>
 *
 * <p>多个排序器用 {@link #thenBy(Ranker)} 串起来，前一个分出胜负就不再看后一个。</p>
 *
 * @param <T> 候选类型
 * @author CH
 * @since 4.0.0.53
 */
@FunctionalInterface
public interface Ranker<T> {

    /**
     * 排序比较器：返回值越小越优先。
     *
     * @return 比较器
     */
    Comparator<T> comparator();

    /**
     * 追加次级排序：前一个相等时用后一个。
     *
     * @param next 次级排序
     * @return 组合排序
     */
    default Ranker<T> thenBy(Ranker<T> next) {
        Comparator<T> first = comparator();
        return by(first.thenComparing(next.comparator()));
    }

    /**
     * 用现成比较器构造。
     *
     * @param comparator 比较器
     * @param <T>        候选类型
     * @return 排序器
     */
    static <T> Ranker<T> by(Comparator<T> comparator) {
        return () -> comparator;
    }

    /**
     * 快到期的优先：到期时间早的排前面；未提供（null / <= 0）排最后。
     *
     * @param expiresAt 到期时刻提取器（毫秒）
     * @param <T>       候选类型
     * @return 排序器
     */
    static <T> Ranker<T> expiringFirst(Function<T, Long> expiresAt) {
        return by(Comparator.comparingLong(item -> {
            Long value = expiresAt.apply(item);
            return value == null || value <= 0 ? Long.MAX_VALUE : value;
        }));
    }

    /**
     * 免费优先：免费的排前面。
     *
     * @param free 是否免费
     * @param <T>  候选类型
     * @return 排序器
     */
    static <T> Ranker<T> freeFirst(Predicate<T> free) {
        return by(Comparator.comparingInt(item -> free.test(item) ? 0 : 1));
    }

    /**
     * 便宜优先：单价低的排前面。
     *
     * @param cost 单价提取器
     * @param <T>  候选类型
     * @return 排序器
     */
    static <T> Ranker<T> cheapestFirst(ToDoubleFunction<T> cost) {
        return by(Comparator.comparingDouble(cost));
    }

    /**
     * 快的优先：延迟低的排前面；未提供（<= 0）排最后。
     *
     * @param latency 延迟提取器（毫秒）
     * @param <T>     候选类型
     * @return 排序器
     */
    static <T> Ranker<T> fastestFirst(ToLongFunction<T> latency) {
        return by(Comparator.comparingLong(item -> {
            long value = latency.applyAsLong(item);
            return value <= 0 ? Long.MAX_VALUE : value;
        }));
    }

    /**
     * 权重高的优先。
     *
     * @param weight 权重提取器
     * @param <T>    候选类型
     * @return 排序器
     */
    static <T> Ranker<T> highestWeightFirst(ToDoubleFunction<T> weight) {
        return by(Comparator.comparingDouble(weight).reversed());
    }
}
