package com.chua.common.support.task.cooldown;

/**
 * 单个冷却键的只读快照，供页面展示「还剩多久 / 为什么」。
 *
 * @param key             冷却键
 * @param failures        连续失败次数
 * @param remainingMillis 剩余冷却毫秒数，未冷却为 0
 * @param reason          最近一次失败原因，未冷却为 {@code null}
 * @param blocked         当前是否仍在冷却期
 * @author CH
 * @since 4.0.0.53
 */
public record CooldownSnapshot(String key, int failures, long remainingMillis,
                               String reason, boolean blocked) {

    /**
     * 空快照：该键从未失败过。
     *
     * @param key 冷却键
     * @return 快照
     */
    public static CooldownSnapshot empty(String key) {
        return new CooldownSnapshot(key, 0, 0L, null, false);
    }
}
