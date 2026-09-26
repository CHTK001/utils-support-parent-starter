package com.chua.common.support.rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 规则断路器。
 *
 * <p>把规则引擎压缩成一个「放行 / 断路」的布尔门禁，
 * 是 {@code com.chua.common.support.concurrent.circuit.CircuitBreaker}
 * （表达式断路器）在规则引擎上的对应物：两者门面形状一致，
 * 便于在不改调用方代码的前提下灰度替换。</p>
 *
 * <h3>与表达式断路器的本质区别</h3>
 * <p>表达式断路器是<b>单条、无状态、一次性、布尔输出</b>的求值器：
 * 表达式经 SPI 解析为 B-Tree，叶子由判断器求值，短路得到一个 boolean；
 * 状态（计数、限流窗口等）必须由调用方自己塞进 {@code context}。
 * 它是本引擎的一个退化特例：N 条规则塌缩成 1 条、多轮推理塌缩成单轮、
 * 动作塌缩成 boolean、没有竞争因此也没有「谁说了算」。</p>
 *
 * <p>规则断路器则是：</p>
 * <ul>
 *   <li><b>多条规则竞争</b> — salience / agenda-group / activation-group 决定谁说话</li>
 *   <li><b>有记忆</b> — 工作内存中的 {@link Fact} 跨请求存活，规则可依赖历史</li>
 *   <li><b>多轮推理</b> — 动作可改事实，衍生出新的激活，直到不动点</li>
 *   <li><b>输出不止布尔</b> — 结论之外还有结果条目、列表与统计</li>
 * </ul>
 *
 * <h3>结论来源</h3>
 * <ul>
 *   <li>规则通过 {@link Actions#allow()} / {@link Actions#deny()} 产出结论</li>
 *   <li>无规则产出结论时，按 {@link Builder#denyWhenNoConclusion(boolean)} 决定：
 *       <b>默认放行</b>（{@code denyWhenNoConclusion} 默认为 {@code false}），
 *       语义是「没有规则拦截即视为通过」。
 *       {@link DynamicBuilder} 采用相同默认值；需要保守拒绝时
 *       必须显式调用 {@code denyWhenNoConclusion(true)}</li>
 *   <li>推理过程抛异常时，按 {@link Builder#failClosed(boolean)} 决定：
 *       默认断路，与表达式断路器「异常返回 false」的保守语义一致</li>
 * </ul>
 *
 * <h3>异常边界</h3>
 * <p>fail-closed 只兜住 {@link RuntimeException}。规则内部抛出的
 * {@code RuntimeException} 由引擎逐条隔离并计入 {@code failedCount}；
 * 而议程构建阶段（如动态 salience 计算）抛出的 {@code RuntimeException}
 * 会逃逸出 {@code fire()}，此时按 fail-closed 语义转为断路。</p>
 * <p>{@link Error}（{@code OutOfMemoryError}、{@code StackOverflowError} 等）
 * <b>不</b>吞掉，直接向上抛出——JVM 级故障不应被伪装成一次正常的门禁结论。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 最小用法：一条规则一个结论
 * Rule denyBlocked = Rule.builder("黑名单断路")
 *         .when(Pattern.of("user", UserFact.class, UserFact::isBlocked))
 *         .then(Actions.deny())
 *         .build();
 *
 * RuleBreaker breaker = RuleBreaker.of(denyBlocked);
 * boolean pass = breaker.evaluate(new UserFact("u1"));
 *
 * // 带全局数据与多规则竞争
 * RuleBreaker rich = RuleBreaker.builder(ruleBase)
 *         .globals(Map.of("maxAmount", 100_000))
 *         .denyWhenNoConclusion(false)
 *         .build();
 *
 * // 规则可热更新：长期持有的门禁自动跟随新规则
 * RuleBreaker live = RuleBreaker.dynamic(() ->
 *         new RuleBreaker.RuleSnapshot(repository.current(), repository.globals()));
 * }</pre>
 *
 * <h3>需要多轮或多次推理时</h3>
 * <p>门面每次 {@code evaluate} 使用一个全新的会话。
 * 若需要在同一批事实上反复推理、或自己掌控工作内存，
 * 请直接使用 {@link #newSession()} 拿裸会话。</p>
 *
 * <h3>规则来源：快照 与 动态</h3>
 * <p>本门面有两种规则来源，区别只在「规则集从哪来」：</p>
 * <ul>
 *   <li><b>快照</b>（{@link #of(RuleBase)}、{@link #builder(RuleBase)}）：
 *       创建时固定当时的规则集。规则热更新后该实例<b>不会</b>跟随，
 *       需要调用方自己重建。</li>
 *   <li><b>动态</b>（{@link #dynamic(Supplier)}）：每次求值开始时
 *       重新取一次 {@link RuleSnapshot}，因此长期持有的实例也能
 *       立即用上热更新后的规则。规则集来自可热更新的配置源
 *       （如 {@code RuleRepository}）时应当用它。</li>
 * </ul>
 *
 * <p><b>动态不会破坏单次判定的一致性</b>：快照在一次求值内只取一次
 * （一次 {@code verdict()} 只建一个会话），所以同一次判定里
 * 所有条件必然基于同一份规则集；规则集与其 globals 又成对封装在
 * {@link RuleSnapshot} 中，不会出现「阈值已更新而 {@code g.max}
 * 仍是旧值」这类前后矛盾。</p>
 *
 * <h3>线程安全</h3>
 * <p>{@link RuleBase} 不可变可共享，本门面亦无可变状态，因此门面线程安全；
 * 每次求值使用独立会话，彼此不干扰。仅当通过 {@link #newSession()}
 * 自行持有会话时，才需自行保证单线程使用。
 * 动态门面的规则来源（{@code Supplier}）是否线程安全由提供方负责——
 * {@code RuleRepository} 用 {@link java.util.concurrent.atomic.AtomicReference}
 * 换入，因此是安全的。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleBreaker {

    /**
     * 放行结论
     */
    public static final String ALLOW = Actions.ALLOW;

    /**
     * 断路结论
     */
    public static final String DENY = Actions.DENY;

    /**
     * 规则快照提供者。
     *
     * <p>普通断路器返回固定快照；由 {@code RuleRepository} 背书的
     * 动态断路器每次求值都重新读取，从而自动跟随热更新。</p>
     */
    private final Supplier<RuleSnapshot> snapshots;

    /**
     * 会话配置
     */
    private final RuleSessionConfig sessionConfig;

    /**
     * 无结论时是否断路
     */
    private final boolean denyWhenNoConclusion;

    /**
     * 推理异常时是否断路
     */
    private final boolean failClosed;

    /**
     * 事件监听器
     */
    private final List<RuleListener> listeners;

    /**
     * 规则快照：一次求值所依据的不可变规则集与其全局变量。
     *
     * <p>把规则集与全局变量打包在一起，是为了保证「同一次求值里
     * 规则和 globals 来自同一版本」——否则规则用新阈值、
     * 表达式却读到旧 globals，判定结果将无法解释。</p>
     *
     * @param ruleBase 规则库
     * @param globals  全局变量
     */
    public record RuleSnapshot(RuleBase ruleBase, Map<String, Object> globals) {

        /**
         * 紧凑构造器：校验规则库非空。
         */
        public RuleSnapshot {
            if (ruleBase == null) {
                throw new RuleException("规则库不能为 null");
            }
            globals = globals == null ? Map.of() : Map.copyOf(globals);
        }
    }

    /**
     * 创建规则断路器。
     *
     * @param snapshots            规则快照提供者
     * @param sessionConfig        会话配置
     * @param denyWhenNoConclusion 无结论时是否断路
     * @param failClosed           推理异常时是否断路
     * @param listeners            事件监听器
     */
    private RuleBreaker(Supplier<RuleSnapshot> snapshots, RuleSessionConfig sessionConfig,
            boolean denyWhenNoConclusion, boolean failClosed, List<RuleListener> listeners) {
        this.snapshots = snapshots;
        this.sessionConfig = sessionConfig;
        this.denyWhenNoConclusion = denyWhenNoConclusion;
        this.failClosed = failClosed;
        this.listeners = listeners;
    }

    /**
     * 由单条规则创建断路器。
     *
     * @param rule 规则
     * @return 规则断路器
     */
    public static RuleBreaker of(Rule rule) {
        if (rule == null) {
            throw new RuleException("规则不能为 null");
        }
        return builder(RuleBase.of(rule)).build();
    }

    /**
     * 由多条规则创建断路器。
     *
     * @param rules 规则数组
     * @return 规则断路器
     */
    public static RuleBreaker of(Rule... rules) {
        return builder(RuleBase.of(rules)).build();
    }

    /**
     * 由规则库创建断路器。
     *
     * @param ruleBase 规则库
     * @return 规则断路器
     */
    public static RuleBreaker of(RuleBase ruleBase) {
        return builder(ruleBase).build();
    }

    /**
     * 由规则库与全局变量创建断路器。
     *
     * @param ruleBase 规则库
     * @param globals  全局变量
     * @return 规则断路器
     */
    public static RuleBreaker of(RuleBase ruleBase, Map<String, Object> globals) {
        return builder(ruleBase).globals(globals).build();
    }

    /**
     * 由「规则快照提供者」创建断路器：<b>动态语义</b>。
     *
     * <p>与 {@link #of(RuleBase)} 的区别在于规则来源：前者固定创建时的
     * 规则集，本方法则在<b>每次求值开始时</b>调用 {@code snapshots}
     * 取得当下规则集，因此长期持有的断路器能自动跟随规则热更新。</p>
     *
     * <p><b>一致性保证</b>：快照在一次求值内只取一次
     * （{@link #newSession()} 每次调用解析一次，而一次
     * {@code verdict()} 只建一个会话），所以单次判定不会混用两个版本；
     * 规则集与全局变量成对封装在 {@link RuleSnapshot} 里，
     * 也不会出现「阈值已更新而 {@code g.max} 仍是旧值」。</p>
     *
     * <p>典型用法是规则来自可热更新的配置源：</p>
     * <pre>{@code
     * RuleRepository repository = ...;
     * repository.loadFromDirectory(Paths.get("rules"));
     * RuleBreaker breaker = RuleBreaker.dynamic(() ->
     *         new RuleBreaker.RuleSnapshot(repository.current(), repository.globals()));
     * }</pre>
     *
     * <p>{@code snapshots} 返回 null 会被视为「当前没有可用规则」，
     * 求值时按 {@code failClosed} 处理。</p>
     *
     * @param snapshots 规则快照提供者
     * @return 动态规则断路器
     */
    public static RuleBreaker dynamic(Supplier<RuleSnapshot> snapshots) {
        return dynamic(snapshots, RuleSessionConfig.defaultConfig());
    }

    /**
     * 由「规则快照提供者」与指定会话配置创建动态断路器。
     *
     * @param snapshots     规则快照提供者
     * @param sessionConfig 会话配置
     * @return 动态规则断路器
     */
    public static RuleBreaker dynamic(Supplier<RuleSnapshot> snapshots,
            RuleSessionConfig sessionConfig) {
        return dynamicBuilder(snapshots).sessionConfig(sessionConfig).build();
    }

    /**
     * 创建动态断路器构建器。
     *
     * @param snapshots 规则快照提供者
     * @return 构建器
     */
    public static DynamicBuilder dynamicBuilder(Supplier<RuleSnapshot> snapshots) {
        return new DynamicBuilder(snapshots);
    }

    /**
     * 创建构建器。
     *
     * @param ruleBase 规则库
     * @return 构建器
     */
    public static Builder builder(RuleBase ruleBase) {
        return new Builder(ruleBase);
    }

    /**
     * 一次性求值：传入事实，返回是否放行。
     *
     * @param facts 事实数组
     * @return 放行返回 true
     */
    public boolean evaluate(Fact... facts) {
        return verdict(facts).passed();
    }

    /**
     * 一次性求值：传入事实集合，返回是否放行。
     *
     * @param facts 事实集合
     * @return 放行返回 true
     */
    public boolean evaluate(Collection<? extends Fact> facts) {
        return verdict(facts).passed();
    }

    /**
     * 一次性求值并返回结论字符串。
     *
     * @param facts 事实数组
     * @return 结论，{@link #ALLOW} 或 {@link #DENY}
     */
    public String conclusion(Fact... facts) {
        return verdict(facts).conclusion();
    }

    /**
     * 一次性求值并返回完整判定结果。
     *
     * @param facts 事实数组
     * @return 判定结果
     */
    public Verdict verdict(Fact... facts) {
        List<Fact> list = new ArrayList<>(facts == null ? 0 : facts.length);
        if (facts != null) {
            for (Fact fact : facts) {
                if (fact != null) {
                    list.add(fact);
                }
            }
        }
        return verdict(list);
    }

    /**
     * 一次性求值并返回完整判定结果。
     *
     * @param facts 事实集合
     * @return 判定结果
     */
    public Verdict verdict(Collection<? extends Fact> facts) {
        try (RuleSession session = newSession()) {
            if (facts != null) {
                session.insertAll(facts);
            }
            session.fire();
            return buildVerdict(session);
        } catch (RuntimeException e) {
            return failClosed
                    ? Verdict.denied(e)
                    : Verdict.allowed(e);
        }
    }

    /**
     * 依据会话结果构造判定结果。
     *
     * @param session 会话
     * @return 判定结果
     */
    private Verdict buildVerdict(RuleSession session) {
        Object result = session.result();
        boolean passed;
        String conclusion;
        if (result == null) {
            passed = !denyWhenNoConclusion;
            conclusion = passed ? ALLOW : DENY;
        } else {
            conclusion = String.valueOf(result);
            passed = ALLOW.equals(conclusion);
        }
        // 无结论时同样要带上会话的真实计数：规则可能失败了但没产出结论，
        // 若这里返回全 0 的 Verdict，hasRuleFailure() 会撒谎，
        // 线上排查就会被带偏
        return new Verdict(conclusion, passed, session.entries(), session.list(), session.lists(),
                session.firedCount(), session.failedCount(), session.cycleCount(), null);
    }

    /**
     * 创建裸会话，用于需要自行掌控工作内存的场景。
     *
     * <p>每次调用都重新解析一次规则快照，因此动态断路器的新会话
     * 会自动使用最新规则集；同一次求值内只解析一次，
     * 保证单次判定不会混用两个版本的规则。</p>
     *
     * @return 规则会话
     */
    public RuleSession newSession() {
        RuleSnapshot snapshot = snapshots.get();
        return snapshot.ruleBase().newSession(sessionConfig, snapshot.globals(), listeners);
    }

    /**
     * 获取当前规则库。
     *
     * @return 规则库
     */
    public RuleBase ruleBase() {
        return snapshots.get().ruleBase();
    }

    /**
     * 获取当前全局变量。
     *
     * @return 全局变量，只读
     */
    public Map<String, Object> globals() {
        return snapshots.get().globals();
    }

    /**
     * 判定结果。
     *
     * @param conclusion  结论字符串
     * @param passed      是否放行
     * @param entries     结果条目
     * @param list        默认列表结果
     * @param lists       全部列表结果（按列表键索引）
     * @param firedCount  触发规则数
     * @param failedCount 规则失败数
     * @param cycleCount  推理轮数
     * @param error       推理异常，无异常为 null
     */
    public record Verdict(String conclusion, boolean passed, Map<String, Object> entries, List<Object> list,
                          Map<String, List<Object>> lists, int firedCount, int failedCount, int cycleCount,
                          Throwable error) {

        /**
         * 规范构造器：结论为空值敌对，集合组件做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。
         * 三个构造点的集合来源分别是 {@code RuleSession#entries()}
         * （已是 {@code Map.copyOf}）、{@code RuleSession#list()}（已是
         * {@code List.copyOf}）与 {@code Map.of()}，均不含 null 键值，
         * 故采用 {@code copyOf} 系列。</p>
         *
         * <p>{@code error} 在无异常时显式传 null，属正常语义，不做校验。</p>
         *
         * @param conclusion  结论字符串
         * @param passed      是否放行
         * @param entries     结果条目
         * @param list        默认列表结果
         * @param lists       全部列表结果
         * @param firedCount  触发规则数
         * @param failedCount 规则失败数
         * @param cycleCount  推理轮数
         * @param error       推理异常，无异常为 null
         */
        public Verdict {
            conclusion = Objects.requireNonNull(conclusion, "conclusion 不能为 null");
            entries = Map.copyOf(Objects.requireNonNull(entries, "entries 不能为 null"));
            list = List.copyOf(Objects.requireNonNull(list, "list 不能为 null"));
            lists = Map.copyOf(Objects.requireNonNull(lists, "lists 不能为 null"));
        }

        /**
         * 构造放行结果。
         *
         * @param error 关联异常
         * @return 判定结果
         */
        static Verdict allowed(Throwable error) {
            return new Verdict(ALLOW, true, Map.of(), List.of(), Map.of(), 0, 0, 0, error);
        }

        /**
         * 构造断路结果。
         *
         * @param error 关联异常
         * @return 判定结果
         */
        static Verdict denied(Throwable error) {
            return new Verdict(DENY, false, Map.of(), List.of(), Map.of(), 0, 0, 0, error);
        }

        /**
         * 推理过程中是否有规则失败。
         *
         * @return 有规则抛异常返回 true
         */
        public boolean hasRuleFailure() {
            return failedCount > 0;
        }
    }

    /**
     * 规则断路器构建器。
     */
    public static final class Builder {

        /**
         * 规则库
         */
        private final RuleBase ruleBase;

        /**
         * 会话配置
         */
        private RuleSessionConfig sessionConfig = RuleSessionConfig.defaultConfig();

        /**
         * 无结论时是否断路，默认放行
         */
        private boolean denyWhenNoConclusion;

        /**
         * 推理异常时是否断路，默认断路（保守，与表达式断路器一致）
         */
        private boolean failClosed = true;

        /**
         * 全局变量
         */
        private Map<String, Object> globals;

        /**
         * 事件监听器
         */
        private final List<RuleListener> listeners = new ArrayList<>();

        /**
         * 创建构建器。
         *
         * @param ruleBase 规则库
         */
        private Builder(RuleBase ruleBase) {
            if (ruleBase == null) {
                throw new RuleException("规则库不能为 null");
            }
            this.ruleBase = ruleBase;
        }

        /**
         * 设置会话配置。
         *
         * @param sessionConfig 会话配置，null 回退为默认配置
         * @return 当前构建器
         */
        public Builder sessionConfig(RuleSessionConfig sessionConfig) {
            this.sessionConfig = sessionConfig == null ? RuleSessionConfig.defaultConfig() : sessionConfig;
            return this;
        }

        /**
         * 设置无结论时的行为。
         *
         * @param denyWhenNoConclusion true 断路，false 放行
         * @return 当前构建器
         */
        public Builder denyWhenNoConclusion(boolean denyWhenNoConclusion) {
            this.denyWhenNoConclusion = denyWhenNoConclusion;
            return this;
        }

        /**
         * 设置推理异常时的行为。
         *
         * @param failClosed true 断路（保守），false 放行
         * @return 当前构建器
         */
        public Builder failClosed(boolean failClosed) {
            this.failClosed = failClosed;
            return this;
        }

        /**
         * 设置全局变量，对位表达式断路器的 {@code context}。
         *
         * <p>全局变量不参与模式匹配，仅供规则条件与动作读取。</p>
         *
         * @param globals 全局变量，可为 null
         * @return 当前构建器
         */
        public Builder globals(Map<String, Object> globals) {
            this.globals = globals == null ? null : new LinkedHashMap<>(globals);
            return this;
        }

        /**
         * 追加全局变量。
         *
         * @param key   变量名
         * @param value 变量值
         * @return 当前构建器
         */
        public Builder global(String key, Object value) {
            if (this.globals == null) {
                this.globals = new LinkedHashMap<>();
            }
            this.globals.put(key, value);
            return this;
        }

        /**
         * 追加事件监听器。
         *
         * @param listener 监听器
         * @return 当前构建器
         */
        public Builder listener(RuleListener listener) {
            if (listener != null) {
                this.listeners.add(listener);
            }
            return this;
        }

        /**
         * 构建规则断路器。
         *
         * @return 规则断路器
         */
        public RuleBreaker build() {
            Map<String, Object> snapshotGlobals = globals == null
                    ? Map.of() : new LinkedHashMap<>(globals);
            return new RuleBreaker(() -> new RuleSnapshot(ruleBase, snapshotGlobals),
                    sessionConfig, denyWhenNoConclusion, failClosed, List.copyOf(listeners));
        }
    }

    /**
     * 动态断路器构建器。
     *
     * <p>与静态 {@link Builder} 的差别只在规则来源：全局变量不能在构建期
     * 固定（它们会随规则集一起热更新），因此统一从快照中读取。</p>
     */
    public static final class DynamicBuilder {

        /**
         * 规则快照提供者
         */
        private final Supplier<RuleSnapshot> snapshots;

        /**
         * 会话配置
         */
        private RuleSessionConfig sessionConfig = RuleSessionConfig.defaultConfig();

        /**
         * 无结论时是否断路
         *
         * <p>与静态 {@link Builder} 保持一致，默认放行。</p>
         */
        private boolean denyWhenNoConclusion;

        /**
         * 推理异常时是否断路
         */
        private boolean failClosed = true;

        /**
         * 事件监听器
         */
        private final List<RuleListener> listeners = new ArrayList<>();

        /**
         * 创建构建器。
         *
         * @param snapshots 规则快照提供者
         */
        private DynamicBuilder(Supplier<RuleSnapshot> snapshots) {
            if (snapshots == null) {
                throw new RuleException("规则快照提供者不能为 null");
            }
            this.snapshots = snapshots;
        }

        /**
         * 设置会话配置。
         *
         * @param sessionConfig 会话配置，null 回退为默认配置
         * @return 当前构建器
         */
        public DynamicBuilder sessionConfig(RuleSessionConfig sessionConfig) {
            this.sessionConfig = sessionConfig == null
                    ? RuleSessionConfig.defaultConfig() : sessionConfig;
            return this;
        }

        /**
         * 设置无结论时的行为。
         *
         * @param denyWhenNoConclusion true 断路，false 放行
         * @return 当前构建器
         */
        public DynamicBuilder denyWhenNoConclusion(boolean denyWhenNoConclusion) {
            this.denyWhenNoConclusion = denyWhenNoConclusion;
            return this;
        }

        /**
         * 设置推理异常时的行为。
         *
         * @param failClosed true 断路（保守），false 放行
         * @return 当前构建器
         */
        public DynamicBuilder failClosed(boolean failClosed) {
            this.failClosed = failClosed;
            return this;
        }

        /**
         * 追加事件监听器。
         *
         * @param listener 监听器
         * @return 当前构建器
         */
        public DynamicBuilder listener(RuleListener listener) {
            if (listener != null) {
                this.listeners.add(listener);
            }
            return this;
        }

        /**
         * 构建动态断路器。
         *
         * @return 动态规则断路器
         */
        public RuleBreaker build() {
            return new RuleBreaker(snapshots, sessionConfig,
                    denyWhenNoConclusion, failClosed, List.copyOf(listeners));
        }
    }
}
