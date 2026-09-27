package com.chua.common.support.ai.rule;

import com.chua.common.support.reflection.ReflectUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 规则求值上下文。
 *
 * <p>五层变量，读取优先级由高到低：</p>
 * <ol>
 *   <li><b>vars</b>——规则定义期用 {@code Rule.var(...)} 声明的变量。
 *       优先级最高，因为它是规则作者对本规则的显式声明，
 *       刻意盖过任何外部同名数据。</li>
 *   <li><b>current</b>——本次执行累积的中间结果。前序规则的
 *       {@code then} 写入的变量落在这一层，是层与层之间传值的主通道。</li>
 *   <li><b>external</b>——外部注入的<b>计算结果</b>，当前唯一来源是
 *       决策展平变量（{@code ai.decision} 的 {@code DecisionBatch#flatten()}）。
 *       单独成层而不是塞进 input，是因为它比原始输入<b>新</b>——
 *       对同一个问题，决策算出的结论应当盖过调用方随手传进来的同名值；
 *       但仍低于 current，规则自己算出的中间结果又比它新。</li>
 *   <li><b>input</b>——流程输入。可以是 {@link Map}，也可以是任意业务对象
 *       （此时按 §{@link #get(String)} 的路径下探逐段取属性）。</li>
 *   <li><b>environment</b>——环境量，如租户、灰度开关、当前时间。</li>
 * </ol>
 *
 * <p><b>刻意区分「变量不存在」与「变量值为 null」</b>。
 * {@link #get(String)} 两者都返回 null，因此判断存在性必须用
 * {@link #contains(String)}：变量存在但值为 null 在配置驱动、开关类
 * 场景里是合法且有意义的（显式关闭），与「没配这个变量」不是一回事。
 * 需要「必须存在」的场景用 {@link #require(String)}。</p>
 *
 * <p>本类有真实的可变状态（{@link #set(String, Object)} 累积中间结果），
 * 因此<b>不是</b> record，也不参与 value class 目标。
 * 线程不安全：一次规则执行用一个实例，不要跨线程共享。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleContext {

    /**
     * 路径分段符。{@code order.amount} 表示「order 的 amount 段」。
     */
    public static final char SEPARATOR = '.';

    /**
     * 规则声明的变量，本次执行内可被 {@link #declare(String, Object)} 累积
     */
    private final Map<String, Object> declared;

    /**
     * 本次执行累积的中间结果
     */
    private final Map<String, Object> current = new LinkedHashMap<>();

    /**
     * 外部注入的计算结果，当前用于承载决策展平变量
     */
    private final Map<String, Object> external;

    /**
     * 流程输入，可为 {@link Map} 或任意业务对象
     */
    private final Object input;

    /**
     * 环境量
     */
    private final Map<String, Object> environment;

    /**
     * 用输入构造上下文，不带 vars 与 environment。
     *
     * @param input 流程输入，可为 null
     * @return 规则上下文
     */
    public static RuleContext of(Object input) {
        return new RuleContext(null, null, input, null);
    }

    /**
     * 用输入与环境量构造上下文。
     *
     * @param input       流程输入，可为 null
     * @param environment 环境量，可为 null
     * @return 规则上下文
     */
    public static RuleContext of(Object input, Map<String, Object> environment) {
        return new RuleContext(null, null, input, environment);
    }

    /**
     * 用规则声明的变量、输入与环境量构造上下文。
     *
     * @param vars        规则声明的变量，可为 null
     * @param input       流程输入，可为 null
     * @param environment 环境量，可为 null
     * @return 规则上下文
     */
    public static RuleContext of(Map<String, Object> vars, Object input, Map<String, Object> environment) {
        return new RuleContext(vars, null, input, environment);
    }

    /**
     * 用规则声明的变量、外部计算结果、输入与环境量构造上下文。
     *
     * <p>五层齐全的工厂，供规则执行器使用：{@code external} 装决策展平变量。
     * 不装配决策实现时用 {@code external = null} 即可，
     * 规则引擎在这种情况下完全<b>不触碰</b>决策包。</p>
     *
     * @param vars        规则声明的变量，可为 null
     * @param external    外部计算结果（如决策展平变量），可为 null
     * @param input       流程输入，可为 null
     * @param environment 环境量，可为 null
     * @return 规则上下文
     */
    public static RuleContext of(Map<String, Object> vars,
                                 Map<String, Object> external,
                                 Object input,
                                 Map<String, Object> environment) {
        return new RuleContext(vars, external, input, environment);
    }

    private RuleContext(Map<String, Object> vars,
                        Map<String, Object> external,
                        Object input,
                        Map<String, Object> environment) {
        // declared 可变（执行器逐条注入规则声明），其余两层在构造期就冻结
        this.declared = new LinkedHashMap<>(nullTolerant(vars));
        this.external = nullTolerant(external);
        this.input = input;
        this.environment = nullTolerant(environment);
    }

    /**
     * 把变量表转成不可变快照，<b>允许值为 null</b>。
     *
     * <p>刻意不用 {@link Map#copyOf(Map)}：它拒绝 null 值，
     * 而 {@code Rule.var("开关", null)} 表示「显式置空」是
     * {@link RuleBuilder#var(String, Object)} 明文支持的语义。
     * 规则声明的变量被拒会平白把「显式关闭」变成
     * {@link NullPointerException}，且报错点在上下文构造期，
     * 与真正的错误位置（规则定义处）相隔很远，极难定位。</p>
     *
     * @param table 变量表，可为 null
     * @return 不可变快照；入参为 null 时返回空表
     */
    private static Map<String, Object> nullTolerant(Map<String, Object> table) {
        if (table == null) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(table));
    }

    /**
     * 读取变量：先按整串名精确匹配，再按 {@link #SEPARATOR} 逐段下探。
     *
     * <p><b>刻意把「精确匹配」与「路径下探」合在一个方法里</b>。
     * 分成 {@code get} 与 {@code getPath} 两个方法会出现一个很难察觉的坑：
     * 写 {@code get("order.amount")} 时因为不是打平 key 而静默返回 null，
     * 条件恒不成立，规则永不命中，且没有任何报错。
     * 同一个包的两处代码一个用 {@code get} 一个用 {@code getPath}、
     * 结果一个能跑一个不能跑，这种不一致比多一个方法危险得多。
     * 合并后 {@code get("order.amount")} 无论输入是打平 key 还是嵌套结构都拿得到值。</p>
     *
     * <p>精确匹配优先于路径下探，因此调用方可以用 {@code {"order.amount": 1}}
     * 这种打平 key 规避逐段解析。逐段下探时首段走五层优先级，
     * 后续段在 {@link Map} 上按 key 取、在其他对象上走
     * {@link ReflectUtils#getField(Object, String)} 取字段；
     * 任一段缺失即返回 null，不抛异常——本方法用于 {@code when} 条件，
     * 条件里判空比在读取时炸掉更好写。</p>
     *
     * @param name 变量名或路径，如 {@code vip} / {@code order.amount}
     * @return 变量值；不存在或值为 null 时均返回 null
     */
    public Object get(String name) {
        Objects.requireNonNull(name, "name 不能为 null");
        if (declared.containsKey(name)) {
            return declared.get(name);
        }
        if (current.containsKey(name)) {
            return current.get(name);
        }
        if (external.containsKey(name)) {
            return external.get(name);
        }
        if (input instanceof Map<?, ?> map && map.containsKey(name)) {
            return map.get(name);
        }
        if (environment.containsKey(name)) {
            return environment.get(name);
        }
        return resolvePath(name);
    }

    /**
     * 判断变量是否存在。
     *
     * <p>这是与 {@link #get(String)} 的关键区别：
     * 变量存在但值为 null 时，本方法返回 true 而 {@code get} 返回 null。</p>
     *
     * <p>路径解析规则与 {@link #get(String)} 一致：整串精确匹配不到时，
     * 尝试逐段下探，下探得到非 null 值即视为存在。
     * 路径中途命中一个 null 段时按「不存在」处理。</p>
     *
     * @param name 变量名或路径
     * @return 存在返回 true
     */
    public boolean contains(String name) {
        Objects.requireNonNull(name, "name 不能为 null");
        if (declared.containsKey(name) || current.containsKey(name) || external.containsKey(name)
                || (input instanceof Map<?, ?> map && map.containsKey(name))
                || environment.containsKey(name)) {
            return true;
        }
        return resolvePath(name) != null;
    }

    /**
     * 读取必需变量。
     *
     * @param name 变量名
     * @return 变量值，值本身允许为 null
     * @throws RuleException 变量不存在时
     */
    public Object require(String name) {
        if (!contains(name)) {
            throw new RuleException("必需变量不存在: " + name);
        }
        return get(name);
    }

    /**
     * 读取变量并给默认值。
     *
     * @param name         变量名
     * @param defaultValue 变量不存在或值为 null 时返回的默认值
     * @param <T>          返回值类型
     * @return 变量值或默认值
     */
    public <T> T getOrDefault(String name, T defaultValue) {
        Object value = get(name);
        return value == null ? defaultValue : cast(value);
    }

    /**
     * 逐段下探读取。
     *
     * <p>只被 {@link #get(String)} 在整串精确匹配失败后调用，
     * 因此不必再查五层的第一段以外的层，也不做整串匹配。</p>
     *
     * @param path 路径
     * @return 变量值；无分隔符或任一段缺失时返回 null
     */
    private Object resolvePath(String path) {
        int index = path.indexOf(SEPARATOR);
        if (index < 0) {
            return null;
        }
        Object node = get(path.substring(0, index));
        int from = index + 1;
        while (node != null && from <= path.length()) {
            int next = path.indexOf(SEPARATOR, from);
            String segment = next < 0 ? path.substring(from) : path.substring(from, next);
            node = segment(node, segment);
            from = next < 0 ? path.length() + 1 : next + 1;
        }
        return node;
    }

    /**
     * 读取数值并与阈值比较用的强转。
     *
     * <p>{@link #get(String)} 返回 {@link Object}，直接和 {@code 0.8D} 比较
     * 必须先强转，条件里写起来很啰嗦；本方法把强转收在一处。
     * 缺失、值为 null、不是数值、不是可解析的字符串，一律返回默认值。</p>
     *
     * @param path         变量名或路径，如 {@code order.amount}
     * @param defaultValue 取不到数值时的返回值
     * @return 数值
     */
    public double number(String path, double defaultValue) {
        Object value = get(path);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof CharSequence text) {
            try {
                return Double.parseDouble(text.toString());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 写入本次执行的中间变量，落在 current 层。
     *
     * <p>current 层优先级高于 external、input 与 environment、低于 vars，
     * 因此前序规则算出的中间结果能盖住决策结论与原始输入，
     * 但盖不过规则显式声明的变量。</p>
     *
     * @param name  变量名
     * @param value 变量值，允许为 null（写入后 {@link #contains(String)} 仍为 true）
     * @return 自身，便于链式调用
     */
    public RuleContext set(String name, Object value) {
        current.put(Objects.requireNonNull(name, "name 不能为 null"), value);
        return this;
    }

    /**
     * 声明一个高优先级变量，落在 vars 层。
     *
     * <p>与 {@link #set(String, Object)} 写 current 层的区别在于优先级：
     * vars 层是<b>最高</b>，盖过决策展平结果、原始输入和其他规则写下的中间结果。
     * 供 {@link RuleExecutor} 在规则命中时注入该规则的 {@code var} 声明——
     * 规则作者对自己这条规则的显式声明，是这条规则内部最权威的数据。</p>
     *
     * <p>vars 层是本次执行内累积的可变表（一条规则一个上下文时，
     * 逐条注入各规则的声明），因此与 vars()/external()/environment()
     * 返回的不可变快照不同。</p>
     *
     * @param name  变量名
     * @param value 变量值，允许为 null
     * @return 自身，便于链式调用
     * @throws NullPointerException 变量名为 null 时
     */
    public RuleContext declare(String name, Object value) {
        declared.put(Objects.requireNonNull(name, "name 不能为 null"), value);
        return this;
    }

    /**
     * 规则声明的变量，只读。
     *
     * @return 不可变变量表
     */
    public Map<String, Object> vars() {
        return Collections.unmodifiableMap(declared);
    }

    /**
     * 本次执行累积的中间变量，只读快照。
     *
     * @return 不可变变量表
     */
    public Map<String, Object> current() {
        return Collections.unmodifiableMap(current);
    }

    /**
     * 外部注入的计算结果，只读。
     *
     * @return 不可变变量表
     */
    public Map<String, Object> external() {
        return external;
    }

    /**
     * 流程输入。
     *
     * @return 流程输入，可为 null
     */
    public Object input() {
        return input;
    }

    /**
     * 环境量，只读。
     *
     * @return 不可变变量表
     */
    public Map<String, Object> environment() {
        return environment;
    }

    /**
     * 逐段下探一层。
     *
     * @param node    当前节点
     * @param segment 段名
     * @return 下一层节点；节点类型不支持下探时返回 null
     */
    private static Object segment(Object node, String segment) {
        if (node instanceof Map<?, ?> map) {
            return map.get(segment);
        }
        try {
            return ReflectUtils.getField(node, segment);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
