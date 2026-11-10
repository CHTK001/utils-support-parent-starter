package com.chua.common.support.task.cooldown;

/**
 * 一条冷却策略：基础时长 + 封顶时长 + 退避方式。
 *
 * <p>渠道 / 上游各自给一套，通过 {@link CooldownPolicies#register(String, CooldownPolicy)}
 * 按冷却键前缀挂上去。三个字段都允许缺省：</p>
 * <ul>
 *   <li>{@code baseMillis <= 0}：沿用作用域默认基础时长；</li>
 *   <li>{@code maxMillis <= 0}：沿用作用域默认封顶时长；</li>
 *   <li>{@code backoff == null}：沿用作用域默认退避方式（指数）。</li>
 * </ul>
 *
 * @param baseMillis 首次失败排除时长（毫秒）
 * @param maxMillis  连续失败封顶时长（毫秒）
 * @param backoff    退避方式，可为 null（沿用默认）
 * @author CH
 * @since 4.0.0.53
 */
public record CooldownPolicy(long baseMillis, long maxMillis, Backoff backoff) {

    /**
     * 只配时长、退避沿用默认的便捷构造。
     *
     * @param baseMillis 首次失败排除时长（毫秒）
     * @param maxMillis  连续失败封顶时长（毫秒）
     */
    public CooldownPolicy(long baseMillis, long maxMillis) {
        this(baseMillis, maxMillis, null);
    }

    /**
     * 是否三项都未配置（用于撤销注册）。
     *
     * @return true 表示没有任何有效配置
     */
    public boolean isEmpty() {
        return baseMillis <= 0 && maxMillis <= 0 && backoff == null;
    }
}
