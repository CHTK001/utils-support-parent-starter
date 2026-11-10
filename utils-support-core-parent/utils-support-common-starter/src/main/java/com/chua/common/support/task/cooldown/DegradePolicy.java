package com.chua.common.support.task.cooldown;

import java.util.List;
import java.util.function.Supplier;

/**
 * 降级策略 —— 候选全部不可用时该怎么办。
 *
 * <p>三种内置语义，按渠道 / 调用点可配：</p>
 * <ul>
 *   <li>{@link #openAll()}：半开放行。全部在冷却时把「有资格」的候选原样放行，
 *       宁可再撞一次限流，也不让整体不可用（现有行为）；</li>
 *   <li>{@link #reject()}：直接返回空列表，由调用方报可读错误，不撞上游；</li>
 *   <li>{@link #fallback(Supplier)}：换备用池（备用渠道 / 兜底账号 / 本地模型）。</li>
 * </ul>
 *
 * <p>「全部」的判定不包含资格失败：资格全挂时 {@code qualified} 为空，
 * 半开也不会把没资格的候选放出去。</p>
 *
 * @param <T> 候选类型
 * @author CH
 * @since 4.0.0.53
 */
@FunctionalInterface
public interface DegradePolicy<T> {

    /**
     * 决定最终返回哪些候选。
     *
     * @param candidates 原始候选
     * @param qualified  通过资格判断的候选
     * @param usable     通过资格且不在冷却期的候选（此时通常为空）
     * @return 最终候选列表
     */
    List<T> resolve(List<T> candidates, List<T> qualified, List<T> usable);

    /**
     * 半开放行：把有资格的候选全部放回。
     *
     * @param <T> 候选类型
     * @return 策略
     */
    static <T> DegradePolicy<T> openAll() {
        return (candidates, qualified, usable) -> qualified;
    }

    /**
     * 拒绝：返回空列表。
     *
     * @param <T> 候选类型
     * @return 策略
     */
    static <T> DegradePolicy<T> reject() {
        return (candidates, qualified, usable) -> List.of();
    }

    /**
     * 兜底池：从另一个来源取候选。
     *
     * @param supplier 备用候选提供者，返回 null 视为空
     * @param <T>      候选类型
     * @return 策略
     */
    static <T> DegradePolicy<T> fallback(Supplier<? extends List<T>> supplier) {
        return (candidates, qualified, usable) -> {
            List<T> alternate = supplier == null ? null : supplier.get();
            return alternate == null ? List.of() : alternate;
        };
    }
}
