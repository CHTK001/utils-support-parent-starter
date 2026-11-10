package com.chua.common.support.task.cooldown;

/**
 * 冷却时长策略 —— 「第 N 次失败该排除多久」的唯一入口。
 *
 * <p>原来只有「基础时长 × 2^n、封顶」一种写法，写死在冷却引擎里；抽出来后
 * 固定时长、线性、指数、甚至业务自定义（lambda）都能按渠道挂上去：</p>
 *
 * <pre>{@code
 * CooldownBackoff.fixed(30_000L);                    // 永远 30 秒
 * CooldownBackoff.exponential(60_000L, 600_000L);    // 60s → 2m → 4m → 8m（封顶）
 * CooldownBackoff.linear(30_000L, 300_000L);         // 30s → 60s → 90s（封顶）
 * }</pre>
 *
 * <p>实现必须对 {@code failures <= 0} 返回一个安全值（视为第一次失败），
 * 并保证返回值不为负。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
@FunctionalInterface
public interface Backoff {

    /**
     * 指数退避允许的最大翻倍次数：2^4 = 16 倍
     */
    int MAX_SHIFT = 4;

    /**
     * 计算本次失败应有的冷却时长。
     *
     * @param failures  连续失败次数（从 1 开始）
     * @param baseMillis 基础时长（毫秒），非正数视为 0
     * @param maxMillis  封顶时长（毫秒），非正数表示不封顶
     * @return 冷却毫秒数，不小于 0
     */
    long delayMillis(int failures, long baseMillis, long maxMillis);

    /**
     * 固定时长：无论失败多少次都排除同一段时间。
     *
     * @return 策略
     */
    static Backoff fixed() {
        return (failures, baseMillis, maxMillis) -> cap(baseMillis, maxMillis);
    }

    /**
     * 指数退避：基础时长 × 2^n，n 最大 {@value #MAX_SHIFT}，再按封顶截断。
     *
     * @return 策略
     */
    static Backoff exponential() {
        return (failures, baseMillis, maxMillis) -> {
            long base = Math.max(0L, baseMillis);
            int shift = Math.min(Math.max(failures - 1, 0), MAX_SHIFT);
            return cap(base << shift, maxMillis);
        };
    }

    /**
     * 线性退避：基础时长 × 失败次数，再按封顶截断。
     *
     * @return 策略
     */
    static Backoff linear() {
        return (failures, baseMillis, maxMillis) -> {
            long base = Math.max(0L, baseMillis);
            long times = Math.max(1, failures);
            return cap(base * times, maxMillis);
        };
    }

    /**
     * 不冷却：只记录失败事件，不排除（降级策略仍然生效）。
     *
     * @return 策略
     */
    static Backoff none() {
        return (failures, baseMillis, maxMillis) -> 0L;
    }

    /**
     * 按名字取内置策略，未知或空白回落到指数退避。
     *
     * @param name 策略名：{@code fixed} / {@code exponential} / {@code linear} / {@code none}
     * @return 策略
     */
    static Backoff of(String name) {
        if (name == null || name.isBlank()) {
            return exponential();
        }
        return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "fixed" -> fixed();
            case "linear" -> linear();
            case "none" -> none();
            default -> exponential();
        };
    }

    /**
     * 按封顶截断，负值归零，{@code max <= 0} 表示不封顶。
     *
     * @param duration 原始时长
     * @param maxMillis 封顶时长
     * @return 截断后的时长
     */
    private static long cap(long duration, long maxMillis) {
        long safe = Math.max(0L, duration);
        return maxMillis <= 0 ? safe : Math.min(safe, maxMillis);
    }
}
