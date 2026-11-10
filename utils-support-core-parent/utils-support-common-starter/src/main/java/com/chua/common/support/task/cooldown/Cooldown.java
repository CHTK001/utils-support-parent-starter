package com.chua.common.support.task.cooldown;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 带类型、带资格判断、带降级与排序的冷却门面。
 *
 * <p>面向调用方的「一次调用该用谁」：把候选数组丢进来，拿回当前能用的对象。</p>
 *
 * <pre>{@code
 * Cooldown<Account> cooldown = Cooldown.of("channel:qoder", Account::key)
 *         .configure(60_000L, 600_000L)
 *         .backoff(Backoff.exponential())
 *         .precondition(Precondition.isTrue(Account::enabled))
 *         .degrade(DegradePolicy.openAll())
 *         .rank(Ranker.expiringFirst(Account::expiresAt), Ranker.freeFirst(Account::free));
 *
 * List<Account> usable = cooldown.select(allAccounts);
 * cooldown.fail(used, "429");
 * }</pre>
 *
 * <p>三段流水线：<b>资格</b>（precondition）→ <b>冷却</b>（gate）→ <b>降级</b>
 * （degrade，仅当无可用时）。排序只作用于能用的候选，不改变冷却状态。</p>
 *
 * <p>内部只保留「键 → 冷却状态」，不持有候选对象本身：渠道客户端有 TTL 重建、
 * 账号是远端投影，门面持有引用只会拿到过期实例。</p>
 *
 * @param <T> 候选类型
 * @author CH
 * @since 4.0.0.53
 */
public final class Cooldown<T> {

    /**
     * 底层状态引擎
     */
    private final CooldownGate gate;

    /**
     * 候选 → 冷却键
     */
    private final Function<T, String> keyOf;

    /**
     * 资格判断
     */
    private Precondition<T> precondition = Precondition.always();

    /**
     * 降级策略
     */
    private DegradePolicy<T> degrade = DegradePolicy.openAll();

    /**
     * 排序链
     */
    private final List<Ranker<T>> rankers = new ArrayList<>(4);

    /**
     * 构造。
     *
     * @param gate  状态引擎
     * @param keyOf 冷却键提取器
     */
    private Cooldown(CooldownGate gate, Function<T, String> keyOf) {
        this.gate = gate;
        this.keyOf = keyOf;
    }

    /**
     * 取一个类型化冷却门面（同 scope 与 {@link CooldownGate#of(String)} 共用状态）。
     *
     * @param scope 作用域名，建议 {@code channel:渠道标识}
     * @param keyOf 候选 → 冷却键
     * @param <T>   候选类型
     * @return 门面
     */
    public static <T> Cooldown<T> of(String scope, Function<T, String> keyOf) {
        return new Cooldown<>(CooldownGate.of(scope), Objects.requireNonNull(keyOf, "keyOf"));
    }

    /**
     * 覆盖基础 / 封顶时长。
     *
     * @param baseMillis 首次失败排除时长
     * @param maxMillis  连续失败封顶时长
     * @return 当前门面
     */
    public Cooldown<T> configure(long baseMillis, long maxMillis) {
        gate.configure(baseMillis, maxMillis);
        return this;
    }

    /**
     * 覆盖默认退避方式。
     *
     * @param backoff 退避方式
     * @return 当前门面
     */
    public Cooldown<T> backoff(Backoff backoff) {
        gate.backoff(backoff);
        return this;
    }

    /**
     * 指定到期调度器（默认惰性，换时间轮可拿恢复事件与先进先出）。
     *
     * @param scheduler 调度器
     * @return 当前门面
     */
    public Cooldown<T> scheduler(ExpiryScheduler scheduler) {
        gate.scheduler(scheduler);
        return this;
    }

    /**
     * 设置资格判断。
     *
     * @param precondition 条件；null 表示不限制
     * @return 当前门面
     */
    public Cooldown<T> precondition(Precondition<T> precondition) {
        this.precondition = precondition == null ? Precondition.always() : precondition;
        return this;
    }

    /**
     * 设置降级策略。
     *
     * @param degrade 策略；null 表示半开放行
     * @return 当前门面
     */
    public Cooldown<T> degrade(DegradePolicy<T> degrade) {
        this.degrade = degrade == null ? DegradePolicy.openAll() : degrade;
        return this;
    }

    /**
     * 追加排序器，按添加顺序依次比较。
     *
     * @param next 排序器
     * @return 当前门面
     */
    @SafeVarargs
    public final Cooldown<T> rank(Ranker<T>... next) {
        if (next != null) {
            for (Ranker<T> ranker : next) {
                if (ranker != null) {
                    rankers.add(ranker);
                }
            }
        }
        return this;
    }

    /**
     * 选出当前可用的候选（已排序）；全部不可用时按降级策略返回。
     *
     * @param candidates 候选数组，可为 null
     * @return 可用候选；不会返回 null
     */
    public List<T> select(T[] candidates) {
        return candidates == null ? List.of() : select(Arrays.asList(candidates));
    }

    /**
     * 选出当前可用的候选（已排序）；全部不可用时按降级策略返回。
     *
     * @param candidates 候选列表，可为 null
     * @return 可用候选；不会返回 null
     */
    public List<T> select(List<T> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<T> qualified = new ArrayList<>(candidates.size());
        for (T item : candidates) {
            if (item != null && precondition.test(item)) {
                qualified.add(item);
            }
        }
        List<T> usable = new ArrayList<>(qualified.size());
        for (T item : qualified) {
            if (!gate.blocked(keyOf.apply(item))) {
                usable.add(item);
            }
        }
        if (usable.isEmpty()) {
            return degrade.resolve(candidates, qualified, usable);
        }
        if (rankers.isEmpty()) {
            return usable;
        }
        usable.sort(comparator());
        return usable;
    }

    /**
     * 选出第一个可用候选；没有可用时按降级策略取第一个，仍没有返回 null。
     *
     * @param candidates 候选列表
     * @return 候选；没有返回 null
     */
    public T first(List<T> candidates) {
        List<T> selected = select(candidates);
        return selected.isEmpty() ? null : selected.getFirst();
    }

    /**
     * 该候选当前是否可用（通过资格且不在冷却期）。
     *
     * @param item 候选
     * @return true 表示可用
     */
    public boolean canUse(T item) {
        return item != null && precondition.test(item) && !gate.blocked(keyOf.apply(item));
    }

    /**
     * 是否所有候选都被限制（资格或冷却，不计降级）。
     *
     * @param candidates 候选列表
     * @return true 表示没有一个可用
     */
    public boolean allLimited(List<T> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }
        for (T item : candidates) {
            if (canUse(item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否所有候选都被<b>资格</b>拒掉（硬性不可用）。
     *
     * <p>与 {@link #allLimited(List)} 的区别：全部在冷却时可以半开，
     * 全部没资格时半开也没意义。</p>
     *
     * @param candidates 候选列表
     * @return true 表示全部没资格
     */
    public boolean exhausted(List<T> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }
        for (T item : candidates) {
            if (item != null && precondition.test(item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 该候选剩余冷却时长。
     *
     * @param item 候选
     * @return 毫秒；未冷却为 0
     */
    public long remaining(T item) {
        return item == null ? 0L : gate.remaining(keyOf.apply(item));
    }

    /**
     * 记一次失败。
     *
     * @param item  失败的候选
     * @param error 异常，可为 null
     * @return 当前门面
     */
    public Cooldown<T> fail(T item, Throwable error) {
        if (item == null) {
            return this;
        }
        gate.fail(keyOf.apply(item), error == null ? null : error.getMessage());
        return this;
    }

    /**
     * 记一次失败。
     *
     * @param item   失败的候选
     * @param reason 原因
     * @return 当前门面
     */
    public Cooldown<T> fail(T item, String reason) {
        if (item != null) {
            gate.fail(keyOf.apply(item), reason);
        }
        return this;
    }

    /**
     * 记一次成功：立即解除冷却。
     *
     * @param item 成功的候选
     * @return 当前门面
     */
    public Cooldown<T> pass(T item) {
        if (item != null) {
            gate.pass(keyOf.apply(item));
        }
        return this;
    }

    /**
     * 取候选的冷却快照。
     *
     * @param item 候选
     * @return 快照
     */
    public CooldownSnapshot snapshot(T item) {
        return gate.snapshot(item == null ? null : keyOf.apply(item));
    }

    /**
     * 取底层状态引擎（需要按前缀订阅事件时用）。
     *
     * @return 状态引擎
     */
    public CooldownGate gate() {
        return gate;
    }

    /**
     * 组合排序器。
     *
     * @return 比较器
     */
    private Comparator<T> comparator() {
        Comparator<T> combined = rankers.getFirst().comparator();
        for (int i = 1; i < rankers.size(); i++) {
            combined = combined.thenComparing(rankers.get(i).comparator());
        }
        return combined;
    }
}
