package com.chua.common.support.task.cooldown;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 冷却门面 —— 「失败先排除一段时间、到期自动恢复」的统一入口。
 *
 * <p>与 {@link com.chua.common.support.task.async.AsyncFlow} 同一套门面风格：
 * 用 {@link #of(String)} 取一个作用域句柄，之后所有判定与记账都走这个句柄。
 * 不同作用域（不同渠道、不同上游、不同路由组）各自一份状态、各自一套
 * 基础/封顶时长，互不串味。</p>
 *
 * <p>核心语义（与原先的 {@code ClientCooldownRegistry} 完全一致）：</p>
 * <ul>
 *   <li><b>失败即排除</b>：{@link #fail(String, String)} 记一次失败，
 *       首次排除基础时长；</li>
 *   <li><b>连续失败翻倍</b>：同一键连续失败按 2 的幂次递增
 *       （基础 × 2^n），到封顶时长为止；一次成功立即清零，下次重新从基础时长开始；</li>
 *   <li><b>到期自动恢复</b>：不需要定时任务或外部触发，
 *       {@link #blocked(String)} 读到过期记录时顺手清掉；</li>
 *   <li><b>全部在冷却时半开放行</b>：{@link #filterOpen(List, Function)} 在全量
 *       候选都不可用时返回原候选集合，宁可再撞一次限流也不让整体不可用；</li>
 *   <li><b>限流事件订阅</b>：{@link #subscribe(String, Consumer)} 按前缀订阅
 *       「刚刚被限流」的键，供「排到最后 / 保持原位」这类路由策略使用。</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * CooldownGate gate = CooldownGate.of("channel:qoder").configure(60_000L, 600_000L);
 * if (!gate.blocked(accountKey)) {
 *     // 本次放行
 * }
 * gate.fail(accountKey, "HTTP 429 Too Many Requests");
 * gate.pass(accountKey);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.53
 */
@Slf4j
public final class CooldownGate {

    /**
     * 默认基础冷却时长（毫秒）：首次失败排除 60 秒
     */
    public static final long DEFAULT_BASE_MS = 60_000L;

    /**
     * 默认最长冷却时长（毫秒）：连续失败封顶 10 分钟
     */
    public static final long DEFAULT_MAX_MS = 600_000L;

    /**
     * 连续失败翻倍次数上限：60s → 2m → 4m → 8m，再乘就到顶了
     */
    private static final int MAX_BACKOFF_SHIFT = 4;

    /**
     * 单个作用域的登记表容量上限
     */
    private static final int MAX_ENTRIES = 4096;

    /**
     * 默认作用域名
     */
    private static final String DEFAULT_SCOPE = "default";

    /**
     * 全局作用域登记表：同名作用域共用同一份冷却状态
     */
    private static final Map<String, CooldownGate> SCOPES = new ConcurrentHashMap<>(8);

    /**
     * 作用域名
     */
    private final String scope;

    /**
     * 冷却状态：键为调用方自定的稳定标识（渠道|账号、provider|model|key 摘要等）
     */
    private final Map<String, CooldownState> states = new ConcurrentHashMap<>(16);

    /**
     * 限流事件订阅者：键为冷却键前缀
     */
    private final Map<String, Consumer<String>> listeners = new ConcurrentHashMap<>(4);

    /**
     * 基础冷却时长（毫秒）
     */
    private volatile long baseMillis = DEFAULT_BASE_MS;

    /**
     * 最长冷却时长（毫秒）
     */
    private volatile long maxMillis = DEFAULT_MAX_MS;

    /**
     * 私有构造：统一走 {@link #of(String)}，保证同名作用域状态共享。
     *
     * @param scope 作用域名
     */
    private CooldownGate(String scope) {
        this.scope = scope;
    }

    /**
     * 取一个作用域句柄（门面入口，与 {@code AsyncFlow.of()} 同风格）。
     *
     * <p>同名作用域返回同一个实例，因此可以放心在每个调用点重新取；
     * 传 {@code null} 或空白串时落到 {@code default} 作用域。</p>
     *
     * @param scope 作用域名，建议用 {@code 渠道:标识} 形式
     * @return 作用域句柄
     */
    public static CooldownGate of(String scope) {
        String name = scope == null || scope.isBlank() ? DEFAULT_SCOPE : scope;
        return SCOPES.computeIfAbsent(name, CooldownGate::new);
    }

    /**
     * 覆盖本作用域的冷却参数。
     *
     * <p>非正数表示使用默认值；{@code maxMillis} 小于 {@code baseMillis} 时按基础值封顶。</p>
     *
     * @param base 首次失败冷却时长（毫秒）
     * @param max  连续失败冷却上限（毫秒）
     * @return 当前句柄，便于链式配置
     */
    public CooldownGate configure(long base, long max) {
        this.baseMillis = base > 0 ? base : DEFAULT_BASE_MS;
        this.maxMillis = max > 0 ? max : DEFAULT_MAX_MS;
        return this;
    }

    /**
     * 该键当前是否处于冷却期。
     *
     * @param key 冷却键
     * @return true 表示应跳过
     */
    public boolean blocked(String key) {
        if (key == null) {
            return false;
        }
        CooldownState state = states.get(key);
        if (state == null) {
            return false;
        }
        if (state.until <= System.currentTimeMillis()) {
            // 到期即恢复：清理记录，下次失败重新从基础时长开始
            states.remove(key, state);
            log.info("[Cooldown] {}:{} 冷却结束，恢复参与路由", scope, key);
            return false;
        }
        return true;
    }

    /**
     * 该键剩余冷却时长（毫秒），未冷却返回 0。
     *
     * @param key 冷却键
     * @return 剩余毫秒数
     */
    public long remaining(String key) {
        if (key == null) {
            return 0L;
        }
        CooldownState state = states.get(key);
        if (state == null) {
            return 0L;
        }
        return Math.max(0L, state.until - System.currentTimeMillis());
    }

    /**
     * 记一次失败，并按连续失败次数计算冷却时长。
     *
     * @param key    冷却键
     * @param reason 失败原因，可为 null
     * @return 当前句柄
     */
    public CooldownGate fail(String key, String reason) {
        return fail(key, reason, true);
    }

    /**
     * 记一次失败，可指定是否广播限流事件。
     *
     * <p>「连不上上游」这类失败仍会短时冷却，但不该触发「被限流就换号」的
     * 路由策略，调用方传 {@code notify=false} 区分。</p>
     *
     * @param key    冷却键
     * @param reason 失败原因，可为 null
     * @param notify 是否广播给前缀订阅者
     * @return 当前句柄
     */
    public CooldownGate fail(String key, String reason, boolean notify) {
        if (key == null) {
            return this;
        }
        long now = System.currentTimeMillis();
        CooldownState state = states.compute(key, (k, old) -> {
            CooldownState next = old == null ? new CooldownState() : old;
            next.failures++;
            next.until = now + backoffMillis(next.failures);
            next.reason = reason == null || reason.isBlank() ? "unknown" : reason;
            return next;
        });
        log.warn("[Cooldown] {}:{} 第 {} 次失败，排除 {}s（原因: {}）", // [P3C A20 豁免]
                scope, key, state.failures, (state.until - now) / 1000, state.reason);
        trimIfNecessary();
        if (notify) {
            notifyFailure(key, reason);
        }
        return this;
    }

    /**
     * 记一次成功：立即解除该键的冷却。
     *
     * @param key 冷却键
     * @return 当前句柄
     */
    public CooldownGate pass(String key) {
        if (key != null && states.remove(key) != null) {
            log.info("[Cooldown] {}:{} 调用成功，冷却已解除", scope, key);
        }
        return this;
    }

    /**
     * 订阅某个前缀下的失败事件（如 {@code provider|model|}）。
     *
     * @param prefix  订阅前缀（冷却键以它开头即命中）
     * @param onLimit 回调，入参为完整冷却键；为 null 表示退订
     * @return 当前句柄
     */
    public CooldownGate subscribe(String prefix, Consumer<String> onLimit) {
        if (prefix == null) {
            return this;
        }
        if (onLimit == null) {
            listeners.remove(prefix);
        } else {
            listeners.put(prefix, onLimit);
        }
        return this;
    }

    /**
     * 从候选中挑出当前不在冷却期的元素。
     *
     * <p>若所有候选都在冷却中，直接放行全部（半开）：宁可再撞一次限流，
     * 也不能让整体服务不可用。</p>
     *
     * @param items 候选列表
     * @param keyOf 从元素取冷却键
     * @param <T>   元素类型
     * @return 可参与本次调用的元素列表
     */
    public <T> List<T> filterOpen(List<T> items, Function<T, String> keyOf) {
        if (items == null || items.isEmpty() || keyOf == null) {
            return items;
        }
        List<T> healthy = new ArrayList<>(items.size());
        for (T item : items) {
            if (!blocked(keyOf.apply(item))) {
                healthy.add(item);
            }
        }
        if (healthy.isEmpty()) {
            log.warn("[Cooldown] {} 全部 {} 个候选均在冷却中，本次降级为半开放行", scope, items.size());
            return items;
        }
        if (healthy.size() < items.size()) {
            log.info("[Cooldown] {} 本次跳过 {} 个冷却中的候选，剩余 {}",
                    scope, items.size() - healthy.size(), healthy.size());
        }
        return healthy;
    }

    /**
     * 当前登记条目数，供监控/测试断言。
     *
     * @return 条目数
     */
    public int size() {
        return states.size();
    }

    /**
     * 清空本作用域的全部冷却记录与订阅，并恢复默认参数（主要供测试使用）。
     *
     * @return 当前句柄
     */
    public CooldownGate reset() {
        states.clear();
        listeners.clear();
        baseMillis = DEFAULT_BASE_MS;
        maxMillis = DEFAULT_MAX_MS;
        return this;
    }

    /**
     * 取作用域名。
     *
     * @return 作用域名
     */
    public String scope() {
        return scope;
    }

    /**
     * 计算第 N 次连续失败应排除的时长。
     *
     * @param failures 连续失败次数（从 1 开始）
     * @return 冷却毫秒数
     */
    public long backoffMillis(int failures) {
        int shift = Math.min(Math.max(failures - 1, 0), MAX_BACKOFF_SHIFT);
        long duration = baseMillis << shift;
        long cap = Math.max(baseMillis, maxMillis);
        return Math.min(duration, cap);
    }

    /**
     * 失败通知订阅者。
     *
     * @param key    冷却键
     * @param reason 失败原因
     */
    private void notifyFailure(String key, String reason) {
        for (Map.Entry<String, Consumer<String>> entry : listeners.entrySet()) {
            if (!key.startsWith(entry.getKey())) {
                continue;
            }
            try {
                entry.getValue().accept(key);
            } catch (Exception e) {
                log.debug("[Cooldown] 失败通知订阅者 {} 出错: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * 超过容量上限时清理已到期记录。
     */
    private void trimIfNecessary() {
        if (states.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(entry -> entry.getValue().until <= now);
        if (states.size() > MAX_ENTRIES) {
            log.warn("[Cooldown] {} 登记表条目超过上限 {}，已清理到期记录后仍超限: {}",
                    scope, MAX_ENTRIES, states.size());
        }
    }

    /**
     * 单个键的冷却状态。
     */
    private static final class CooldownState {

        /**
         * 连续失败次数
         */
        private int failures;

        /**
         * 冷却截止时间戳（毫秒）
         */
        private long until;

        /**
         * 最近一次失败原因
         */
        private String reason;
    }

    /**
     * 便于日志排查：作用域与当前状态摘要。
     *
     * @return 摘要
     */
    @Override
    public String toString() {
        return "CooldownGate{" + scope + ", entries=" + states.size() + '}';
    }
}
