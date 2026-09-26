package com.chua.common.support.rule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则会话（Working Memory + Agenda）。
 *
 * <p>承载一次独立推理的全部可变状态：工作内存、议程、焦点栈与推理结果。
 * 对应 Drools 的 {@code StatefulKnowledgeSession}。</p>
 *
 * <h3>两阶段执行</h3>
 * <p>与 Drools 一致，每轮推理分两阶段：</p>
 * <ol>
 *   <li><b>议程评估</b> — 遍历规则库，把规则 LHS 与工作内存做模式匹配，
 *       为每个满足条件的绑定元组生成一条激活，并按冲突消解策略排序</li>
 *   <li><b>工作内存动作</b> — 按序执行激活对应的 RHS 动作</li>
 * </ol>
 * <p>两阶段交替进行，直到没有新激活（达到不动点）或触达轮次上限。</p>
 *
 * <h3>冲突消解顺序</h3>
 * <ol>
 *   <li>动态优先级（{@link Rule#resolveSalience}）降序</li>
 *   <li>规则声明顺序升序</li>
 *   <li>激活生成顺序升序（FIFO，保证结果可复现）</li>
 * </ol>
 * <p>激活分组（{@code activationGroup}）内仅保留优先级最高的激活，
 * 其余同组激活被取消并发布 {@link RuleEventType#RULE_CANCELLED} 事件。</p>
 *
 * <h3>线程安全</h3>
 * <p>会话<b>非线程安全</b>，单次推理应在单线程内完成。
 * 但事实的插入 / 撤销已做同步，允许其他线程在推理期间投递事实。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleSession implements AutoCloseable {

    /**
     * 所属规则库
     */
    private final RuleBase ruleBase;

    /**
     * 会话配置
     */
    private final RuleSessionConfig config;

    /**
     * 全局变量
     */
    private final Map<String, Object> globals;

    /**
     * 事件监听器
     */
    private final List<RuleListener> listeners;

    /**
     * 议程焦点栈，栈顶为当前焦点分组
     */
    private final Deque<String> focusStack = new ArrayDeque<>();

    /**
     * 当前焦点期间已锁定（lock-on-active）的规则名
     */
    private final Set<String> lockedRules = new HashSet<>();

    /**
     * 会话内部状态对象，持有工作内存、推理结果与计数器。
     *
     * <p>本类只做编排：状态读写一律委托给 {@code state}，
     * 事件发布仍留在本类，以保证「状态变更 → 事件通知」的顺序不变。</p>
     */
    private final RuleSessionState state = new RuleSessionState();

    /**
     * 使用默认配置创建会话。
     *
     * @param ruleBase 规则库
     */
    RuleSession(RuleBase ruleBase) {
        this(ruleBase, RuleSessionConfig.defaultConfig(), null, null);
    }

    /**
     * 创建会话。
     *
     * @param ruleBase 规则库
     * @param config   会话配置
     */
    RuleSession(RuleBase ruleBase, RuleSessionConfig config) {
        this(ruleBase, config, null, null);
    }

    /**
     * 创建会话。
     *
     * @param ruleBase  规则库
     * @param config    会话配置
     * @param globals   全局变量，可为 null
     * @param listeners 事件监听器，可为 null
     */
    RuleSession(RuleBase ruleBase, RuleSessionConfig config, Map<String, Object> globals,
                List<RuleListener> listeners) {
        if (ruleBase == null) {
            throw new RuleException("规则库不能为 null");
        }
        this.ruleBase = ruleBase;
        this.config = config == null ? RuleSessionConfig.defaultConfig() : config;
        this.globals = new LinkedHashMap<>();
        if (globals != null) {
            this.globals.putAll(globals);
        }
        this.listeners = new ArrayList<>();
        if (listeners != null) {
            for (RuleListener listener : listeners) {
                if (listener != null) {
                    this.listeners.add(listener);
                }
            }
        }
        this.focusStack.push(Rule.DEFAULT_AGENDA_GROUP);
    }

    /**
     * 获取所属规则库。
     *
     * @return 规则库
     */
    public RuleBase ruleBase() {
        return ruleBase;
    }

    /**
     * 获取会话配置。
     *
     * @return 会话配置
     */
    public RuleSessionConfig config() {
        return config;
    }

    // ==================== 工作内存 ====================

    /**
     * 插入事实到工作内存。
     *
     * @param fact 待插入事实
     * @return 插入成功返回 true
     */
    public boolean insert(Fact fact) {
        ensureOpen();
        if (fact == null) {
            throw new RuleException("待插入的事实不能为 null");
        }
        state.insertFact(fact);
        publish(RuleEvent.of(RuleEventType.FACT_INSERTED, null, fact));
        return true;
    }

    /**
     * 批量插入事实。
     *
     * @param items 待插入事实集合
     * @return 实际插入数量
     */
    public int insertAll(Iterable<? extends Fact> items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (Fact fact : items) {
            if (insert(fact)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 从工作内存撤销事实。
     *
     * @param fact 待撤销事实，按对象同一性比较
     * @return 撤销成功返回 true
     */
    public boolean retract(Object fact) {
        ensureOpen();
        if (fact == null) {
            return false;
        }
        boolean removed = state.retractFact(fact);
        if (removed) {
            publish(RuleEvent.of(RuleEventType.FACT_RETRACTED, null, fact));
        }
        return removed;
    }

    /**
     * 标记事实已变更。
     *
     * <p>本引擎每轮都会重新做全量模式匹配，因此无需显式重投；
     * 此方法递增事实版本号，使依赖该事实的规则在其 RHS 修改事实后
     * 可以再次触发（Drools refraction 语义），
     * 同时发布 {@link RuleEventType#FACT_UPDATED} 事件供审计与指标消费。</p>
     *
     * @param fact 已变更事实
     * @return 事实在工作内存中返回 true
     */
    public boolean touch(Object fact) {
        if (fact == null) {
            return false;
        }
        boolean present = state.touchFact(fact);
        if (present) {
            publish(RuleEvent.of(RuleEventType.FACT_UPDATED, null, fact));
        }
        return present;
    }

    /**
     * 获取事实当前版本号。
     *
     * @param fact 事实
     * @return 版本号，从 0 开始
     */
    int versionOf(Object fact) {
        return state.versionOfFact(fact);
    }

    /**
     * 获取指定类型的事实。
     *
     * <p>按运行时类型索引做可赋值匹配，既支持具体类，
     * 也支持接口与抽象类声明。</p>
     *
     * @param type 事实类型
     * @return 事实快照，只读
     */
    public List<Object> factsOf(Class<?> type) {
        return state.factsOfType(type);
    }

    /**
     * 获取全部事实。
     *
     * @return 事实快照，只读
     */
    public List<Object> facts() {
        return state.allFacts();
    }

    /**
     * 获取工作内存事实数量。
     *
     * @return 事实数量
     */
    public int factCount() {
        return state.factCount();
    }

    /**
     * 清空工作内存与推理结果。
     */
    public void reset() {
        state.reset();
        lockedRules.clear();
    }

    // ==================== 全局变量 ====================

    /**
     * 获取全局变量。
     *
     * @param key 变量名
     * @return 变量值，不存在返回 null
     */
    public Object global(String key) {
        return key == null ? null : globals.get(key);
    }

    /**
     * 设置全局变量。
     *
     * @param key   变量名
     * @param value 变量值
     */
    public void setGlobal(String key, Object value) {
        if (key != null) {
            globals.put(key, value);
        }
    }

    /**
     * 获取全部全局变量。
     *
     * @return 全局变量快照，只读
     */
    public Map<String, Object> globals() {
        return Map.copyOf(globals);
    }

    // ==================== 议程焦点 ====================

    /**
     * 切换议程焦点到指定分组。
     *
     * @param agendaGroup 议程分组
     */
    public void focus(String agendaGroup) {
        ensureOpen();
        if (agendaGroup == null || agendaGroup.isBlank()) {
            throw new RuleException("议程分组不能为空");
        }
        focusStack.push(agendaGroup.trim());
    }

    /**
     * 弹出当前焦点，恢复上一个分组。
     *
     * <p>弹栈时清空该焦点期间的规则锁定，
     * 使 {@code lock-on-active} 规则可被重新激活。</p>
     */
    public void popFocus() {
        ensureOpen();
        if (!focusStack.isEmpty()) {
            focusStack.pop();
        }
        lockedRules.clear();
    }

    /**
     * 获取当前焦点分组。
     *
     * @return 当前焦点分组
     */
    public String currentFocus() {
        return focusStack.isEmpty() ? Rule.DEFAULT_AGENDA_GROUP : focusStack.peek();
    }

    // ==================== 推理执行 ====================

    /**
     * 执行推理，直到达到不动点或触达轮次上限。
     *
     * <p>激活在整次 {@code fire()} 调用内被「消费」：同一
     * 「规则 + 绑定元组 + 事实版本」的组合只会触发一次，
     * 直到所依赖的事实被 {@link #touch(Object)} 标记为已变更。
     * 这保证无副作用的规则不会在多轮推理中空转到轮次上限。</p>
     *
     * @return 本次调用触发的规则次数
     * @throws IllegalStateException 会话已关闭时抛出
     */
    public int fire() {
        ensureOpen();
        int firedThisCall = 0;
        Set<String> firedSignatures = new HashSet<>();
        for (int cycle = 1; cycle <= config.maxCycles(); cycle++) {
            state.setCycle(cycle);
            List<Activation> activations = agendaEvaluation();
            if (activations.isEmpty()) {
                break;
            }
            activations = resolveActivationGroups(activations);
            if (activations.isEmpty()) {
                break;
            }
            boolean halted = false;
            for (Activation activation : activations) {
                if (halted) {
                    break;
                }
                Rule rule = activation.rule();
                if (rule.noLoop() && !firedSignatures.add(activation.signature())) {
                    continue;
                }
                if (execute(activation)) {
                    state.markFired();
                    firedThisCall++;
                    if (rule.lockOnActive()) {
                        lockedRules.add(rule.name());
                    }
                } else {
                    // 失败计数由 execute() 统一负责：它同时掌握异常对象并发布
                    // RULE_FAILED。此处再记一次会让每条失败规则把 failedCount
                    // 记成 2，与 LHS 失败（markConditionFailure 只记一次）
                    // 口径不一致，也会把 Verdict.failedCount 虚高一倍。
                    if (config.stopOnRuleFailure()) {
                        halted = true;
                    }
                }
            }
            publish(RuleEvent.of(RuleEventType.CYCLE_COMPLETED, null, cycle));
            if (halted) {
                break;
            }
        }
        return firedThisCall;
    }

    /**
     * 仅评估一轮议程并执行，用于需要观察中间态的场景。
     *
     * @return 本轮触发的规则次数
     */
    public int fireOneCycle() {
        ensureOpen();
        state.advanceCycle();
        List<Activation> activations = resolveActivationGroups(agendaEvaluation());
        int firedThisCycle = 0;
        for (Activation activation : activations) {
            if (execute(activation)) {
                state.markFired();
                firedThisCycle++;
            }
            // 失败计数由 execute() 统一负责，理由同 fire()。
        }
        publish(RuleEvent.of(RuleEventType.CYCLE_COMPLETED, null, state.cycleCount()));
        return firedThisCycle;
    }

    /**
     * 第一阶段：议程评估。
     *
     * <p>遍历规则库中启用且属于当前焦点分组的规则，
     * 对每条规则做模式匹配，生成激活并按冲突消解策略排序。</p>
     *
     * @return 排序后的激活列表
     */
    private List<Activation> agendaEvaluation() {
        List<Activation> activations = new ArrayList<>();
        String focus = currentFocus();
        for (Rule rule : ruleBase.rules()) {
            if (!rule.enabled() || !rule.agendaGroup().equals(focus)) {
                continue;
            }
            if (rule.lockOnActive() && lockedRules.contains(rule.name())) {
                continue;
            }
            evaluateRule(rule, activations);
        }
        if (activations.size() > config.maxActivationsPerCycle()) {
            publish(RuleEvent.of(RuleEventType.LIMIT_REACHED, null, activations.size()));
            activations = new ArrayList<>(activations.subList(0, config.maxActivationsPerCycle()));
        }
        activations.sort((left, right) -> {
            int bySalience = Integer.compare(right.salience(), left.salience());
            if (bySalience != 0) {
                return bySalience;
            }
            int bySequence = Integer.compare(left.rule().sequence(), right.rule().sequence());
            if (bySequence != 0) {
                return bySequence;
            }
            return Integer.compare(left.order(), right.order());
        });
        return activations;
    }

    /**
     * 对单条规则做模式匹配并生成激活。
     *
     * <p>当规则条件顶层包含 {@link Pattern} 时进入元组模式：
     * 对所有模式求候选集并做笛卡尔积，每个绑定组合生成一条激活。
     * 否则进入单元组模式，整条规则只可能产生一条激活。</p>
     *
     * @param rule        规则
     * @param activations 激活收集容器
     */
    private void evaluateRule(Rule rule, List<Activation> activations) {
        List<Condition> topLevel = Conditions.flatten(rule.when());
        List<Pattern> patterns = new ArrayList<>();
        List<Condition> predicates = new ArrayList<>();
        for (Condition condition : topLevel) {
            if (condition instanceof Pattern pattern) {
                patterns.add(pattern);
            } else {
                predicates.add(condition);
            }
        }

        if (patterns.isEmpty()) {
            RuleContext context = new RuleContext(this, rule);
            // LHS 与 RHS 必须同样隔离：条件里抛出的异常若逃逸出去，
            // 会被 RuleBreaker 的兜底 catch 吞掉，failedCount 与规则名都会丢失，
            // 线上表现为「莫名 DENY 且查不到哪条规则失败」
            try {
                for (Condition predicate : predicates) {
                    if (!predicate.test(context)) {
                        return;
                    }
                }
            } catch (RuntimeException e) {
                markConditionFailure(rule, e);
                return;
            }
            activations.add(new Activation(rule, context, activations.size()));
            publish(RuleEvent.of(RuleEventType.RULE_ACTIVATED, rule.name()));
            return;
        }

        List<List<Object>> candidateLists = new ArrayList<>(patterns.size());
        for (Pattern pattern : patterns) {
            List<Object> matched = pattern.filter(factsOf(pattern.factType()));
            if (matched.isEmpty()) {
                return;
            }
            candidateLists.add(matched);
        }

        int[] cursors = new int[candidateLists.size()];
        while (true) {
            RuleContext context = new RuleContext(this, rule);
            for (int i = 0; i < patterns.size(); i++) {
                context.bind(patterns.get(i).binding(), candidateLists.get(i).get(cursors[i]));
            }
            boolean matched = true;
            try {
                for (Condition predicate : predicates) {
                    if (!predicate.test(context)) {
                        matched = false;
                        break;
                    }
                }
            } catch (RuntimeException e) {
                // 条件异常按「整条规则失败」处理并终止该规则，
                // 而不是对每个绑定组合重复计一次失败
                markConditionFailure(rule, e);
                return;
            }
            if (matched) {
                activations.add(new Activation(rule, context, activations.size()));
                publish(RuleEvent.of(RuleEventType.RULE_ACTIVATED, rule.name()));
            }

            int position = cursors.length - 1;
            while (position >= 0) {
                cursors[position]++;
                if (cursors[position] < candidateLists.get(position).size()) {
                    break;
                }
                cursors[position] = 0;
                position--;
            }
            if (position < 0) {
                return;
            }
            if (activations.size() >= config.maxActivationsPerCycle()) {
                return;
            }
        }
    }

    /**
     * 激活分组冲突消解。
     *
     * <p>同一激活分组内仅保留优先级最高的激活，其余取消。
     * 优先级相同则全部保留（与 Drools 只触发一条略有差异，
     * 此处偏向「同优先级等价规则都应生效」的语义）。</p>
     *
     * @param activations 待消解的激活列表
     * @return 消解后的激活列表
     */
    private List<Activation> resolveActivationGroups(List<Activation> activations) {
        Map<String, Integer> groupMaxSalience = new HashMap<>();
        for (Activation activation : activations) {
            String group = activation.rule().activationGroup();
            if (group == null) {
                continue;
            }
            groupMaxSalience.merge(group, activation.salience(), Math::max);
        }
        if (groupMaxSalience.isEmpty()) {
            return activations;
        }
        List<Activation> kept = new ArrayList<>(activations.size());
        for (Activation activation : activations) {
            String group = activation.rule().activationGroup();
            if (group == null || activation.salience() >= groupMaxSalience.get(group)) {
                kept.add(activation);
                continue;
            }
            publish(RuleEvent.of(RuleEventType.RULE_CANCELLED, activation.rule().name(), group));
        }
        return kept;
    }

    /**
     * 记录一次「条件求值失败」。
     *
     * <p>与 {@link #execute(Activation)} 的 RHS 隔离保持一致：
     * 计入 {@code failedCount} 并发布 {@link RuleEventType#RULE_FAILED}，
     * 使「哪条规则、因为什么失败」在审计里可见。</p>
     *
     * @param rule 规则
     * @param error 异常
     */
    private void markConditionFailure(Rule rule, RuntimeException error) {
        state.markFailed();
        publish(RuleEvent.of(RuleEventType.RULE_FAILED, rule.name(), null, error));
    }

    /**
     * 第二阶段：执行单条激活的 RHS 动作。
     *
     * <p><b>失败计数归属本方法。</b>RHS 抛异常时在此计入一次
     * {@code failedCount} 并发布 {@link RuleEventType#RULE_FAILED}；
     * 调用方（{@link #fire()} / {@link #fireOneCycle()}）只根据返回值
     * 决定是否停议程，不得重复计数。</p>
     *
     * <p>返回 {@code false} 有两种情形：RHS 抛异常（已计一次失败），
     * 或规则显式调用了 {@link RuleContext#halt()}（主动停议程，
     * <b>不算</b>规则失败）。</p>
     *
     * @param activation 激活
     * @return 执行成功返回 true
     */
    private boolean execute(Activation activation) {
        RuleContext context = activation.context();
        try {
            activation.rule().then().execute(context);
            mergeResult(context);
            publish(RuleEvent.of(RuleEventType.RULE_FIRED, activation.rule().name()));
            return !context.halted();
        } catch (RuntimeException e) {
            state.markFailed();
            publish(RuleEvent.of(RuleEventType.RULE_FAILED, activation.rule().name(), null, e));
            return false;
        }
    }

    /**
     * 合并单条激活产出的结果到会话级结果。
     *
     * @param context 规则上下文
     */
    private void mergeResult(RuleContext context) {
        state.mergeResult(context.result(), context.entries(), context.lists());
    }

    // ==================== 结果读取 ====================

    /**
     * 获取主结果。
     *
     * @return 主结果，未设置返回 null
     */
    public Object result() {
        return state.result();
    }

    /**
     * 获取结果条目。
     *
     * @return 结果条目快照，只读
     */
    public Map<String, Object> entries() {
        return state.entries();
    }

    /**
     * 获取列表结果。
     *
     * @param key 列表键
     * @return 列表快照，不存在返回空列表
     */
    public List<Object> list(String key) {
        return state.listOf(key);
    }

    /**
     * 获取默认列表结果。
     *
     * @return 列表快照
     */
    public List<Object> list() {
        return list(RuleContext.DEFAULT_LIST_KEY);
    }

    /**
     * 获取全部列表结果（按列表键索引）。
     *
     * @return 列表结果快照，只读
     */
    public Map<String, List<Object>> lists() {
        return state.allLists();
    }

    /**
     * 获取累计触发规则数。
     *
     * @return 触发次数
     */
    public int firedCount() {
        return state.firedCount();
    }

    /**
     * 获取累计规则失败数。
     *
     * @return 失败次数
     */
    public int failedCount() {
        return state.failedCount();
    }

    /**
     * 获取累计推理轮数。
     *
     * @return 推理轮数
     */
    public int cycleCount() {
        return state.cycleCount();
    }

    /**
     * 添加事件监听器。
     *
     * @param listener 监听器
     */
    public void addListener(RuleListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 发布事件，监听器异常不影响推理。
     *
     * @param event 规则事件
     */
    private void publish(RuleEvent event) {
        if (listeners.isEmpty() || event == null) {
            return;
        }
        for (RuleListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                state.markFailed();
            }
        }
    }

    /**
     * 校验会话未关闭。
     */
    private void ensureOpen() {
        if (state.isClosed()) {
            throw new IllegalStateException("规则会话已关闭");
        }
    }

    @Override
    public void close() {
        reset();
        state.markClosed();
    }

    /**
     * 规则激活。
     *
     * <p>绑定「规则 + 一个事实绑定元组」，是议程的最小调度单位。</p>
     */
    private static final class Activation {

        /**
         * 所属规则
         */
        private final Rule rule;

        /**
         * 规则上下文
         */
        private final RuleContext context;

        /**
         * 生成顺序
         */
        private final int order;

        /**
         * 动态优先级
         */
        private final int salience;

        /**
         * 绑定签名
         */
        private final String signature;

        /**
         * 创建激活。
         *
         * @param rule    规则
         * @param context 规则上下文
         * @param order   生成顺序
         */
        private Activation(Rule rule, RuleContext context, int order) {
            this.rule = rule;
            this.context = context;
            this.order = order;
            this.salience = rule.resolveSalience(context);
            this.signature = buildSignature(rule, context);
        }

        /**
         * 构建绑定签名。
         *
         * <p>签名由规则名、各绑定事实的对象同一性及其版本号组成。
         * 三者共同实现 refraction：</p>
         * <ul>
         *   <li>规则名 — 区分不同规则</li>
         *   <li>同一性 — 使「同一规则的不同元组」都能触发</li>
         *   <li>版本号 — 事实被 touch 后签名改变，允许再次触发</li>
         * </ul>
         *
         * @param rule    规则
         * @param context 规则上下文
         * @return 绑定签名
         */
        private String buildSignature(Rule rule, RuleContext context) {
            StringBuilder builder = new StringBuilder(rule.name());
            for (Map.Entry<String, Object> entry : context.bindings().entrySet()) {
                builder.append('|').append(entry.getKey())
                        .append('#').append(System.identityHashCode(entry.getValue()))
                        .append('@').append(context.version(entry.getValue()));
            }
            return builder.toString();
        }

        /**
         * 获取规则。
         *
         * @return 规则
         */
        private Rule rule() {
            return rule;
        }

        /**
         * 获取规则上下文。
         *
         * @return 规则上下文
         */
        private RuleContext context() {
            return context;
        }

        /**
         * 获取生成顺序。
         *
         * @return 生成顺序
         */
        private int order() {
            return order;
        }

        /**
         * 获取动态优先级。
         *
         * @return 动态优先级
         */
        private int salience() {
            return salience;
        }

        /**
         * 获取绑定签名。
         *
         * @return 绑定签名
         */
        private String signature() {
            return signature;
        }
    }
}
