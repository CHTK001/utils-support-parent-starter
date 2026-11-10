package com.chua.common.support.task.cooldown;

import java.util.function.Function;

/**
 * 候选资格判断 —— 「这个对象现在有没有资格参与调用」。
 *
 * <p>与冷却的分工：资格失败是<b>配置 / 状态问题</b>（渠道停用、模型下线、
 * 账号过期、额度为 0），不该记冷却；冷却失败是<b>上游临时拒绝</b>（429、
 * 额度耗尽），要等一段时间再回来。两者混在一起，一个配置错误就会把整池账号
 * 全打成冷却。</p>
 *
 * <p>可组合：{@code Precondition.and(a, b)} / {@code a.and(b).negate()}。</p>
 *
 * @param <T> 候选类型
 * @author CH
 * @since 4.0.0.53
 */
@FunctionalInterface
public interface Precondition<T> {

    /**
     * 判断候选是否有资格。
     *
     * @param item 候选
     * @return true 表示有资格
     */
    boolean test(T item);

    /**
     * 与另一个条件取交集（短路）。
     *
     * @param other 另一个条件
     * @return 组合条件
     */
    default Precondition<T> and(Precondition<? super T> other) {
        return item -> test(item) && other.test(item);
    }

    /**
     * 与另一个条件取并集（短路）。
     *
     * @param other 另一个条件
     * @return 组合条件
     */
    default Precondition<T> or(Precondition<? super T> other) {
        return item -> test(item) || other.test(item);
    }

    /**
     * 取反。
     *
     * @return 取反后的条件
     */
    default Precondition<T> negate() {
        return item -> !test(item);
    }

    /**
     * 恒为真。
     *
     * @param <T> 候选类型
     * @return 条件
     */
    static <T> Precondition<T> always() {
        return item -> true;
    }

    /**
     * 恒为假（用于临时全禁）。
     *
     * @param <T> 候选类型
     * @return 条件
     */
    static <T> Precondition<T> never() {
        return item -> false;
    }

    /**
     * 取值的条件：提取的字段非 null 即通过。
     *
     * @param getter 字段提取器
     * @param <T>    候选类型
     * @param <V>    字段类型
     * @return 条件
     */
    static <T, V> Precondition<T> notNull(Function<? super T, ? extends V> getter) {
        return item -> item != null && getter.apply(item) != null;
    }

    /**
     * 布尔字段的条件：提取值为 {@link Boolean#TRUE} 才通过。
     *
     * @param getter 字段提取器
     * @param <T>    候选类型
     * @return 条件
     */
    static <T> Precondition<T> isTrue(Function<? super T, Boolean> getter) {
        return item -> item != null && Boolean.TRUE.equals(getter.apply(item));
    }
}
