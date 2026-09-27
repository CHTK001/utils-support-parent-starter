package com.chua.common.support.ai.rule;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.spi.ServiceProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 规则条件表达式：把表达式文本编译为可对 {@link RuleContext} 求值的条件。
 *
 * <p>这是 {@code when} 的<b>默认形态</b>。表达式能力复用 common-starter
 * 已有的 {@link ExpressionParser} SPI（默认实现注册名为 {@code expr}），
 * 本类只做两件它不做的事：路径归一化与求值。</p>
 *
 * <pre>{@code
 * new RuleExpression("order.amount >= 10000 AND user.level != 'VIP'")
 * new RuleExpression("fraud.confidence > 0.8")        // 决策展平变量同样按路径读
 * new RuleExpression("status IN ('vip','new')")
 * }</pre>
 *
 * <p><b>为什么需要路径归一化</b>：共享的 {@code expr} 解析器只接受
 * {@code [A-Za-z0-9_]} 组成的列名，{@code order.amount} 会在第一个点处解析失败。
 * 但带点的路径正是规则最需要的形态（{@code RuleContext#get(String)}
 * 本来就按点逐段下探）。本类因此在交给解析器之前把 {@code a.b.c}
 * 换成合成标识 {@code __rp0}，求值时再按映射还原成原路径，
 * 仍由 {@code RuleContext} 完成逐段读取。合成名会先与表达式里出现过的
 * 所有标识比对，保证不与真实变量名撞名。这样无需改动共享解析器，
 * 避免影响同链路上 {@code sql}、{@code cypher}、{@code lucene} 的使用方。</p>
 *
 * <p><b>归一化不做的事</b>：</p>
 * <ul>
 *   <li><b>不改字符串字面量内部</b>——{@code name LIKE 'a.b'} 里的 {@code a.b}
 *       是数据不是路径，扫描器按引号状态跳过</li>
 *   <li><b>不引入新语法</b>——运算符、优先级、{@code IN}/{@code LIKE}/
 *       {@code BETWEEN}/{@code IS NULL} 全部沿用共享解析器的定义</li>
 *   <li><b>不做方法调用</b>——共享解析器本就不支持，这里也不绕开</li>
 * </ul>
 *
 * <p><b>解析发生在构造期</b>：语法错误立刻抛 {@link RuleException}，
 * 不会留到规则真正执行时才暴露。</p>
 *
 * <p>本类不可变，线程安全。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleExpression {

    /**
     * 合成标识前缀。选下划线开头是因为共享解析器接受下划线做列名首字符，
     * 而真实业务变量极少用双下划线开头；仍会与表达式内已有标识做碰撞比对。
     */
    private static final String SYNTHETIC_PREFIX = "__rp";

    /**
     * 表达式 SPI 扩展名
     */
    private static final String PARSER_EXTENSION = "expr";

    /**
     * 原始表达式文本
     */
    private final String text;

    /**
     * 归一化后的文本，仅用于错误提示定位
     */
    private final String normalized;

    /**
     * 解析得到的表达式树
     */
    private final BTreeNode tree;

    /**
     * 合成标识到原始路径的映射
     */
    private final Map<String, String> paths;

    private RuleExpression(String text, String normalized, BTreeNode tree, Map<String, String> paths) {
        this.text = text;
        this.normalized = normalized;
        this.tree = tree;
        this.paths = Map.copyOf(paths);
    }

    /**
     * 编译一条条件表达式。
     *
     * @param text 表达式文本，不可为 null 或空白
     * @return 编译好的表达式
     * @throws RuleException 表达式为空或语法错误时
     */
    public static RuleExpression of(String text) {
        if (text == null || text.isBlank()) {
            throw new RuleException("表达式不能为 null 或空白");
        }
        Map<String, String> paths = new HashMap<>();
        String normalized = normalize(text, paths);
        ExpressionParser parser = ServiceProvider.of(ExpressionParser.class).getExtension(PARSER_EXTENSION);
        if (parser == null) {
            throw new RuleException("未找到 " + PARSER_EXTENSION + " 表达式解析器，表达式无法编译: " + text);
        }
        try {
            BTreeNode tree = parser.parse(normalized);
            return new RuleExpression(text, normalized, tree, paths);
        } catch (RuntimeException e) {
            throw new RuleException("表达式语法错误: " + text + "（归一化后: " + normalized + "）", e);
        }
    }

    /**
     * 对上下文求值。
     *
     * @param context 规则上下文，不可为 null
     * @return 表达式成立返回 true；<b>变量缺失按不成立处理</b>，
     *         不用异常打断规则求值——条件里判空比炸掉更好写
     */
    public boolean test(RuleContext context) {
        Objects.requireNonNull(context, "context 不能为 null");
        Object value = evaluate(tree, context);
        return value instanceof Boolean b ? b : false;
    }

    /**
     * 原始表达式文本。
     *
     * @return 表达式文本
     */
    public String text() {
        return text;
    }

    /**
     * 归一化后文本，便于排查路径替换问题。
     *
     * @return 归一化文本
     */
    public String normalized() {
        return normalized;
    }

    /**
     * 递归求值。
     *
     * @param node    节点
     * @param context 规则上下文
     * @return 求值结果；布尔节点返回 {@link Boolean}，比较节点返回 {@link Boolean}
     */
    private Object evaluate(BTreeNode node, RuleContext context) {
        if (node == null) {
            return Boolean.FALSE;
        }
        return switch (node.getType()) {            case LOGIC -> evaluateLogic(node, context);
            case NOT -> !truthy(evaluate(node.getLeft(), context));
            case COMPARE -> evaluateCompare(node, context);
            case COLUMN -> resolve(node.getOperator(), context);
            case VALUE -> node.getValue();
            case FUNCTION, RAW -> throw new RuleException("表达式暂不支持函数调用与原始节点: " + text);
        };
    }

    /**
     * 求值 AND / OR，短路。
     *
     * @param node    节点
     * @param context 规则上下文
     * @return 布尔结果
     */
    private Object evaluateLogic(BTreeNode node, RuleContext context) {
        String operator = node.getOperator() == null ? "" : node.getOperator().trim().toUpperCase();
        boolean or = "OR".equals(operator);
        Object left = evaluate(node.getLeft(), context);
        if (truthy(left) == or) {
            // OR 遇到 true、AND 遇到 false，即可短路
            return or;
        }
        return truthy(evaluate(node.getRight(), context));
    }

    /**
     * 求值比较运算。
     *
     * @param node    节点
     * @param context 规则上下文
     * @return 布尔结果
     */
    private Object evaluateCompare(BTreeNode node, RuleContext context) {
        String operator = node.getOperator() == null ? "" : node.getOperator().trim().toUpperCase();
        // BETWEEN 的右子树是 COMPARE(AND)，两个边界分别挂在它的 left / right 上，
        // 不能当普通值求值，否则会走进比较分支报「无法识别的比较符: AND」
        if ("BETWEEN".equals(operator)) {
            return between(node, context);
        }
        Object leftRaw = evaluate(node.getLeft(), context);
        Object rightRaw = evaluate(node.getRight(), context);

        return switch (operator) {
            case "IS NULL" -> leftRaw == null;
            case "IS NOT NULL" -> leftRaw != null;
            case "IN" -> contains(rightRaw, leftRaw);
            case "LIKE" -> like(leftRaw, rightRaw);
            case "=", "==" -> equalsValue(leftRaw, rightRaw);
            case "!=", "<>" -> !equalsValue(leftRaw, rightRaw);
            case ">" -> compare(leftRaw, rightRaw) > 0;
            case ">=" -> compare(leftRaw, rightRaw) >= 0;
            case "<" -> compare(leftRaw, rightRaw) < 0;
            case "<=" -> compare(leftRaw, rightRaw) <= 0;
            default -> throw new RuleException("无法识别的比较符: " + operator + "（表达式: " + text + "）");
        };
    }

    /**
     * 解析列引用：合成名还原成原路径，再交给 {@link RuleContext} 逐段读取。
     */
    private Object resolve(Object column, RuleContext context) {
        String key = String.valueOf(column);
        String path = paths.get(key);
        return context.get(path == null ? key : path);
    }

    /**
     * 判断是否属于集合。
     *
     * <p>共享解析器把 {@code IN (1,2,3)} 的右节点存成字符串 {@code "(1,2,3)"}，
     * 因此这里负责拆分。若集合元素本身是数组或集合，直接用。</p>
     */
    private static boolean contains(Object collection, Object value) {
        if (collection == null) {
            return false;
        }
        List<Object> items = new ArrayList<>();
        if (collection instanceof Iterable<?> iterable) {
            iterable.forEach(items::add);
        } else if (collection instanceof Object[] array) {
            for (Object item : array) {
                items.add(item);
            }
        } else {
            for (String part : splitTopLevel(String.valueOf(collection))) {
                items.add(literal(part));
            }
        }
        for (Object item : items) {
            if (equalsValue(value, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否落在闭区间内。
     *
     * <p>{@code a BETWEEN x AND y} 解析出的右子树是 {@code COMPARE(AND)}，
     * 两个边界是它的 left 与 right，这里直接从结构取，不做字符串拆分。</p>
     */
    private boolean between(BTreeNode node, RuleContext context) {
        Object value = evaluate(node.getLeft(), context);
        BTreeNode bounds = node.getRight();
        if (value == null || bounds == null) {
            return false;
        }
        Object low = evaluate(bounds.getLeft(), context);
        Object high = evaluate(bounds.getRight(), context);
        if (low == null || high == null) {
            return false;
        }
        return compare(value, low) >= 0 && compare(value, high) <= 0;
    }

    /**
     * 判断是否匹配 LIKE 模式。
     *
     * <p>只支持 {@code %} 通配，不支持 {@code _}：规则配置里的单字符通配
     * 极少用到，而把它实现错的风险高于收益。</p>
     */
    private static boolean like(Object value, Object pattern) {
        if (value == null || pattern == null) {
            return false;
        }
        String text = String.valueOf(value);
        String regex = String.valueOf(pattern)
                .replace(".", "\\.")
                .replace("%", ".*")
                .replace("_", ".");
        return text.matches(regex);
    }

    /**
     * 按顶层逗号拆分集合字面量，忽略括号与引号内的逗号。
     *
     * <p>共享解析器把 {@code IN (1,2,3)} 整体存成字符串 {@code "(1,2,3)"}，
     * 最外层那对括号必须先剥掉：留着的话内部逗号的深度是 1 而非 0，
     * 会被当成「不在顶层」而不拆分，整串退化成单个元素。</p>
     */
    private static List<String> splitTopLevel(String raw) {
        String body = raw.trim();
        if (body.length() >= 2 && body.charAt(0) == '(' && body.charAt(body.length() - 1) == ')') {
            body = body.substring(1, body.length() - 1);
        }
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                current.append(ch);
                continue;
            }
            if (!quoted) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    continue;
                } else if (ch == ',' && depth == 0) {
                    addPart(parts, current);
                    continue;
                }
            }
            current.append(ch);
        }
        addPart(parts, current);
        return parts;
    }

    /**
     * 把缓冲区里的一段收成一个元素并清空缓冲区，空段丢弃。
     */
    private static void addPart(List<String> parts, StringBuilder buffer) {
        String part = buffer.toString().trim();
        buffer.setLength(0);
        if (!part.isEmpty()) {
            parts.add(part);
        }
    }

    /**
     * 把字面量文本转成值：去引号、识别数字与布尔。
     */
    private static Object literal(String raw) {
        String text = raw.trim();
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        if ("null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            if (text.indexOf('.') >= 0) {
                return Double.valueOf(text);
            }
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /**
     * 相等比较：数值按数值比，其余按字符串比，避免 {@code 12800L} 与
     * {@code 12800.0} 因类型不同判为不等。
     */
    private static boolean equalsValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() == ((Number) right).doubleValue();
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    /**
     * 大小比较。任一侧非数值时退回字符串比较，字符串无法比较则视为不成立。
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compare(Object left, Object right) {
        if (left == null || right == null) {
            return Integer.MIN_VALUE;
        }
        if (left instanceof Number ln && right instanceof Number rn) {
            return Double.compare(ln.doubleValue(), rn.doubleValue());
        }
        if (left instanceof Comparable comparable && left.getClass() == right.getClass()) {
            return comparable.compareTo(right);
        }
        if (left instanceof Boolean lb && right instanceof Boolean rb) {
            return Boolean.compare(lb, rb);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    /**
     * 宽松取布尔：仅 {@link Boolean#TRUE} 视为真，null 与其他类型均视为假。
     */
    private static boolean truthy(Object value) {
        return value instanceof Boolean b && b;
    }

    /**
     * 路径归一化：把 {@code a.b.c} 换成合成标识。
     *
     * <p>按引号状态扫描，引号内的一律不动，避免把字面量里的点当路径。
     * 合成名先与表达式内所有标识比对，保证不撞名。</p>
     *
     * @param text  原始文本
     * @param paths 输出参数，记录合成标识到原路径的映射
     * @return 归一化文本
     */
    private static String normalize(String text, Map<String, String> paths) {
        Set<String> taken = new HashSet<>(identifiersOf(text));
        StringBuilder out = new StringBuilder(text.length() + 16);
        int index = 0;
        boolean quoted = false;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == '\'') {
                quoted = !quoted;
                out.append(ch);
                index++;
                continue;
            }
            if (quoted || !isIdentifierStart(ch)) {
                out.append(ch);
                index++;
                continue;
            }
            int cursor = index;
            StringBuilder identifier = new StringBuilder();
            while (cursor < text.length() && isIdentifierPart(text.charAt(cursor))) {
                identifier.append(text.charAt(cursor));
                cursor++;
            }
            // 尝试吞掉 . 段，形成路径
            List<String> segments = new ArrayList<>();
            segments.add(identifier.toString());
            int probe = cursor;
            while (probe < text.length() && text.charAt(probe) == '.'
                    && probe + 1 < text.length() && isIdentifierStart(text.charAt(probe + 1))) {
                int next = probe + 1;
                StringBuilder segment = new StringBuilder();
                while (next < text.length() && isIdentifierPart(text.charAt(next))) {
                    segment.append(text.charAt(next));
                    next++;
                }
                segments.add(segment.toString());
                probe = next;
            }
            if (segments.size() == 1) {
                out.append(identifier);
            } else {
                String path = String.join(".", segments);
                int seq = paths.size();
                String synthetic;
                do {
                    synthetic = SYNTHETIC_PREFIX + seq++;
                } while (taken.contains(synthetic));
                taken.add(synthetic);
                paths.put(synthetic, path);
                out.append(synthetic);
            }
            index = probe;
        }
        return out.toString();
    }

    /**
     * 收集文本中出现的全部标识（忽略引号内容），用于合成名防撞。
     */
    private static Set<String> identifiersOf(String text) {
        Set<String> found = new LinkedHashSet<>();
        boolean quoted = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (isIdentifierPart(ch)) {
                current.append(ch);
            } else if (current.length() > 0) {
                found.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            found.add(current.toString());
        }
        return found;
    }

    /**
     * 是否为标识首字符。
     */
    private static boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    /**
     * 是否为标识后续字符。
     */
    private static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
