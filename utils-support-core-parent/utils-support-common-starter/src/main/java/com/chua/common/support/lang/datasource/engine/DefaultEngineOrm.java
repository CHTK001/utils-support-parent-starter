package com.chua.common.support.lang.datasource.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link EngineOrm} 默认实现 — 把 {@link BTreeNode} 表达式树翻译为 {@link Condition} 条件树。
 *
 * <p>翻译规则（{@link BTreeNode.Type} → {@link Condition}）：</p>
 * <table border="1">
 *   <caption>节点映射</caption>
 *   <tr><th>BTreeNode</th><th>Condition</th></tr>
 *   <tr><td>{@code LOGIC(AND)}</td><td>{@link Condition#and(List)} 嵌套</td></tr>
 *   <tr><td>{@code LOGIC(OR)}</td><td>{@link Condition#or(List)} 嵌套</td></tr>
 *   <tr><td>{@code NOT}</td><td>德摩根律下推，见 {@link #negate(BTreeNode)}</td></tr>
 *   <tr><td>{@code COMPARE}</td><td>{@link Condition#of(String, String, Object)} 叶子</td></tr>
 * </table>
 *
 * <h3>NOT 的处理</h3>
 * <p>{@code Condition} 只有 AND / OR 两种嵌套，没有取反形态，因此 NOT 必须消解掉。
 * 本实现用德摩根律把取反下推到叶子算子：
 * <ul>
 *   <li>{@code NOT (A AND B)} → {@code NOT A OR NOT B}</li>
 *   <li>{@code NOT (A OR B)} → {@code NOT A AND NOT B}</li>
 *   <li>叶子取反走算子反查表（{@code = → !=}、{@code > → <=}、
 *       {@code LIKE → NOT LIKE}、{@code IN → NOT IN}、{@code IS NULL → IS NOT NULL}）</li>
 *   <li>{@code NOT BETWEEN} 没有对应算子，展开为 {@code (col < low OR col > high)}——
 *       {@code renderCondition} 的 BETWEEN 分支只渲染 {@code BETWEEN ? AND ?}，
 *       直接塞 {@code NOT BETWEEN} 会生成非法 SQL</li>
 * </ul>
 *
 * <h3>两处刻意的收窄</h3>
 * <ul>
 *   <li><b>列名走白名单</b>：表达式文本是不可信输入，{@code COLUMN} 节点的算子
 *       必须过 {@link SqlName#checkSimple} 才允许拼进 WHERE。路径形式的列名
 *       （如 Cypher 的 {@code n.age}）在此被拒绝——{@code Condition} 渲染的是
 *       裸列名，带限定前缀的语义由各引擎的表别名机制负责。</li>
 *   <li><b>算子走白名单</b>：只映射 {@link #LEAF_OPERATORS} 列出的算子。
 *       {@code CONTAINS}、{@code =~} 这类图/搜索方言算子若原样透传，
 *       会走 {@code renderCondition} 的 default 分支生成 {@code col CONTAINS ?}，
 *       在 SQL 里是非法语法。宁可显式失败，也不产出运行期才炸的语句。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultEngineOrm implements EngineOrm {

    /**
     * 等于
     */
    private static final String OP_EQ = "=";

    /**
     * 不等于
     */
    private static final String OP_NE = "!=";

    /**
     * 大于
     */
    private static final String OP_GT = ">";

    /**
     * 小于
     */
    private static final String OP_LT = "<";

    /**
     * 大于等于
     */
    private static final String OP_GTE = ">=";

    /**
     * 小于等于
     */
    private static final String OP_LTE = "<=";

    /**
     * 模糊匹配
     */
    private static final String OP_LIKE = "LIKE";

    /**
     * 取反后的模糊匹配
     */
    private static final String OP_NOT_LIKE = "NOT LIKE";

    /**
     * 属于集合
     */
    private static final String OP_IN = "IN";

    /**
     * 不属于集合
     */
    private static final String OP_NOT_IN = "NOT IN";

    /**
     * 闭区间
     */
    private static final String OP_BETWEEN = "BETWEEN";

    /**
     * 取反闭区间
     */
    private static final String OP_NOT_BETWEEN = "NOT BETWEEN";

    /**
     * 为空
     */
    private static final String OP_IS_NULL = "IS NULL";

    /**
     * 不为空
     */
    private static final String OP_IS_NOT_NULL = "IS NOT NULL";

    /**
     * 比较算子白名单：表达式算子 → ORM 条件算子。
     *
     * <p>键为 {@code DefaultExpressionParser} 归一化后的形态（{@code ==} 与 {@code =}
     * 在解析期已合并为 {@code =}，这里保留 {@code ==} 只是防御）。</p>
     */
    private static final Map<String, String> LEAF_OPERATORS = Map.ofEntries(
            Map.entry(OP_EQ, OP_EQ),
            Map.entry("==", OP_EQ),
            Map.entry(OP_NE, OP_NE),
            Map.entry("<>", OP_NE),
            Map.entry(OP_GT, OP_GT),
            Map.entry(OP_LT, OP_LT),
            Map.entry(OP_GTE, OP_GTE),
            Map.entry(OP_LTE, OP_LTE),
            Map.entry(OP_LIKE, OP_LIKE),
            Map.entry(OP_NOT_LIKE, OP_NOT_LIKE),
            Map.entry(OP_IN, OP_IN),
            Map.entry(OP_NOT_IN, OP_NOT_IN),
            Map.entry(OP_BETWEEN, OP_BETWEEN),
            Map.entry(OP_NOT_BETWEEN, OP_NOT_BETWEEN),
            Map.entry(OP_IS_NULL, OP_IS_NULL),
            Map.entry(OP_IS_NOT_NULL, OP_IS_NOT_NULL));

    /**
     * 算子反查表：用于把取反下推到叶子。
     *
     * <p>不收录 {@code BETWEEN} / {@code NOT BETWEEN}：二者都要展开成
     * {@code (col < low OR col > high)}，无法用单一算子表达。</p>
     */
    private static final Map<String, String> INVERSE_OPERATORS = Map.ofEntries(
            Map.entry(OP_EQ, OP_NE),
            Map.entry("==", OP_EQ),
            Map.entry(OP_NE, OP_EQ),
            Map.entry("<>", OP_EQ),
            Map.entry(OP_GT, OP_LTE),
            Map.entry(OP_LT, OP_GTE),
            Map.entry(OP_GTE, OP_LT),
            Map.entry(OP_LTE, OP_GT),
            Map.entry(OP_LIKE, OP_NOT_LIKE),
            Map.entry(OP_IN, OP_NOT_IN),
            Map.entry(OP_IS_NULL, OP_IS_NOT_NULL));

    @Override
    public Condition parse(String expression, ExpressionParser parser) {
        if (parser == null) {
            throw new UnsupportedOperationException("表达式解析器不能为空");
        }
        List<Condition> conditions = translate(parser.parse(expression));
        // 顶层只会因 NOT(OR) 展开出多个条件；德摩根律保证它们是 AND 关系
        return conditions.size() == 1 ? conditions.getFirst() : Condition.and(conditions);
    }

    /**
     * 把表达式节点翻译为条件列表。
     *
     * <p>返回列表而非单个条件，是因为取反展开后一个节点可能对应多个条件。
     * 非取反路径恒返回单元素列表。</p>
     *
     * @param node 表达式节点，不允许为 null
     * @return 条件列表（非空）
     */
    private List<Condition> translate(BTreeNode node) {
        if (node == null) {
            throw new IllegalArgumentException("表达式解析结果为空");
        }
        return switch (node.getType()) {
            case LOGIC -> group(node);
            case NOT -> negate(node.getRight());
            case COMPARE -> List.of(comparison(node));
            default -> throw new IllegalArgumentException(
                    "表达式节点 " + node.getType() + " 不能独立作为查询条件: " + node);
        };
    }

    /**
     * 把 AND / OR 节点翻译为嵌套条件。
     *
     * @param node 逻辑节点，左右子树均已翻译
     * @return 条件列表
     */
    private List<Condition> group(BTreeNode node) {
        List<Condition> merged = Wraps.list();
        merged.addAll(translate(node.getLeft()));
        merged.addAll(translate(node.getRight()));
        if (merged.size() == 1) {
            return merged;
        }
        return List.of(node.isOr() ? Condition.or(merged) : Condition.and(merged));
    }

    /**
     * 把 NOT 节点消解为不含取反的条件列表。
     *
     * @param node 被取反的节点（{@code NOT} 节点的右子树）
     * @return 条件列表
     */
    private List<Condition> negate(BTreeNode node) {
        if (node == null) {
            throw new IllegalArgumentException("NOT 节点缺少右子树，表达式不完整");
        }
        return switch (node.getType()) {
            case NOT -> translate(node.getRight());
            case LOGIC -> {
                List<Condition> merged = Wraps.list();
                merged.addAll(negate(node.getLeft()));
                merged.addAll(negate(node.getRight()));
                // 德摩根律：NOT(A AND B) = NOT A OR NOT B；NOT(A OR B) = NOT A AND NOT B
                yield List.of(node.isAnd() ? Condition.or(merged) : Condition.and(merged));
            }
            case COMPARE -> negatedComparison(node);
            default -> throw new IllegalArgumentException(
                    "无法对 " + node.getType() + " 节点取反: " + node);
        };
    }

    /**
     * 把比较节点翻译为叶子条件。
     *
     * @param node 比较节点
     * @return 叶子条件
     */
    private Condition comparison(BTreeNode node) {
        String source = normalize(node.getOperator());
        String operator = LEAF_OPERATORS.get(source);
        if (operator == null) {
            throw unsupportedOperator(source, node);
        }
        String column = columnName(node.getLeft());
        return Condition.of(column, operator, operand(node, operator));
    }

    /**
     * 把比较节点的取反形态翻译为条件列表。
     *
     * <p>{@code BETWEEN} 家族不走算子反查——展开为区间外的并集。</p>
     *
     * @param node 比较节点
     * @return 条件列表
     */
    private List<Condition> negatedComparison(BTreeNode node) {
        String source = normalize(node.getOperator());
        if (OP_BETWEEN.equals(source) || OP_NOT_BETWEEN.equals(source)) {
            return List.of(betweenExclusion(node));
        }
        String inverse = INVERSE_OPERATORS.get(source);
        if (inverse == null) {
            throw unsupportedOperator(source, node);
        }
        String operator = LEAF_OPERATORS.get(inverse);
        return List.of(Condition.of(columnName(node.getLeft()), operator, operand(node, operator)));
    }

    /**
     * 把 {@code NOT (col BETWEEN low AND high)} 展开为 {@code (col < low OR col > high)}。
     *
     * @param node BETWEEN 节点，右子树是承载两个边界的 COMPARE(AND)
     * @return 区间外条件
     */
    private Condition betweenExclusion(BTreeNode node) {
        BTreeNode bounds = node.getRight();
        if (bounds == null || bounds.getLeft() == null || bounds.getRight() == null) {
            throw new IllegalArgumentException("BETWEEN 表达式缺少边界: " + node);
        }
        String column = columnName(node.getLeft());
        List<Condition> outside = Wraps.list();
        outside.add(Condition.of(column, OP_LT, literal(bounds.getLeft())));
        outside.add(Condition.of(column, OP_GT, literal(bounds.getRight())));
        return Condition.or(outside);
    }

    /**
     * 按目标算子取出条件右值。
     *
     * @param node     比较节点
     * @param operator 目标算子，决定右值的形态
     * @return 绑定值
     */
    private Object operand(BTreeNode node, String operator) {
        return switch (operator) {
            case OP_IS_NULL, OP_IS_NOT_NULL -> null;
            case OP_IN, OP_NOT_IN -> inValues(node.getRight());
            case OP_BETWEEN, OP_NOT_BETWEEN -> betweenBounds(node.getRight());
            default -> literal(node.getRight());
        };
    }

    /**
     * 取出 {@code IN} 列表的候选值集合。
     *
     * <p>共享解析器把 {@code IN (1, 2, 3)} 整体存成字符串 {@code "(1,2,3)"}，
     * 而 {@code renderCondition} 对 {@code IN} 强转 {@code Collection}——
     * 不拆分会直接 ClassCastException。手工构造的树若直接传集合则原样使用。</p>
     *
     * @param right 右子节点
     * @return 候选值集合（非空）
     */
    private Object inValues(BTreeNode right) {
        Object raw = literal(right);
        if (raw instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (raw instanceof Object[] array) {
            return new ArrayList<>(Arrays.asList(array));
        }
        if (raw instanceof String text) {
            return splitList(text);
        }
        throw new IllegalArgumentException("IN 列表需要集合或字面量列表，实际为: " + raw);
    }

    /**
     * 按顶层逗号拆分集合字面量，忽略括号与引号内的逗号。
     *
     * <p>最外层括号（{@code (...)} 或 {@code [...]}）必须先剥掉：留着的话
     * 内部逗号的深度是 1 而非 0，会被当成"不在顶层"而不拆分，整串退化成单个元素。</p>
     *
     * @param text 原始列表文本
     * @return 候选值列表
     */
    private List<Object> splitList(String text) {
        String body = text.trim();
        if (body.length() >= 2) {
            char open = body.charAt(0);
            char close = body.charAt(body.length() - 1);
            if ((open == '(' && close == ')') || (open == '[' && close == ']')) {
                body = body.substring(1, body.length() - 1);
            }
        }
        List<Object> values = Wraps.list();
        int depth = 0;
        boolean quoted = false;
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                buffer.append(ch);
                continue;
            }
            if (!quoted) {
                if (ch == '(' || ch == '[') {
                    depth++;
                } else if (ch == ')' || ch == ']') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    appendValue(values, buffer);
                    continue;
                }
            }
            buffer.append(ch);
        }
        appendValue(values, buffer);
        return values;
    }

    /**
     * 把缓冲区中的一段收成一个值并清空缓冲区，空段丢弃。
     *
     * @param values 结果列表
     * @param buffer 缓冲区
     */
    private static void appendValue(List<Object> values, StringBuilder buffer) {
        String part = buffer.toString().trim();
        buffer.setLength(0);
        if (!part.isEmpty()) {
            values.add(literal(part));
        }
    }

    /**
     * 把字面量文本转成绑定值：去引号、识别数字与布尔。
     *
     * <p><b>定型规则必须与解析器一致</b>：{@code DefaultExpressionParser} 对整数字面量
     * 产出 {@link Integer}、对带小数点的产出 {@link Double}。本方法用于处理 {@code IN} 列表
     * 这类仍以原始文本存在的节点，若产出 {@link Long}，同一棵条件树里就会同时存在
     * {@code Integer} 与 {@code Long} 两种数值类型——对 JDBC 绑定无害，但条件树的结构化
     * 比较、去重与缓存都会失效。超出 {@code int} 范围的整数字面量降级为 {@link Long}，
     * 避免直接沿用解析器的 {@code Integer.parseInt} 抛异常。</p>
     *
     * @param text 字面量文本
     * @return 绑定值
     */
    private static Object literal(String text) {
        String trimmed = text.trim();
        if (trimmed.length() >= 2
                && trimmed.charAt(0) == '\''
                && trimmed.charAt(trimmed.length() - 1) == '\'') {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if ("TRUE".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("FALSE".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        if ("NULL".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            if (trimmed.indexOf('.') >= 0) {
                return Double.valueOf(trimmed);
            }
            long value = Long.parseLong(trimmed);
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                    ? (Object) (int) value
                    : (Object) value;
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

    /**
     * 取出 {@code BETWEEN} 的两个边界。
     *
     * <p>{@code renderCondition} 对 {@code BETWEEN} 强转 {@code Object[]}，
     * 必须保持该形态，否则渲染期 ClassCastException。</p>
     *
     * @param right 右子节点，结构为 COMPARE(AND)
     * @return {@code {low, high}}
     */
    private Object betweenBounds(BTreeNode right) {
        if (right == null || right.getLeft() == null || right.getRight() == null) {
            throw new IllegalArgumentException("BETWEEN 表达式缺少边界");
        }
        return new Object[]{literal(right.getLeft()), literal(right.getRight())};
    }

    /**
     * 取出右节点的常量值。
     *
     * @param node 右子节点
     * @return 绑定值
     * @throws IllegalArgumentException 右节点不是常量时抛出
     */
    private static Object literal(BTreeNode node) {
        if (node == null) {
            return null;
        }
        if (node.getType() != BTreeNode.Type.VALUE) {
            throw new IllegalArgumentException(
                    "条件右值必须是常量，当前为 " + node.getType() + ": " + node);
        }
        return node.getValue();
    }

    /**
     * 取出左节点的列名并校验。
     *
     * @param node 左子节点
     * @return 通过白名单校验的列名
     * @throws IllegalArgumentException 左节点不是列引用，或列名不满足白名单时抛出
     */
    private static String columnName(BTreeNode node) {
        if (node == null || node.getType() != BTreeNode.Type.COLUMN) {
            throw new IllegalArgumentException(
                    "条件左值必须是列引用，当前为: " + (node == null ? "null" : node.getType()));
        }
        return SqlName.checkSimple(node.getOperator(), "条件列名");
    }

    /**
     * 归一化算子：去空白、转大写。
     *
     * @param operator 原始算子
     * @return 归一化算子，可能为 null
     */
    private static String normalize(String operator) {
        return operator == null ? null : operator.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 构造不支持算子的异常。
     *
     * @param operator 算子
     * @param node     所在节点
     * @return 异常实例
     */
    private static IllegalArgumentException unsupportedOperator(String operator, BTreeNode node) {
        return new IllegalArgumentException("ORM 不支持该算子: " + operator
                + "，支持: " + LEAF_OPERATORS.values()
                + "；表达式: " + node);
    }
}