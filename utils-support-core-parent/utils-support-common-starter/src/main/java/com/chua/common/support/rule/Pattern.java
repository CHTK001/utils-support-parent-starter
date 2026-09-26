package com.chua.common.support.rule;

import com.chua.common.support.reflection.ReflectUtils;

import java.util.List;
import java.util.function.Predicate;

/**
 * 事实模式（Pattern）。
 *
 * <p>从工作内存中匹配指定类型的事实，并施加额外约束。
 * 匹配成功的事实会按绑定名写入 {@link RuleContext}，供 RHS 动作使用。
 * 这是 Drools 中 {@code $name : Type(constraints)} 的等价物。</p>
 *
 * <h3>匹配算法</h3>
 * <ol>
 *   <li>按声明类型从工作内存索引中取候选事实（避免全量扫描）</li>
 *   <li>逐个施加约束 {@code constraint}</li>
 *   <li>命中的事实按声明顺序收集，交由引擎做元组组合</li>
 * </ol>
 *
 * <h3>约束写法</h3>
 * <p>约束是普通的 {@link Predicate}，属性读取既可用 lambda 直接调 getter，
 * 也可用 {@link #property(String)} 生成的属性取值器（统一走
 * {@link ReflectUtils}，支持 {@code user.name} 这类嵌套路径）。</p>
 *
 * <h3>匿名模式</h3>
 * <p>绑定名为 {@code null} 或空白时视为匿名模式：参与匹配但不写入上下文，
 * 用于「只关心存在性」的场景。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 具名模式：命中后可用 context.get("order") 取回
 * Pattern order = Pattern.of("order", OrderFact.class, o -> o.getAmount() > 1000);
 *
 * // 匿名模式：只判断存在性
 * Pattern anyBlock = Pattern.of(BlockedFact.class);
 *
 * // 基于属性路径的约束（嵌套取值）
 * Pattern vip = Pattern.of("v", VipFact.class,
 *         Pattern.compare("level", Pattern.Op.GTE, 3));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Pattern implements Condition {

    /**
     * 属性比较运算符。
     */
    public enum Op {

        /**
         * 等于
         */
        EQ,

        /**
         * 不等于
         */
        NE,

        /**
         * 大于
         */
        GT,

        /**
         * 大于等于
         */
        GTE,

        /**
         * 小于
         */
        LT,

        /**
         * 小于等于
         */
        LTE,

        /**
         * 正则全匹配（等价于 {@code String.matches}）
         */
        MATCHES,

        /**
         * 通配符匹配：{@code *} 任意长度、{@code ?} 单字符
         */
        WILDCARD
    }

    /**
     * 属性路径分隔符
     */
    private static final String PATH_SEPARATOR = ".";

    /**
     * 编译后的正则缓存
     *
     * <p>有界：规则可热更新，正则来自配置，无界缓存会泄漏。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern>
            REGEX_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 正则缓存上限，超出即整体清空
     */
    private static final int REGEX_CACHE_LIMIT = 256;

    /**
     * 绑定名，null 表示匿名模式
     */
    private final String binding;

    /**
     * 事实类型
     */
    private final Class<?> factType;

    /**
     * 附加约束，null 表示只做类型匹配
     */
    private final Predicate<Object> constraint;

    /**
     * 命中条数上限，{@code <= 0} 表示不限制
     */
    private final int limit;

    /**
     * 创建事实模式。
     *
     * @param binding    绑定名，可为 null 表示匿名
     * @param factType   事实类型，不允许为 null
     * @param constraint 附加约束，可为 null
     * @param limit      命中条数上限，{@code <= 0} 表示不限制
     */
    private Pattern(String binding, Class<?> factType, Predicate<Object> constraint, int limit) {
        if (factType == null) {
            throw new RuleException("事实类型不能为 null");
        }
        this.binding = (binding == null || binding.isBlank()) ? null : binding.trim();
        this.factType = factType;
        this.constraint = constraint;
        this.limit = limit;
    }

    /**
     * 创建具名事实模式。
     *
     * <p>约束的入参类型由 {@code factType} 推导，
     * 因此可直接写 {@code o -> o.getAmount() > 1000} 而无需强转。</p>
     *
     * @param binding    绑定名
     * @param factType   事实类型
     * @param constraint 附加约束，可为 null
     * @param <T>        事实类型泛型
     * @return 事实模式
     */
    public static <T> Pattern of(String binding, Class<T> factType, Predicate<? super T> constraint) {
        return new Pattern(binding, factType, adapt(factType, constraint), 0);
    }

    /**
     * 创建具名事实模式（不施加额外约束）。
     *
     * <p>只按类型匹配，命中后按绑定名写入上下文。</p>
     *
     * @param binding  绑定名
     * @param factType 事实类型
     * @param <T>      事实类型泛型
     * @return 事实模式
     */
    public static <T> Pattern of(String binding, Class<T> factType) {
        return new Pattern(binding, factType, null, 0);
    }

    /**
     * 创建匿名事实模式（只判断存在性）。
     *
     * @param factType 事实类型
     * @param <T>      事实类型泛型
     * @return 事实模式
     */
    public static <T> Pattern of(Class<T> factType) {
        return new Pattern(null, factType, null, 0);
    }

    /**
     * 创建带命中上限的事实模式。
     *
     * @param binding    绑定名
     * @param factType   事实类型
     * @param constraint 附加约束，可为 null
     * @param limit      命中条数上限，{@code <= 0} 表示不限制
     * @param <T>        事实类型泛型
     * @return 事实模式
     */
    public static <T> Pattern limit(String binding, Class<T> factType, Predicate<? super T> constraint, int limit) {
        return new Pattern(binding, factType, adapt(factType, constraint), limit);
    }

    /**
     * 将类型化约束适配为统一的 {@code Predicate<Object>}。
     *
     * @param factType   事实类型
     * @param constraint 类型化约束
     * @param <T>        事实类型泛型
     * @return 适配后的约束，源约束为 null 时返回 null
     */
    @SuppressWarnings("unchecked")
    private static <T> Predicate<Object> adapt(Class<T> factType, Predicate<? super T> constraint) {
        if (constraint == null) {
            return null;
        }
        return candidate -> constraint.test((T) candidate);
    }

    /**
     * 由属性名生成属性取值器。
     *
     * <p>支持 {@code name} 与 {@code user.name} 两种形式，
     * 逐段通过 {@link ReflectUtils#getField(Object, String)} 取值，
     * 任意一段为 null 时整体返回 null。</p>
     *
     * @param propertyPath 属性路径
     * @return 属性取值函数
     */
    public static java.util.function.Function<Object, Object> property(String propertyPath) {
        if (propertyPath == null || propertyPath.isBlank()) {
            throw new RuleException("属性路径不能为空");
        }
        String[] segments = propertyPath.trim().split("\\" + PATH_SEPARATOR);
        return target -> {
            Object current = target;
            for (String segment : segments) {
                if (current == null) {
                    return null;
                }
                current = ReflectUtils.getField(current, segment);
            }
            return current;
        };
    }

    /**
     * 按属性路径与运算符构造约束。
     *
     * <p>属性值需实现 {@link Comparable}；属性为 null 或类型不可比较时，
     * 该约束判定为不命中。返回类型为 {@code Predicate<Object>}，
     * 可直接用于声明了具体事实类型的 {@link #of(String, Class, Predicate)}。</p>
     *
     * @param propertyPath 属性路径
     * @param op           比较运算符
     * @param value        比较值
     * @return 约束断言
     */
    public static Predicate<Object> compare(String propertyPath, Op op, Comparable<?> value) {
        if (op == null) {
            throw new RuleException("比较运算符不能为 null");
        }
        java.util.function.Function<Object, Object> reader = property(propertyPath);
        return target -> {
            Object actual = reader.apply(target);
            if (isPatternOp(op)) {
                // 正则/通配符只对字符串有意义，且必须在 Comparable 分支之前判定：
                // String 本身也是 Comparable，落到 compareTo 会变成字典序比较
                if (!(actual instanceof String text) || !(value instanceof String pattern)) {
                    return false;
                }
                return op == Op.MATCHES
                        ? regexMatches(text, pattern)
                        : wildcardMatches(text, pattern);
            }
            if (actual == null || value == null) {
                return switch (op) {
                    case EQ -> actual == value;
                    case NE -> actual != value;
                    default -> false;
                };
            }
            if (!(actual instanceof Comparable)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            int result = ((Comparable<Object>) actual).compareTo(value);
            return switch (op) {
                case EQ -> result == 0;
                case NE -> result != 0;
                case GT -> result > 0;
                case GTE -> result >= 0;
                case LT -> result < 0;
                case LTE -> result <= 0;
                case MATCHES, WILDCARD -> false;
            };
        };
    }

    /**
     * 是否为字符串模式匹配算子。
     *
     * @param op 算子
     * @return 是返回 true
     */
    private static boolean isPatternOp(Op op) {
        return op == Op.MATCHES || op == Op.WILDCARD;
    }

    /**
     * 把通配符翻译为正则。
     *
     * <p>{@code *} → {@code .*}、{@code ?} → {@code .}；
     * 其余正则元字符（{@code . \ + ( ) [ ] { } ^ $ |}）一律转义，
     * 这样「通配符」不会退化成「正则」——{@code a.b} 只匹配字面量 {@code a.b}。</p>
     *
     * @param glob 通配符
     * @return 等价正则
     */
    public static String wildcardToRegex(String glob) {
        if (glob == null) {
            return null;
        }
        StringBuilder regex = new StringBuilder(glob.length() * 2 + 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}',
                     '^', '$', '|' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return regex.toString();
    }

    /**
     * 编译并缓存正则。
     *
     * <p>规则求值在热路径上，每次都 {@code Pattern.compile} 会明显拖慢性能，
     * 因此这里做一层<b>有界</b>缓存：命中即复用，超出上限就整体清空。
     * 有界是必须的——规则文件可热更新，正则来自配置，
     * 无界缓存会变成内存泄漏。</p>
     *
     * @param regex 正则
     * @return 编译结果
     * @throws RuleException 正则非法时抛出
     */
    public static java.util.regex.Pattern compileRegex(String regex) {
        if (regex == null) {
            throw new RuleException("正则不能为 null");
        }
        java.util.regex.Pattern cached = REGEX_CACHE.get(regex);
        if (cached != null) {
            return cached;
        }
        try {
            java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(regex);
            if (REGEX_CACHE.size() >= REGEX_CACHE_LIMIT) {
                REGEX_CACHE.clear();
            }
            REGEX_CACHE.put(regex, compiled);
            return compiled;
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new RuleException("正则表达式非法：" + regex + "（" + e.getDescription() + "）", e);
        }
    }

    /**
     * 正则全匹配。
     *
     * @param value 待匹配值
     * @param regex 正则
     * @return 匹配返回 true
     */
    public static boolean regexMatches(String value, String regex) {
        return value != null && compileRegex(regex).matcher(value).matches();
    }

    /**
     * 通配符全匹配。
     *
     * @param value 待匹配值
     * @param glob  通配符
     * @return 匹配返回 true
     */
    public static boolean wildcardMatches(String value, String glob) {
        return value != null && regexMatches(value, wildcardToRegex(glob));
    }

    /**
     * 获取绑定名。
     *
     * @return 绑定名，匿名模式返回 null
     */
    public String binding() {
        return binding;
    }

    /**
     * 获取事实类型。
     *
     * @return 事实类型
     */
    public Class<?> factType() {
        return factType;
    }

    /**
     * 判断是否为匿名模式。
     *
     * @return 匿名模式返回 true
     */
    public boolean anonymous() {
        return binding == null;
    }

    /**
     * 判断候选事实是否命中本模式。
     *
     * @param fact 候选事实
     * @return 命中返回 true
     */
    public boolean matches(Object fact) {
        if (fact == null || !factType.isInstance(fact)) {
            return false;
        }
        if (constraint == null) {
            return true;
        }
        try {
            return constraint.test(fact);
        } catch (RuntimeException e) {
            // 约束异常按不命中处理，避免单条脏数据击穿整轮推理
            return false;
        }
    }

    /**
     * 从候选列表中筛出命中的事实。
     *
     * @param candidates 候选事实列表
     * @return 命中的事实列表，永不为 null
     */
    public List<Object> filter(List<Object> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Object> matched = new java.util.ArrayList<>(Math.min(candidates.size(), 16));
        for (Object candidate : candidates) {
            if (!matches(candidate)) {
                continue;
            }
            matched.add(candidate);
            if (limit > 0 && matched.size() >= limit) {
                break;
            }
        }
        return matched;
    }

    @Override
    public boolean test(RuleContext context) {
        if (context == null) {
            return false;
        }
        List<Object> candidates = context.factsOf(factType);
        if (candidates.isEmpty()) {
            return false;
        }
        List<Object> matched = filter(candidates);
        if (matched.isEmpty()) {
            return false;
        }
        if (binding != null) {
            context.bind(binding, matched.get(0));
        }
        return true;
    }

    @Override
    public String toString() {
        return "Pattern[" + (binding == null ? "<anonymous>" : binding) + ":" + factType.getSimpleName() + "]";
    }
}
