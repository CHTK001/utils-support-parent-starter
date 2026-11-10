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
 * 基础/封顶时长与退避方式，互不串味。</p>
 *
 * <p>核心语义：</p>
 * <ul>
 *   <li><b>失败即排除</b>：{@link #fail(String, String)} 记一次失败，
 *       时长由 {@link Backoff} 策略决定（固定 / 指数 / 线性 / 不冷却）；</li>
 *   <li><b>到期自动恢复</b>：默认惰性过期（读取时清理）；
 *       需要主动通知时用 {@link #scheduler(ExpiryScheduler)} 换成时间轮；</li>
 *   <li><b>全部在冷却时半开放行</b>：{@link #filterOpen(List, Function)} 在全量
 *       候选都不可用时返回原候选集合；</li>
 *   <li><b>事件订阅</b>：{@link #subscribe(String, Consumer)} 订阅「刚刚被限流」，
 *       {@link #subscribeRecover(String, Consumer)} 订阅「刚刚恢复」。</li>
 * </ul>
 *
 * <p>带类型、带资格判断、带降级与排序的完整用法见 {@link Cooldown}。</p>
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
     * 默认作用域名
     */
    private static final String DEFAULT_SCOPE = "default";

    /**
     * 单个作用域的登记表容量上限
     */
    private static final int MAX_ENTRIES = 4096;

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
    private final Map<String, Consumer<String>> limitListeners = new ConcurrentHashMap<>(4);

    /**
     * 恢复事件订阅者：键为冷却键前缀
     */
    private final Map<String, Consumer<String>> recoverListeners = new ConcurrentHashMap<>(4);

    /**
     * 基础冷却时长（毫秒）
     */
    private volatile long baseMillis = DEFAULT_BASE_MS;

    /**
     * 最长冷却时长（毫秒）
     */
    private volatile long maxMillis = DEFAULT_MAX_MS;

    /**
     * 作用域默认退避方式
     */
    private volatile Backoff backoff = Backoff.exponential();

    /**
     * 到期调度器，默认惰性
     */
    private volatile ExpiryScheduler scheduler = ExpiryScheduler.lazy();

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
     * 覆盖本作用域默认的退避方式（渠道前缀策略优先）。
     *
     * @param backoff 退避方式；null 表示恢复指数退避
     * @return 当前句柄
     */
    public CooldownGate backoff(Backoff backoff) {
        this.backoff = backoff == null ? Backoff.exponential() : backoff;
        return this;
    }

    /**
     * 指定到期调度器（默认惰性；换时间轮可拿到主动恢复事件）。
     *
     * @param scheduler 调度器；null 表示恢复惰性
     * @return 当前句柄
     */
    public CooldownGate scheduler(ExpiryScheduler scheduler) {
        this.scheduler = scheduler == null ? ExpiryScheduler.lazy() : scheduler;
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
            // 到期即恢复：清理记录并通知恢复订阅者
            expire(key);
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
     * 取某个键的只读快照。
     *
     * @param key 冷却键
     * @return 快照；从未失败过返回空快照
     */
    public CooldownSnapshot snapshot(String key) {
        if (key == null) {
            return CooldownSnapshot.empty(null);
        }
        CooldownState state = states.get(key);
        if (state == null) {
            return CooldownSnapshot.empty(key);
        }
        long remaining = Math.max(0L, state.until - System.currentTimeMillis());
        return new CooldownSnapshot(key, state.failures, remaining, state.reason, remaining > 0);
    }

    /**
     * 到期清理：删状态并通知恢复订阅者，幂等。
     *
     * @param key 冷却键
     */
    public void expire(String key) {
        if (key == null) {
            return;
        }
        CooldownState state = states.get(key);
        if (state == null || state.until > System.currentTimeMillis()) {
            return;
        }
        if (states.remove(key, state)) {
            log.info("[Cooldown] {}:{} 冷却结束，恢复参与路由", scope, key);
            notify(recoverListeners, key);
        }
    }

    /**
     * 记一次失败，并按策略计算冷却时长。
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
            next.until = now + backoffMillis(key, next.failures);
            next.reason = reason == null || reason.isBlank() ? "unknown" : reason;
            return next;
        });
        long delay = state.until - now;
        log.warn("[Cooldown] {}:{} 第 {} 次失败，排除 {}s（原因: {}）", // [P3C A20 豁免]
                scope, key, state.failures, delay / 1000, state.reason);
        trimIfNecessary();
        if (notify) {
            notify(limitListeners, key);
        }
        if (delay > 0) {
            scheduler.schedule(scope, key, delay, () -> expire(key));
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
        if (key == null) {
            return this;
        }
        scheduler.cancel(scope, key);
        if (states.remove(key) != null) {
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
        subscribe0(limitListeners, prefix, onLimit);
        return this;
    }

    /**
     * 订阅某个前缀下的恢复事件（冷却到期或调用成功后触发）。
     *
     * @param prefix    订阅前缀
     * @param onRecover 回调，入参为完整冷却键；为 null 表示退订
     * @return 当前句柄
     */
    public CooldownGate subscribeRecover(String prefix, Consumer<String> onRecover) {
        subscribe0(recoverListeners, prefix, onRecover);
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
        scheduler.clearScope(scope);
        states.clear();
        limitListeners.clear();
        recoverListeners.clear();
        baseMillis = DEFAULT_BASE_MS;
        maxMillis = DEFAULT_MAX_MS;
        backoff = Backoff.exponential();
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
     * 计算第 N 次连续失败应排除的时长（作用域默认策略）。
     *
     * @param failures 连续失败次数（从 1 开始）
     * @return 冷却毫秒数
     */
    public long backoffMillis(int failures) {
        return backoffMillis(null, failures);
    }

    /**
     * 计算第 N 次连续失败应排除的时长：优先用键前缀命中的渠道策略，
     * 未命中或某一项未配置时回落到本作用域的参数。
     *
     * @param key      冷却键，可为 null（只算作用域默认值）
     * @param failures 连续失败次数（从 1 开始）
     * @return 冷却毫秒数
     */
    public long backoffMillis(String key, int failures) {
        CooldownPolicy policy = CooldownPolicies.resolve(key);
        long base = policy == null || policy.baseMillis() <= 0 ? baseMillis : policy.baseMillis();
        long max = policy == null || policy.maxMillis() <= 0 ? maxMillis : policy.maxMillis();
        Backoff chosen = policy == null || policy.backoff() == null ? backoff : policy.backoff();
        return Math.max(0L, chosen.delayMillis(failures, base, max));
    }

    /**
     * 注册订阅者。
     *
     * @param listeners 订阅表
     * @param prefix    前缀
     * @param consumer  回调
     */
    private void subscribe0(Map<String, Consumer<String>> listeners, String prefix, Consumer<String> consumer) {
        if (prefix == null) {
            return;
        }
        if (consumer == null) {
            listeners.remove(prefix);
        } else {
            listeners.put(prefix, consumer);
        }
    }

    /**
     * 通知前缀匹配的订阅者。
     *
     * @param listeners 订阅表
     * @param key       冷却键
     */
    private void notify(Map<String, Consumer<String>> listeners, String key) {
        for (Map.Entry<String, Consumer<String>> entry : listeners.entrySet()) {
            if (!key.startsWith(entry.getKey())) {
                continue;
            }
            try {
                entry.getValue().accept(key);
            } catch (Exception e) {
                log.debug("[Cooldown] 事件订阅者 {} 出错: {}", entry.getKey(), e.getMessage());
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
     * 便于日志排查：作用域与当前状态摘要。
     *
     * @return 摘要
     */
    @Override
    public String toString() {
        return "CooldownGate{" + scope + ", entries=" + states.size() + '}';
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
        private volatile long until;

        /**
         * 最近一次失败原因
         */
        private volatile String reason;
    }
}
