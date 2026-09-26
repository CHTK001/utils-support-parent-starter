package com.chua.common.support.rule.file;

import com.chua.common.support.rule.Action;
import com.chua.common.support.rule.Actions;
import com.chua.common.support.rule.Condition;
import com.chua.common.support.rule.Conditions;
import com.chua.common.support.rule.Pattern;
import com.chua.common.support.rule.Rule;
import com.chua.common.support.rule.RuleBase;
import com.chua.common.support.rule.RuleContext;
import com.chua.common.support.rule.RuleException;
import com.chua.common.support.rule.decision.DecisionRegistry;
import com.chua.common.support.rule.decision.DecisionTable;
import com.chua.common.support.rule.decision.DecisionTableSpec;
import com.chua.common.support.rule.decision.RuleDecisionProvider;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则装配器。
 *
 * <p>把 {@link RuleSetSpec}（解析结果）翻译为运行时的
 * {@link RuleBase} / {@link Rule} / {@link Condition} / {@link Action}。</p>
 *
 * <h3>条件树到引擎条件的映射</h3>
 * <table border="1">
 *   <caption>映射关系</caption>
 *   <tr><th>规则文件</th><th>运行时</th></tr>
 *   <tr><td>{@code when.facts[].type}</td>
 *       <td>{@link Pattern#of(String, Class, java.util.function.Predicate)} 的类型，
 *           类型由 {@link RuleTypeRegistry} 白名单解析</td></tr>
 *   <tr><td>{@code when.facts[].binding}</td><td>Pattern 绑定名</td></tr>
 *   <tr><td>{@code all}</td><td>{@link Conditions#and}</td></tr>
 *   <tr><td>{@code any}</td><td>{@link Conditions#or}</td></tr>
 *   <tr><td>{@code not}</td><td>{@link Conditions#not}</td></tr>
 *   <tr><td>{@code path/op/value}</td>
 *       <td>{@link Pattern#compare} 属性路径比较（走 ReflectUtils）</td></tr>
 *   <tr><td>{@code global/op/value}</td><td>读取 {@code ctx.global(...)} 后比较</td></tr>
 *   <tr><td>{@code exists}</td><td>绑定非空判断</td></tr>
 *   <tr><td>{@code expr}</td>
 *       <td>交由 {@link ExpressionCompiler} 编译——实现位于
 *           {@code utils-support-asm-starter}（ASM 字节码生成），
 *           引入该 starter 后经 SPI 自动发现；
 *           不可用时抛明确异常，绝不静默降级为 true</td></tr>
 * </table>
 *
 * <h3>为什么 expr 不降级</h3>
 * <p>若表达式无法编译时返回 {@code true}，规则会「默认放行」，
 * 在断路器场景下等于形同虚设；若返回 {@code false} 则「默认拒绝」，
 * 可能造成全量误拦。两种静默降级都危险，因此一律抛异常，
 * 让装载失败暴露出来，由热更新机制保留上一份可用规则集。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class RuleAssembler {

    /**
     * 字符串字面量，用于在推断绑定前把它们从表达式里剔除
     */
    private static final java.util.regex.Pattern STRING_LITERAL =
            java.util.regex.Pattern.compile("'[^']*'|\"[^\"]*\"");

    /**
     * 「标识符 + 点」的成员访问形态，用于推断候选绑定名
     */
    private static final java.util.regex.Pattern MEMBER_ACCESS =
            java.util.regex.Pattern.compile("(?<![\\w.$])([A-Za-z_][A-Za-z0-9_]*)\\s*\\.");

    /**
     * 表达式关键字与字面量，不能当作绑定名
     */
    private static final java.util.Set<String> EXPRESSION_KEYWORDS = java.util.Set.of(
            "and", "or", "not", "is", "like", "true", "false", "null", "g");

    /**
     * 表达式编译器，未装配时 {@code expr} 节点不可用
     */
    private volatile ExpressionCompiler expressionCompiler;

    /**
     * 经 SPI 自动发现到的表达式编译器。
     * <p>
     * 仅在真正遇到 {@code expr} 节点时才惰性解析并缓存，避免不使用表达式的
     * 使用方为此付出 SPI 扫描开销。显式 {@link #expressionCompiler(ExpressionCompiler)}
     * 装配的实例优先级更高，本字段只作兜底。
     * </p>
     */
    private volatile ExpressionCompiler discoveredCompiler;

    /**
     * 类型注册表
     */
    private final RuleTypeRegistry typeRegistry;

    /**
     * 动作注册表
     */
    private final RuleActionRegistry actionRegistry;

    /**
     * 决策实现注册表
     */
    private final DecisionRegistry decisionRegistry;

    /**
     * 本次装配的决策表（id -&gt; 已编译）
     *
     * <p>由 {@link #assemble} 在装配开始时重建。</p>
     */
    private final Map<String, DecisionTable> decisionTables = new LinkedHashMap<>();

    /**
     * 每张决策表编译时用到的绑定名
     *
     * <p>决策表的行是在规则集级别定义的，引用它的规则必须自己把这些
     * 名字绑定到工作内存，否则运行期 {@code ctx.get(name)} 恒为 null。
     * 这里记下名字，装配规则时据此补出定义节点。</p>
     */
    private final Map<String, java.util.Set<String>> decisionBindings = new LinkedHashMap<>();

    /**
     * 创建装配器。
     *
     * @param typeRegistry   类型注册表
     * @param actionRegistry 动作注册表
     */
    public RuleAssembler(RuleTypeRegistry typeRegistry, RuleActionRegistry actionRegistry) {
        this(typeRegistry, actionRegistry, DecisionRegistry.createDefault());
    }

    /**
     * 创建装配器。
     *
     * @param typeRegistry   类型注册表
     * @param actionRegistry 动作注册表
     * @param decisionRegistry 决策实现注册表
     */
    public RuleAssembler(RuleTypeRegistry typeRegistry, RuleActionRegistry actionRegistry,
            DecisionRegistry decisionRegistry) {
        this.typeRegistry = typeRegistry == null ? RuleTypeRegistry.create() : typeRegistry;
        this.actionRegistry = actionRegistry == null ? RuleActionRegistry.createDefault() : actionRegistry;
        this.decisionRegistry = decisionRegistry == null
                ? DecisionRegistry.createDefault() : decisionRegistry;
    }

    /**
     * 获取决策实现注册表。
     *
     * @return 决策注册表
     */
    public DecisionRegistry decisionRegistry() {
        return decisionRegistry;
    }

    /**
     * 表达式编译器（SPI）。
     *
     * <p><b>本模块只定义接口，不提供实现，也不依赖任何字节码生成三方库。</b>
     * 实现位于 {@code utils-support-asm-starter}：
     * {@code com.chua.common.support.rule.codegen.RuleAsmCompiler}，
     * 并通过 {@code META-INF/extensions} 声明，因此引入该 starter 即可自动生效。</p>
     *
     * <p>装配优先级：显式 {@link RuleAssembler#expressionCompiler(ExpressionCompiler)}
     * 装配 &gt; SPI 自动发现。两者都不可用时，{@code expr} 节点会在装载阶段抛
     * {@link RuleException}，<b>绝不静默降级</b>。</p>
     */
    @FunctionalInterface
    public interface ExpressionCompiler {

        /**
         * 编译表达式为条件。
         *
         * <p><b>bindings 必须传入</b>：表达式里的 {@code o.amount} 中
         * {@code o} 是 {@code when.facts[].binding} 声明的<b>绑定名</b>，
         * 而不是类型别名。编译器需要在编译期把绑定名解析为具体类型，
         * 才能把属性访问生成为类型化的 getter 调用（而不是运行期反射）。</p>
         *
         * @param expression 表达式文本
         * @param ruleName   所属规则名，用于诊断
         * @param bindings   绑定名 -> 事实类型，由本规则的 facts 声明收集
         * @return 条件
         */
        Condition compile(String expression, String ruleName, Map<String, Class<?>> bindings);
    }

    /**
     * 装配表达式编译器。
     *
     * @param compiler 编译器
     * @return 当前装配器
     */
    public RuleAssembler expressionCompiler(ExpressionCompiler compiler) {
        this.expressionCompiler = compiler;
        return this;
    }

    /**
     * 获取表达式编译器。
     *
     * @return 编译器，未装配返回 null
     */
    public ExpressionCompiler expressionCompiler() {
        return expressionCompiler;
    }

    /**
     * 装配为规则库。
     *
     * @param spec 规则集定义
     * @return 规则库
     * @throws RuleException 规则为空、名称重复或节点非法时抛出
     */
    public RuleBase assemble(RuleSetSpec spec) {
        return assemble(spec, Map.of());
    }

    /**
     * 装配为规则库。
     *
     * <p><b>线程约束</b>：{@link #decisionTables} 是每次装配重建的实例字段，
     * 因此同一个装配器<b>不可用于并发装配</b>（{@link RuleRepository} 的装载与
     * 热更新已由单线程串行化）。万一发生并发，最坏结果是某条规则引用到另一份
     * 决策表导致装载失败——失败会保留上一份可用规则集，不会「放行」，
     * 因此偏差方向是安全的。</p>
     *
     * @param spec      规则集定义
     * @param decisions 决策表定义（未编译）
     * @return 规则库
     * @throws RuleException 规则为空、名称重复或节点非法时抛出
     */
    public RuleBase assemble(RuleSetSpec spec, Map<String, DecisionTableSpec> decisions) {
        if (spec == null || spec.rules().isEmpty()) {
            throw new RuleException("规则集为空，无法装配");
        }
        compileDecisionTables(decisions == null ? Map.of() : decisions);
        List<Rule> rules = new ArrayList<>(spec.rules().size());
        for (RuleSpec ruleSpec : spec.rules()) {
            rules.add(assembleRule(ruleSpec));
        }
        return RuleBase.builder().addAll(rules).build();
    }

    /**
     * 编译决策表：把每行表达式编译成 {@link Condition}。
     *
     * <p>这样决策行与规则里的 {@code expr} 走同一套编译器，
     * 白名单、类型检查、字面量加宽规则完全一致，不会出现两套语义。</p>
     *
     * @param decisions 决策表定义
     */
    private void compileDecisionTables(Map<String, DecisionTableSpec> decisions) {
        decisionTables.clear();
        decisionBindings.clear();
        if (decisions.isEmpty()) {
            return;
        }
        ExpressionCompiler compiler = expressionCompiler;
        if (compiler == null) {
            throw new RuleException("规则文件声明了决策表，但未装配表达式编译器；"
                    + "决策行使用表达式，请引入 utils-support-asm-starter 或显式装配");
        }
        decisions.forEach((id, spec) -> {
            List<DecisionTable.Row> rows = new ArrayList<>(spec.rows().size());
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (DecisionTableSpec.Row row : spec.rows()) {
                Map<String, Class<?>> rowBindings =
                        inferBindings(row.when(), "决策表[" + id + "]");
                Condition condition = compiler.compile(row.when(),
                        "决策表[" + id + "] 优先级" + row.priority(), rowBindings);
                rows.add(new DecisionTable.Row(row.priority(), condition, row.outcome()));
                names.addAll(rowBindings.keySet());
            }
            decisionTables.put(id, new DecisionTable(spec.id(), spec.kind(), rows,
                    spec.defaultValue(), spec.hitPolicy()));
            decisionBindings.put(id, names);
        });
    }

    /**
     * 推断表达式里的绑定名。
     *
     * @param expression 表达式
     * @param where      用于报错定位
     * @return 绑定名到类型
     */
    private Map<String, Class<?>> inferBindings(String expression, String where) {
        Map<String, Class<?>> bindings = new LinkedHashMap<>();
        collectResolvableAliases(expression, bindings);
        if (bindings.isEmpty()) {
            throw new RuleException(where + " 的条件里没有任何可解析的绑定：" + expression
                    + "；请确认引用的名字已注册为类型别名，或为该规则显式声明 facts");
        }
        return bindings;
    }

    /**
     * 装配单条规则。
     *
     * @param spec 规则定义
     * @return 规则
     */
    public Rule assembleRule(RuleSpec spec) {
        RuleConditionSpec declared = spec.when();
        // when 写成裸表达式时没有 facts 可声明，绑定名只能靠类型别名推断。
        // 但「推断出类型」还不够——运行期必须有人把事实绑定到这个名字上，
        // 否则 ctx.get("o") 恒为 null。因此这里要把推断结果补成真正的定义节点。
        RuleConditionSpec when = synthesizeDefinitions(declared, spec.name());
        Map<String, Class<?>> bindings = collectBindings(when, spec.name());
        Rule.Builder builder = Rule.builder(spec.name())
                .salience(spec.salience())
                .noLoop(spec.noLoop())
                .lockOnActive(spec.lockOnActive())
                .enabled(spec.enabled())
                .when(assembleTop(when, spec.name(), bindings))
                .then(assembleActions(spec.then(), spec.name()));
        if (spec.description() != null) {
            builder.description(spec.description());
        }
        if (spec.agendaGroup() != null) {
            builder.agendaGroup(spec.agendaGroup());
        }
        if (spec.activationGroup() != null) {
            builder.activationGroup(spec.activationGroup());
        }
        return builder.build();
    }

    /**
     * 当条件树里只有表达式、没有任何事实定义时，按类型别名补出定义节点。
     *
     * <p>触发场景：{@code "when": "o.amount > 100"}。这里从表达式里扫出
     * {@code o.amount} 这样的成员访问，取首段 {@code o} 到
     * {@link RuleTypeRegistry} 里查；查到就生成一个
     * {@code all[DEFINITION(o), EXPR]}，让运行期真的把 OrderFact 绑定到 o。
     * 一个都查不到时原样返回，让表达式编译器报「未声明的绑定」并列出候选，
     * 诊断更准确。</p>
     *
     * <p>已经含有显式定义时不做任何事，显式声明永远优先。</p>
     *
     * @param when     原始条件树
     * @param ruleName 规则名
     * @return 补全后的条件树
     */
    private RuleConditionSpec synthesizeDefinitions(RuleConditionSpec when, String ruleName) {
        if (when == null) {
            return null;
        }
        List<String> expressions = new ArrayList<>();
        List<String> decisionIds = new ArrayList<>();
        if (scanForMissingBindings(when, expressions, decisionIds)) {
            // 已有显式定义：尊重声明，不做任何推断
            return when;
        }
        Map<String, Class<?>> inferred = new LinkedHashMap<>();
        for (String expression : expressions) {
            collectResolvableAliases(expression, inferred);
        }
        // 决策表的行在规则集级别定义，引用它的规则必须自己绑定那些名字，
        // 否则运行期 ctx.get(name) 恒为 null，决策行会 NPE 并被
        // fail-closed 兜成「拒绝」——那是一个极难排查的假阴性
        for (String decisionId : decisionIds) {
            java.util.Set<String> names = decisionBindings.get(decisionId);
            if (names == null) {
                throw new RuleException("引用了未定义的决策表 '" + decisionId + "'；"
                        + "请在规则文件的 decisions 段中声明它");
            }
            for (String name : names) {
                if (inferred.containsKey(name)) {
                    continue;
                }
                try {
                    inferred.put(name, typeRegistry.resolve(name));
                } catch (RuleException e) {
                    throw new RuleException("决策表[" + decisionId + "] 引用了无法解析的绑定 '"
                            + name + "'：" + e.getMessage(), e);
                }
            }
        }
        if (inferred.isEmpty()) {
            return when;
        }
        List<RuleConditionSpec> merged = new ArrayList<>(inferred.size() + 1);
        inferred.forEach((alias, type) ->
                // 类型标识符用别名本身：注册表以别名为键，
                // 写类名会解析失败（allowClassName 默认关闭）
                merged.add(RuleConditionSpec.definition(alias, alias, null)));
        merged.add(when);
        return RuleConditionSpec.all(merged);
    }

    /**
     * 递归扫描：收集表达式文本与决策表引用，并报告是否已存在定义节点。
     *
     * @param spec        条件节点
     * @param expressions 表达式文本收集
     * @param decisionIds 决策表 id 收集
     * @return 是否已存在定义节点
     */
    private boolean scanForMissingBindings(RuleConditionSpec spec, List<String> expressions,
            List<String> decisionIds) {
        if (spec == null) {
            return false;
        }
        boolean found = spec.kind() == RuleConditionSpec.Kind.DEFINITION;
        if (spec.kind() == RuleConditionSpec.Kind.EXPR) {
            expressions.add(spec.expr());
        }
        if (spec.kind() == RuleConditionSpec.Kind.DECISION) {
            decisionIds.add(spec.type());
        }
        for (RuleConditionSpec child : spec.children()) {
            found |= scanForMissingBindings(child, expressions, decisionIds);
        }
        return found;
    }

    /**
     * 扫描表达式里能解析为已注册类型的标识符。
     *
     * @param expression 表达式文本
     * @param target     收集结果，键为绑定名，值为类型
     */
    private void collectResolvableAliases(String expression, Map<String, Class<?>> target) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        String text = STRING_LITERAL.matcher(expression).replaceAll(" ");
        java.util.regex.Matcher matcher = MEMBER_ACCESS.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (target.containsKey(candidate) || EXPRESSION_KEYWORDS.contains(candidate)) {
                continue;
            }
            try {
                target.put(candidate, typeRegistry.resolve(candidate));
            } catch (RuleException ignored) {
                // 不是已注册的类型别名，忽略
            }
        }
    }

    /**
     * 收集规则内全部事实声明的「绑定名 -&gt; 类型」。
     *
     * <p>供表达式编译器在编译期解析 {@code o.amount} 里的 {@code o}。
     * 类型同样经 {@link RuleTypeRegistry} 白名单解析，
     * 因此表达式无法引用注册范围之外的类。</p>
     *
     * @param spec     条件节点
     * @param ruleName 规则名
     * @return 绑定名到类型的映射
     */
    private Map<String, Class<?>> collectBindings(RuleConditionSpec spec, String ruleName) {
        Map<String, Class<?>> bindings = new LinkedHashMap<>();
        collectBindings(spec, ruleName, bindings);
        return bindings;
    }

    /**
     * 递归收集绑定名到类型的映射。
     *
     * @param spec     条件节点
     * @param ruleName 规则名
     * @param bindings 收集结果
     */
    private void collectBindings(RuleConditionSpec spec, String ruleName,
            Map<String, Class<?>> bindings) {
        if (spec == null) {
            return;
        }
        if (spec.kind() == RuleConditionSpec.Kind.DEFINITION) {
            String binding = spec.binding();
            if (binding == null || binding.isBlank()) {
                throw new RuleException("规则[" + ruleName + "] 的事实声明缺少 binding，"
                        + "表达式无法引用该事实");
            }
            try {
                bindings.put(binding, typeRegistry.resolve(spec.type()));
            } catch (RuleException e) {
                throw new RuleException("规则[" + ruleName + "] 事实类型解析失败：" + e.getMessage(), e);
            }
        }
        if (spec.kind() == RuleConditionSpec.Kind.EXPR) {
            inferBindingsFromExpression(spec.expr(), ruleName, bindings);
        }
        for (RuleConditionSpec child : spec.children()) {
            collectBindings(child, ruleName, bindings);
        }
    }

    /**
     * 从表达式里推断未声明的绑定名。
     *
     * <p>{@code when} 直接写表达式时没有 {@code facts} 可声明，
     * 例如 {@code "when": "o.amount > 100 and o.vip is not null"}。
     * 这里扫描出所有「标识符 + 点」的形态，逐个用
     * {@link RuleTypeRegistry} 试探：能解析成已注册类型才收进绑定表。</p>
     *
     * <p>因此推断<b>不会放宽白名单</b>——类型仍然只能来自注册表。
     * 解析不出来的标识符直接忽略，交给表达式编译器报
     * 「未声明的绑定」并列出当前可用绑定，诊断信息比在这里猜更准确。</p>
     *
     * <p>显式声明的绑定优先：已在 {@code bindings} 里的名字不会被覆盖。</p>
     *
     * @param expression 表达式文本
     * @param ruleName   规则名
     * @param bindings   收集结果
     */
    private void inferBindingsFromExpression(String expression, String ruleName,
            Map<String, Class<?>> bindings) {
        collectResolvableAliases(expression, bindings);
    }

    /**
     * 装配动作列表。
     *
     * @param specs    动作定义
     * @param ruleName 规则名
     * @return 动作
     */
    private Action assembleActions(List<RuleActionSpec> specs, String ruleName) {
        List<Action> actions = new ArrayList<>(specs.size());
        for (RuleActionSpec spec : specs) {
            try {
                actions.add(actionRegistry.create(spec));
            } catch (RuleException e) {
                throw new RuleException("规则[" + ruleName + "] 动作装配失败：" + e.getMessage(), e);
            }
        }
        return Actions.all(actions);
    }

    /**
     * 装配条件树。
     *
     * <p>事实声明（{@code when.facts}）会被「摊平」为两个同级条件：
     * 一个绑定模式（{@link Pattern}）与它自己的 {@code where} 谓词。
     * 这样做的原因是引擎的执行顺序——顶层模式先完成绑定，
     * 随后同级谓词才被求值，因此 {@code where} 里可以正常引用
     * 其它绑定与全局变量，无需伪造上下文。</p>
     *
     * @param spec           条件节点
     * @param ruleName       规则名
     * @param enclosingBind  外层事实的绑定名，用于为 {@code path} 比较确定目标事实
     * @return 条件
     */
    private Condition assembleCondition(RuleConditionSpec spec, String ruleName, String enclosingBind,
            Map<String, Class<?>> bindings) {
        switch (spec.kind()) {
            case ALL:
                return assembleAnd(spec, ruleName, enclosingBind, bindings);
            case ANY:
                return assembleOr(spec, ruleName, enclosingBind, bindings);
            case NOT:
                return assembleNot(spec, ruleName, enclosingBind, bindings);
            case DEFINITION:
                throw new RuleException("规则[" + ruleName + "] 的 facts 声明必须位于 when 顶层，"
                        + "不能嵌套在 any/not 内部");
            case COMPARE:
                return assembleCompare(spec, ruleName, enclosingBind);
            case EXISTS: {
                String binding = spec.exists();
                return Conditions.of(context -> context.get(binding) != null);
            }
            case EXPR:
                return assembleExpr(spec, ruleName, bindings);
            case DECISION:
                return assembleDecision(spec, ruleName, bindings);
            default:
                throw new RuleException("规则[" + ruleName + "] 不支持的条件节点：" + spec.kind());
        }
    }

    /**
     * 装配决策节点。
     *
     * <p>决策表里每一行的 {@code when} 都是表达式，因此在装配期就用
     * 同一个表达式编译器编译成 {@link Condition}；运行期只做求值，
     * 不再解析表达式。这样决策行与规则里的 {@code expr} 行为完全一致
     * （同样的白名单、同样的类型检查、同样的字面量加宽规则）。</p>
     *
     * <p>决策表在规则集内共享，因此按 id 缓存编译结果：
     * 同一张表被多条规则引用时只编译一次。</p>
     *
     * @param spec     节点
     * @param ruleName 规则名
     * @param bindings 绑定名到类型的映射
     * @return 条件
     */
    private Condition assembleDecision(RuleConditionSpec spec, String ruleName,
            Map<String, Class<?>> bindings) {
        String decisionId = spec.type();
        DecisionTable table = decisionTables.get(decisionId);
        if (table == null) {
            throw new RuleException("规则[" + ruleName + "] 引用了未定义的决策表 '"
                    + decisionId + "'；请在规则文件的 decisions 段中声明它");
        }        RuleDecisionProvider provider = decisionRegistry.resolve(table);
        return context -> matchesDecision(provider, table, context, spec);
    }

    /**
     * 运行期比对决策结果与期望值。
     *
     * @param provider 决策实现
     * @param table    决策表
     * @param context  规则上下文
     * @param spec     节点
     * @return 是否符合期望
     */
    private static boolean matchesDecision(RuleDecisionProvider provider, DecisionTable table,
            RuleContext context, RuleConditionSpec spec) {
        Object actual = provider.decide(table, context);
        if ("IN".equals(spec.op())) {
            for (Object candidate : asList(spec.value())) {
                if (java.util.Objects.equals(candidate, actual)) {
                    return true;
                }
            }
            return false;
        }
        return java.util.Objects.equals(spec.value(), actual);
    }

    /**
     * 把值当作列表处理。
     *
     * @param value 值
     * @return 列表视图
     */
    private static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return value == null ? List.of() : List.of(value);
    }

    /**
     * 装配顶层条件。
     *
     * @param spec     条件节点
     * @param ruleName 规则名
     * @param bindings 绑定名到类型的映射
     * @return 条件
     */
    private Condition assembleTop(RuleConditionSpec spec, String ruleName,
            Map<String, Class<?>> bindings) {
        return assembleCondition(spec, ruleName, null, bindings);
    }

    /**
     * 装配 AND 节点，并把其中的事实声明摊平。
     *
     * @param spec          节点
     * @param ruleName      规则名
     * @param enclosingBind 外层事实绑定名
     * @param bindings      绑定名到类型的映射
     * @return 条件
     */
    private Condition assembleAnd(RuleConditionSpec spec, String ruleName, String enclosingBind,
            Map<String, Class<?>> bindings) {
        if (spec.children().isEmpty()) {
            throw new RuleException("规则[" + ruleName + "] all 节点缺少子节点");
        }
        List<Condition> conditions = new ArrayList<>(spec.children().size() * 2);
        for (RuleConditionSpec child : spec.children()) {
            if (child.kind() == RuleConditionSpec.Kind.DEFINITION) {
                String binding = child.binding();
                conditions.add(assembleFactPattern(child, ruleName));
                if (!child.children().isEmpty()) {
                    // where 内的 path 比较以本事实的绑定为目标
                    conditions.add(assembleCondition(child.children().get(0), ruleName, binding, bindings));
                }
                continue;
            }
            conditions.add(assembleCondition(child, ruleName, enclosingBind, bindings));
        }
        return Conditions.and(conditions);
    }

    /**
     * 装配 OR 节点。
     *
     * @param spec          节点
     * @param ruleName      规则名
     * @param enclosingBind 外层事实绑定名
     * @param bindings      绑定名到类型的映射
     * @return 条件
     */
    private Condition assembleOr(RuleConditionSpec spec, String ruleName, String enclosingBind,
            Map<String, Class<?>> bindings) {
        if (spec.children().isEmpty()) {
            throw new RuleException("规则[" + ruleName + "] any 节点缺少子节点");
        }
        List<Condition> conditions = new ArrayList<>(spec.children().size());
        for (RuleConditionSpec child : spec.children()) {
            conditions.add(assembleCondition(child, ruleName, enclosingBind, bindings));
        }
        return Conditions.or(conditions);
    }

    /**
     * 装配 NOT 节点。
     *
     * @param spec          节点
     * @param ruleName      规则名
     * @param enclosingBind 外层事实绑定名
     * @param bindings      绑定名到类型的映射
     * @return 条件
     */
    private Condition assembleNot(RuleConditionSpec spec, String ruleName, String enclosingBind,
            Map<String, Class<?>> bindings) {
        if (spec.children().isEmpty()) {
            throw new RuleException("规则[" + ruleName + "] not 节点缺少子节点");
        }
        return Conditions.not(assembleCondition(spec.children().get(0), ruleName,
                enclosingBind, bindings));
    }

    /**
     * 装配事实声明为绑定模式。
     *
     * <p>类型经 {@link RuleTypeRegistry} 白名单解析，
     * 因此规则文件无法引用未授权的类。</p>
     *
     * @param spec     节点
     * @param ruleName 规则名
     * @return 条件
     */
    private Condition assembleFactPattern(RuleConditionSpec spec, String ruleName) {
        Class<?> factType;
        try {
            factType = typeRegistry.resolve(spec.type());
        } catch (RuleException e) {
            throw new RuleException("规则[" + ruleName + "] 事实类型解析失败：" + e.getMessage(), e);
        }
        return Pattern.of(spec.binding(), factType);
    }

    /**
     * 装配比较节点。
     *
     * <p>{@code global} 形式读取全局变量后比较；
     * {@code path} 形式以「外层事实的绑定」为目标读取属性路径后比较
     * （属性读取走 {@code Pattern.property}，内部使用 ReflectUtils）。</p>
     *
     * @param spec           节点
     * @param ruleName       规则名
     * @param enclosingBind  外层事实绑定名
     * @return 条件
     */
    private Condition assembleCompare(RuleConditionSpec spec, String ruleName, String enclosingBind) {
        if (spec.global() != null) {
            String globalKey = spec.global();
            String op = spec.op();
            Object expected = spec.value();
            return Conditions.of(context -> compareValues(context.global(globalKey), op, expected));
        }
        if (spec.path() == null || spec.path().isBlank()) {
            throw new RuleException("规则[" + ruleName + "] 比较节点缺少 path 或 global");
        }
        String binding = spec.binding() != null ? spec.binding() : enclosingBind;
        if (binding == null) {
            throw new RuleException("规则[" + ruleName + "] 的 path 比较必须指定目标："
                    + "写在 facts[].where 内部（自动使用该事实的绑定），"
                    + "或在顶层 where 中显式声明 \"binding\"");
        }
        String path = spec.path();
        String op = spec.op();
        Object expected = spec.value();
        return Conditions.of(context -> {
            Object target = context.get(binding);
            if (target == null) {
                return false;
            }
            Object actual = Pattern.property(path).apply(target);
            return compareValues(actual, op, expected);
        });
    }

    /**
     * 装配表达式节点。
     *
     * @param spec     节点
     * @param ruleName 规则名
     * @param bindings 绑定名到类型的映射
     * @return 条件
     */
    private Condition assembleExpr(RuleConditionSpec spec, String ruleName,
            Map<String, Class<?>> bindings) {
        ExpressionCompiler compiler = resolveExpressionCompiler();
        if (compiler == null) {
            throw new RuleException("规则[" + ruleName + "] 使用了 expr 表达式，"
                    + "但未找到表达式编译器；请引入 utils-support-asm-starter"
                    + "（其 META-INF/extensions 已声明 RuleAsmCompiler 实现），"
                    + "或改用 path/op/value 结构化条件，"
                    + "亦可显式调用 expressionCompiler(...) 装配");
        }
        return compiler.compile(spec.expr(), ruleName, Map.copyOf(bindings));
    }

    /**
     * 解析可用的表达式编译器。
     * <p>
     * 顺序为：显式装配 → SPI 自动发现。两者都不可用时返回 {@code null}，
     * 由调用方给出明确错误——此处<b>不提供任何兜底实现</b>，
     * 因为「无法编译表达式」时返回 true 或 false 都属于危险的静默降级。
     * </p>
     *
     * @return 表达式编译器，不可用时返回 {@code null}
     */
    private ExpressionCompiler resolveExpressionCompiler() {
        ExpressionCompiler explicit = expressionCompiler;
        if (explicit != null) {
            return explicit;
        }
        ExpressionCompiler discovered = discoveredCompiler;
        if (discovered != null) {
            return discovered;
        }
        discovered = discoverExpressionCompiler();
        if (discovered == null) {
            return null;
        }
        discoveredCompiler = discovered;
        return discovered;
    }

    /**
     * 经 SPI 机制发现表达式编译器实现。
     * <p>
     * 本模块只持有 {@link ExpressionCompiler} 接口，实现位于
     * {@code utils-support-asm-starter}，通过
     * {@code META-INF/extensions/com.chua.common.support.rule.file.RuleAssembler$ExpressionCompiler}
     * 声明。发现过程按「多个实现取首个」处理，并对异常做隔离——
     * SPI 基础设施故障不应让规则装载崩在无关的栈上。
     * </p>
     *
     * @return 发现的实现，未发现时返回 {@code null}
     */
    private ExpressionCompiler discoverExpressionCompiler() {
        try {
            List<ExpressionCompiler> found = ServiceProvider.of(ExpressionCompiler.class).collect();
            if (found.isEmpty()) {
                return null;
            }
            if (found.size() > 1) {
                log.warn("[rule] 发现 {} 个表达式编译器实现（{}），使用首个：{}",
                        found.size(), found, found.getFirst().getClass().getName());
            }
            return found.getFirst();
        } catch (Throwable t) {
            log.debug("[rule] 表达式编译器 SPI 发现失败", t);
            return null;
        }
    }

    /**
     * 比较两个值。
     *
     * <p>数字统一按 {@link BigDecimal} 比较，避免装箱类型不一致导致
     * {@code ClassCastException}——例如事实属性是 {@code long}，
     * 而规则文件里的字面量被解析为 {@code Integer}，
     * 若直接调用 {@code Long.compareTo(Integer)} 会抛异常，
     * 在断路器场景下将被 fail-closed 判成「全部拒绝」。</p>
     *
     * @param left     左值
     * @param op       运算符
     * @param expected 期望值
     * @return 是否成立
     */
    private static boolean compareValues(Object left, String op, Object expected) {
        String normalized = op == null ? "EQ" : op.trim().toUpperCase();
        // 正则/通配符只对字符串有意义，必须在数值/字典序比较之前判定
        if (isPatternOp(normalized)) {
            if (!(left instanceof String text) || !(expected instanceof String pattern)) {
                return false;
            }
            return "MATCHES".equals(normalized) || "=~".equals(normalized) || "REGEX".equals(normalized)
                    ? Pattern.regexMatches(text, pattern)
                    : Pattern.wildcardMatches(text, pattern);
        }
        if (left == null || expected == null) {
            return switch (normalized) {
                case "EQ", "==", "=" -> left == expected;
                case "NE", "!=" -> left != expected;
                default -> false;
            };
        }
        int result;
        if (left instanceof Number leftNumber && expected instanceof Number rightNumber) {
            result = toDecimal(leftNumber).compareTo(toDecimal(rightNumber));
        } else if (left instanceof String leftText && expected instanceof String rightText) {
            result = leftText.compareTo(rightText);
        } else if (left instanceof Boolean || expected instanceof Boolean) {
            result = left.equals(expected) ? 0 : 1;
        } else if (left instanceof Comparable<?> && left.getClass() == expected.getClass()) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) left;
            result = comparable.compareTo(expected);
        } else {
            return "NE".equals(normalized) || "!=".equals(normalized);
        }
        return switch (normalized) {
            case "EQ", "==", "=" -> result == 0;
            case "NE", "!=" -> result != 0;
            case "GT", ">" -> result > 0;
            case "GTE", ">=" -> result >= 0;
            case "LT", "<" -> result < 0;
            case "LTE", "<=" -> result <= 0;
            default -> false;
        };
    }

    /**
     * 判断是否为字符串模式匹配算子。
     *
     * @param normalized 归一化后的算子
     * @return 是返回 true
     */
    private static boolean isPatternOp(String normalized) {
        return switch (normalized) {
            case "MATCHES", "=~", "REGEX", "WILDCARD", "LIKE" -> true;
            default -> false;
        };
    }

    /**
     * 把任意数字转换为 {@link BigDecimal}。
     *
     * <p>{@code float}/{@code double} 先转字符串再转 BigDecimal，
     * 以保留其十进制语义（直接强转会带上二进制误差）。</p>
     *
     * @param number 数字
     * @return BigDecimal
     */
    private static BigDecimal toDecimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof Double || number instanceof Float) {
            return new BigDecimal(number.toString());
        }
        if (number instanceof BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        return BigDecimal.valueOf(number.longValue());
    }
}
