package com.chua.common.support.task.cooldown;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按冷却键前缀挂载的自定义冷却策略。
 *
 * <p>「每个渠道自己一套限流恢复节奏」的落点：调用方在构建渠道客户端时把该渠道的
 * 策略注册到自己的键前缀上（如 {@code qoder|}、{@code 1789547286889|}），
 * 冷却引擎在记账时按<b>最长匹配前缀</b>取策略，取不到就沿用作用域默认值。</p>
 *
 * <p>用前缀而不是精确键：冷却键里带账号（或 Key 摘要），策略是渠道级的，
 * 一个渠道下所有账号共用同一套节奏。</p>
 *
 * <p>注册空策略（三项都未配置）表示撤销该前缀，避免配置删了却还在生效。</p>
 *
 * @author CH
 * @since 4.0.0.53
 */
public final class CooldownPolicies {

    /**
     * 前缀 → 策略
     */
    private static final Map<String, CooldownPolicy> POLICIES = new ConcurrentHashMap<>(8);

    private CooldownPolicies() {
    }

    /**
     * 只配时长、退避沿用默认的便捷注册。
     *
     * @param prefix     冷却键前缀，如 {@code qoder|}；空白串直接忽略
     * @param baseMillis 首次失败排除时长（毫秒），非正数表示沿用默认
     * @param maxMillis  连续失败封顶时长（毫秒），非正数表示沿用默认
     */
    public static void register(String prefix, long baseMillis, long maxMillis) {
        register(prefix, new CooldownPolicy(baseMillis, maxMillis));
    }

    /**
     * 注册（或撤销）某个冷却键前缀的策略。
     *
     * @param prefix 冷却键前缀；空白串直接忽略
     * @param policy 策略；{@code null} 或空策略表示撤销
     */
    public static void register(String prefix, CooldownPolicy policy) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        if (policy == null || policy.isEmpty()) {
            POLICIES.remove(prefix);
        } else {
            POLICIES.put(prefix, policy);
        }
    }

    /**
     * 按冷却键取策略：最长匹配前缀优先。
     *
     * @param key 冷却键，如 {@code qoder|claude|key-abc}
     * @return 命中的策略；没有命中返回 {@code null}
     */
    public static CooldownPolicy resolve(String key) {
        if (key == null || POLICIES.isEmpty()) {
            return null;
        }
        CooldownPolicy matched = null;
        int matchedLength = -1;
        for (Map.Entry<String, CooldownPolicy> entry : POLICIES.entrySet()) {
            String prefix = entry.getKey();
            if (prefix.length() > matchedLength && key.startsWith(prefix)) {
                matched = entry.getValue();
                matchedLength = prefix.length();
            }
        }
        return matched;
    }

    /**
     * 当前已注册的策略条数，供监控 / 测试断言。
     *
     * @return 条数
     */
    public static int size() {
        return POLICIES.size();
    }

    /**
     * 清空全部策略，主要供测试使用。
     */
    public static void clear() {
        POLICIES.clear();
    }
}
