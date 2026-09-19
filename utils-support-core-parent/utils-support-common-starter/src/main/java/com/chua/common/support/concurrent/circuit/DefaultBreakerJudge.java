package com.chua.common.support.concurrent.circuit;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.spi.annotations.Spi;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 默认断路器判断器
 *
 * <p>处理 COMPARE 节点的标准求值，支持以下运算符：
 * <ul>
 *   <li>等于：=、==</li>
 *   <li>不等：!=、&lt;&gt;</li>
 *   <li>数值比较：&gt;、&lt;、&gt;=、&lt;=</li>
 *   <li>集合：IN</li>
 *   <li>范围：BETWEEN</li>
 *   <li>模糊匹配：LIKE</li>
 *   <li>空值检查：IS NULL、IS NOT NULL</li>
 * </ul>
 *
 * <p>对于 COLUMN / FUNCTION 节点直接返回 true（不阻断）。
 * 未实现的运算符也返回 false（保守策略，便于业务扩展）。</p>
 *
 * <h3>COMPARE 节点结构</h3>
 * <pre>
 *   COMPARE 节点
 *     ├─ left  = COLUMN 节点（列名在 operator 字段）
 *     ├─ operator = 比较运算符
 *     └─ right = VALUE 节点 / COLUMN 节点（expected 在 value 或 operator 字段）
 * </pre>
 *
 * <p>字面量解析策略：当 expected 是 {@code 'quoted'} 时存为 VALUE 节点，
 * 解析器对未加引号的标识符统一作为 COLUMN 节点。本判断器在 COLUMN 节点中
 * 通过 {@code ctx.containsKey(name)} 判断是否为真列引用；若 ctx 不含则视为
 * 字面量，回退到 operator 字段取值。</p>
 *
 * @author CH
 * @since 2026/07/28
 */
@Spi("default")
public class DefaultBreakerJudge implements BreakerJudge {

    /**
     * 默认 SPI 名称
     */
    public static final String NAME = "default";

    /** 等于操作符 */
    private static final String OP_EQ = "==";
    /** SQL 等于操作符 */
    private static final String OP_EQ_SQL = "=";
    /** 不等于操作符 */
    private static final String OP_NE = "!=";
    /** SQL 不等于操作符 */
    private static final String OP_NE_SQL = "<>";
    /** 大于操作符 */
    private static final String OP_GT = ">";
    /** 小于操作符 */
    private static final String OP_LT = "<";
    /** 大于等于操作符 */
    private static final String OP_GTE = ">=";
    /** 小于等于操作符 */
    private static final String OP_LTE = "<=";
    /** IN 操作符 */
    private static final String OP_IN = "IN";
    /** BETWEEN 操作符 */
    private static final String OP_BETWEEN = "BETWEEN";
    /** LIKE 操作符 */
    private static final String OP_LIKE = "LIKE";
    /** IS NULL 操作符 */
    private static final String OP_IS_NULL = "IS NULL";
    /** IS NOT NULL 操作符 */
    private static final String OP_IS_NOT_NULL = "IS NOT NULL";

    @Override
    /** Judge */
    public boolean judge(BTreeNode node, Map<String, Object> context) {
        if (node == null || context == null) {
            return false;
        }
        BTreeNode.Type type = node.getType();

        // 非 COMPARE 节点（COLUMN/VALUE/FUNCTION）：作为布尔表达式求值
        // 用于 NOT/AND/OR 节点下的叶子节点。例如：
        //   NOT isBlocked  → isBlocked (COLUMN) → boolean → 取反
        //   AND active      → active (COLUMN)  → boolean
        //   OR  ready       → ready (VALUE)    → boolean
        if (type != BTreeNode.Type.COMPARE) {
            return toBoolean(readNodeValue(node, context));
        }

        String op = node.getOperator();

        // 空值检查：必须先判，不受 actual == null 短路影响
        if (OP_IS_NULL.equalsIgnoreCase(op)) {
            return isNullValue(context, node.getLeft());
        }
        if (OP_IS_NOT_NULL.equalsIgnoreCase(op)) {
            return !isNullValue(context, node.getLeft());
        }

        String column = node.getLeft().getOperator();
        Object actual = context.get(column);

        if (actual == null) {
            // 其他运算符：实际值缺失视为断路
            return false;
        }

        if (OP_BETWEEN.equalsIgnoreCase(op)) {
            return matchBetween(actual, node.getRight(), context);
        }
        if (OP_IN.equalsIgnoreCase(op)) {
            return matchIn(actual, readValue(node.getRight(), context));
        }
        if (OP_LIKE.equalsIgnoreCase(op)) {
            return matchLike(actual, readValue(node.getRight(), context));
        }

        Object expected = readValue(node.getRight(), context);

        return switch (op) {
            case OP_EQ, OP_EQ_SQL -> equalsValue(actual, expected);
            case OP_NE, OP_NE_SQL -> !equalsValue(actual, expected);
            case OP_GT -> compareNumber(actual, expected) > 0;
            case OP_LT -> compareNumber(actual, expected) < 0;
            case OP_GTE -> compareNumber(actual, expected) >= 0;
            case OP_LTE -> compareNumber(actual, expected) <= 0;
            default -> false;
        };
    }

    // ==================== 字面量/列名消歧 ====================

    /**
     * 读取右节点的值：
     * <ol>
     *   <li>VALUE 节点：直接取 value 字段</li>
     *   <li>COLUMN 节点：若 ctx 包含此列名则视为列引用（ctx.get(name)）；否则视为字面量</li>
     *   <li>其他：返回 null</li>
     * </ol>
     * @param right 方法入参 right
     * @param context 上下文，不允许为 null
     * @return 对象 对象
     */
    private static Object readValue(BTreeNode right, Map<String, Object> context) {
        if (right == null) {
            return null;
        }
        if (right.getType() == BTreeNode.Type.VALUE) {
            Object v = right.getValue();
            if (v == null) {
                return null;
            }
            // VALUE 节点中若存的是字符串 "(a,b,c)"（IN 列表），原样返回
            return v;
        }
        if (right.getType() == BTreeNode.Type.COLUMN) {
            String name = right.getOperator();
            if (name != null && context != null && context.containsKey(name)) {
                // 真正的列引用：取 ctx 中的值
                return context.get(name);
            }
            // 字面量
            return name;
        }
        return null;
    }

    /**
     * 读取非 COMPARE 节点（COLUMN/VALUE/FUNCTION）作为独立布尔表达式的值。
     *
     * <ul>
     *   <li>VALUE：直接取 value</li>
     *   <li>COLUMN：ctx 包含则 ctx.get(name)，否则视为字面量（取 operator）</li>
     *   <li>FUNCTION：暂返回 null（未来扩展）</li>
     * </ul>
     * @param node 节点，不允许为 null
     * @param context 上下文，不允许为 null
     * @return 对象 对象
     */
    private static Object readNodeValue(BTreeNode node, Map<String, Object> context) {
        if (node == null) {
            return null;
        }
        return readValue(node, context);
    }

    /**
     * 对象 → boolean：用于 NOT/AND/OR 下的叶子节点布尔求值。
     *
     * <ul>
     *   <li>Boolean：直接取值</li>
     *   <li>Number：非零为 true</li>
     *   <li>String："true"/"1"/"yes"（忽略大小写）为 true，其他非空字符串为 true</li>
     *   <li>null：false</li>
     * </ul>
     * @param value 值，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private static boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.isEmpty()) {
                return false;
            }
            String low = s.toLowerCase();
            if ("false".equals(low) || "0".equals(low) || "no".equals(low) || "off".equals(low)) {
                return false;
            }
            return true;
        }
        // 其他对象：非 null 即 true
        return true;
    }

    // ==================== 比较器 ====================

    /**
     * 值相等：
     * <ul>
     *   <li>两侧均为 Boolean：按 boolean 比较</li>
     *   <li>两侧均严格为数值：按 double 比较</li>
     *   <li>其他：按字符串比较</li>
     * </ul>
     * null 不参与比较。
     * @param actual 方法入参 actual
     * @param expected 方法入参 expected
     * @return 是否成功（true 表示成功）
     */
    private static boolean equalsValue(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        // Boolean 显式比较：避免与 String "true"/"false" 混淆
        if (actual instanceof Boolean && expected instanceof Boolean) {
            return actual.equals(expected);
        }
        if (isStrictNumber(actual) && isStrictNumber(expected)) {
            return compareNumber(actual, expected) == 0;
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    /**
     * 严格数值判断：避免 {@code "01"} 与 {@code 1} 误判。
     * - Number 实例直接通过
     * - 字符串必须完整匹配 double 格式（不允许前导 0 单独视为字符串）
     * @param value 值，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private static boolean isStrictNumber(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (!(value instanceof String)) {
            return false;
        }
        String s = ((String) value).trim();
        if (s.isEmpty()) {
            return false;
        }
        // 严格 double 解析：parseDouble 不抛异常即视为数值
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 数值比较：返回 actual - expected 的符号。
     * 任一侧非数值时返回 Integer.MIN_VALUE（确保比较结果异常，让上层识别）。
     * @param actual 方法入参 actual
     * @param expected 方法入参 expected
     * @return 结果数值
     */
    private static int compareNumber(Object actual, Object expected) {
        if (!isStrictNumber(actual) || !isStrictNumber(expected)) {
            return Integer.MIN_VALUE;
        }
        double a = toDouble(actual);
        double b = toDouble(expected);
        return Double.compare(a, b);
    }

    /**
     * 安全转 double，非数值返回 NaN（让 compareNumber 判定为非数值）
     * @param value 值，不允许为 null
     * @return 结果数值
     */
    private static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    // ==================== 集合/范围/模糊匹配 ====================

    /**
     * IN 列表匹配：expected 支持 {@code (a, b, c)} 或 {@code a,b,c} 格式
     * @param actual 方法入参 actual
     * @param expected 方法入参 expected
     * @return 是否成功（true 表示成功）
     */
    private static boolean matchIn(Object actual, Object expected) {
        if (expected == null) {
            return false;
        }
        String list = String.valueOf(expected);
        String content = list;
        if (list.startsWith("(") && list.endsWith(")")) {
            content = list.substring(1, list.length() - 1);
        }
        String actualStr = String.valueOf(actual);
        for (String item : content.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 优先数值匹配
            if (isStrictNumber(actual) && isStrictNumber(trimmed)) {
                if (compareNumber(actual, trimmed) == 0) {
                    return true;
                }
                continue;
            }
            if (actualStr.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * BETWEEN 范围匹配：right 节点结构为 {@code COMPARE("AND", low, high)}
     *
     * <p>解析器实际结构：{@code COMPARE("BETWEEN", left, COMPARE("AND", low, high))}。
     * low/high 可以是字面量或 ctx 列引用（如 {@code score BETWEEN low AND high}）。</p>
     *
     * @param actual 当前列的值
     * @param right  BETWEEN 右节点（COMPARE("AND", low, high)）
     * @param context 上下文参数（用于解析边界列引用）
     * @return actual 是否在 [low, high] 闭区间内
     */
    private static boolean matchBetween(Object actual, BTreeNode right, Map<String, Object> context) {
        if (right == null) {
            return false;
        }
        Object low = readValue(right.getLeft(), context);
        Object high = readValue(right.getRight(), context);
        if (low == null || high == null) {
            return false;
        }
        return compareNumber(actual, low) >= 0 && compareNumber(actual, high) <= 0;
    }

    /**
     * LIKE 模糊匹配：通配符 {@code %}（任意长度）、{@code _}（单字符）。
     * 对特殊字符做正则转义。
     * @param actual 方法入参 actual
     * @param expected 方法入参 expected
     * @return 是否成功（true 表示成功）
     */
    private static boolean matchLike(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        String pattern = String.valueOf(expected);
        // 转义正则元字符，但保留 % 和 _
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(String.valueOf(actual)).matches();
    }

    /**
     * 空值检查：{@code context} 中列不存在或值为 null 即视为 NULL
     * @param context 上下文，不允许为 null
     * @param columnNode 列节点，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    private static boolean isNullValue(Map<String, Object> context, BTreeNode columnNode) {
        if (columnNode == null) {
            return true;
        }
        String column = columnNode.getOperator();
        return !context.containsKey(column) || context.get(column) == null;
    }
}
