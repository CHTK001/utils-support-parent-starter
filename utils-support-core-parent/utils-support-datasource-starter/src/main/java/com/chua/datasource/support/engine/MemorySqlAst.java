package com.chua.datasource.support.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.chua.datasource.support.engine.MemorySqlLex.RowAccessor;

/**
 * SQL AST 节点与执行计划定义。
 * <p>表达式以二叉树组织：逻辑节点（AND/OR/NOT）为分支，
 * 比较节点（=/LIKE/IN/BETWEEN 等）为叶子谓词，求值自顶向下递归。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class MemorySqlAst {

    private MemorySqlAst() {
    }

    /* ==================== 表达式节点 ==================== */

    /** 表达式基类 */
    abstract static class Node {

        /**
         * 对行求值。
         *
         * @param row 行对象（Map 或 bean）
         * @param p   参数提供器
         * @return 布真
         */
        abstract boolean eval(Object row, ParamProvider p);
    }

    /** 二元逻辑/比较节点 */
    static final class BinaryNode extends Node {
        private final String op;
        private final Node left;
        private final Node right;

        BinaryNode(String op, Node left, Node right) {
            this.op = op.toUpperCase(Locale.ROOT);
            this.left = left;
            this.right = right;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            switch (op) {
                case "AND":
                    return left.eval(row, p) && right.eval(row, p);
                case "OR":
                    return left.eval(row, p) || right.eval(row, p);
                default:
                    Object l = value(left, row, p);
                    Object r = value(right, row, p);
                    if (l == null || r == null) {
                        return false;
                    }
                    int cmp = compare(l, r);
                    switch (op) {
                        case "=":
                            return cmp == 0;
                        case "!=":
                        case "<>":
                            return cmp != 0;
                        case "<":
                            return cmp < 0;
                        case "<=":
                            return cmp <= 0;
                        case ">":
                            return cmp > 0;
                        case ">=":
                            return cmp >= 0;
                        default:
                            throw new IllegalArgumentException("未知运算符: " + op);
                    }
            }
        }
    }

    /** NOT 节点 */
    static final class NotNode extends Node {
        private final Node child;

        NotNode(Node child) {
            this.child = child;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            return !child.eval(row, p);
        }
    }

    /** 列引用 */
    static final class ColumnNode extends Node {
        private final String name;

        ColumnNode(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            throw new UnsupportedOperationException("列不能单独作为谓词");
        }
    }

    /** 字面量 */
    static final class LiteralNode extends Node {
        private final Object value;

        LiteralNode(Object value) {
            this.value = value;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            return Boolean.TRUE.equals(value);
        }

        Object value() {
            return value;
        }

        static Object unwrap(Object v, ParamProvider p) {
            if (v instanceof ParamMarker) {
                return p == null ? null : p.next();
            }
            if (v instanceof LiteralNode) {
                return unwrap(((LiteralNode) v).value, p);
            }
            return v;
        }
    }

    /** IS [NOT] NULL */
    static final class IsNullNode extends Node {
        private final ColumnNode col;
        private final boolean notNull;

        IsNullNode(ColumnNode col, boolean notNull) {
            this.col = col;
            this.notNull = notNull;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            boolean isNull = RowAccessor.value(row, col.name()) == null;
            return notNull ? !isNull : isNull;
        }
    }

    /** BETWEEN a AND b（闭区间） */
    static final class BetweenNode extends Node {
        private final ColumnNode col;
        private final Object lo;
        private final Object hi;

        BetweenNode(ColumnNode col, Object lo, Object hi) {
            this.col = col;
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            Object v = RowAccessor.value(row, col.name());
            if (v == null) {
                return false;
            }
            return compare(v, LiteralNode.unwrap(lo, p)) >= 0
                    && compare(v, LiteralNode.unwrap(hi, p)) <= 0;
        }
    }

    /** IN 列表 */
    static final class InNode extends Node {
        private final ColumnNode col;
        private final List<Object> values;

        InNode(ColumnNode col, List<Object> values) {
            this.col = col;
            this.values = values;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            Object v = RowAccessor.value(row, col.name());
            if (v == null) {
                return false;
            }
            for (Object candidate : values) {
                Object resolved = LiteralNode.unwrap(candidate, p);
                if (compare(v, resolved) == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** LIKE，仅支持 % 通配（前缀 / 后缀 / 包含） */
    static final class LikeNode extends Node {
        private final ColumnNode col;
        private final String pattern;

        LikeNode(ColumnNode col, String pattern) {
            this.col = col;
            this.pattern = pattern;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            Object v = RowAccessor.value(row, col.name());
            if (v == null) {
                return false;
            }
            String s = String.valueOf(v);
            String pat = pattern;
            if (pat.startsWith("%") && pat.endsWith("%")) {
                return s.contains(pat.substring(1, pat.length() - 1));
            }
            if (pat.startsWith("%")) {
                return s.endsWith(pat.substring(1));
            }
            if (pat.endsWith("%")) {
                return s.startsWith(pat.substring(0, pat.length() - 1));
            }
            return s.equals(pat);
        }
    }

    /* ==================== SELECT 语句 ==================== */

    /** ORDER BY 项 */
    static final class OrderItem {
        final String column;
        final boolean desc;

        OrderItem(String column, boolean desc) {
            this.column = column;
            this.desc = desc;
        }
    }

    /** 参数占位标记 */
    static final class ParamMarker {
    }

    /** 参数绑定提供器 */
    interface ParamProvider {
        Object next();
    }

    /** SELECT 语句：FROM 表行引用 + WHERE 树 + 投影/排序/截断 */
    static final class SelectStmt {
        boolean countStar;
        boolean selectAll;
        final List<String> selectColumns = new ArrayList<>();
        String table;
        Node where;
        final List<OrderItem> orderBys = new ArrayList<>();
        int limit = Integer.MAX_VALUE;
        int offset;
        private List<Object> boundParams;

        void bind(List<Object> params) {
            this.boundParams = params == null ? List.of() : params;
        }

        ParamProvider provider() {
            return new ParamProvider() {
                private int idx;

                @Override
                public Object next() {
                    return idx < boundParams.size() ? boundParams.get(idx++) : null;
                }
            };
        }

        /**
         * 在行引用列表上执行管道。
         *
         * @param rows 行引用
         * @return 结果行
         */
        public List<Map<String, Object>> evaluate(List<?> rows) {
            List<?> filtered = rows;
            if (where != null) {
                List<Object> out = new ArrayList<>();
                for (Object r : rows) {
                    /* 参数绑定值不随行变化：每行求值需重置参数游标 */
                    if (where.eval(r, provider())) {
                        out.add(r);
                    }
                }
                filtered = out;
            }
            if (!orderBys.isEmpty()) {
                Comparator<Object> cmp = buildComparator();
                List<Object> sorted = new ArrayList<>(filtered);
                sorted.sort(cmp);
                filtered = sorted;
            }
            if (countStar) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cnt", filtered.size());
                return List.of(m);
            }
            List<Map<String, Object>> projected = new ArrayList<>();
            int fromIdx = Math.min(offset, filtered.size());
            int toIdx = Math.min(offset + limit, filtered.size());
            for (Object r : filtered.subList(fromIdx, toIdx)) {
                projected.add(project(r));
            }
            return projected;
        }

        private Comparator<Object> buildComparator() {
            return (a, b) -> {
                for (OrderItem ob : orderBys) {
                    Object va = RowAccessor.value(a, ob.column);
                    Object vb = RowAccessor.value(b, ob.column);
                    int c;
                    if (va == null && vb == null) {
                        c = 0;
                    } else if (va == null) {
                        c = -1;
                    } else if (vb == null) {
                        c = 1;
                    } else {
                        c = compare(va, vb);
                    }
                    if (c != 0) {
                        return ob.desc ? -c : c;
                    }
                }
                return 0;
            };
        }

        private Map<String, Object> project(Object row) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (selectAll) {
                for (Map.Entry<String, Object> e : RowAccessor.allColumns(row).entrySet()) {
                    out.put(e.getKey(), e.getValue());
                }
                return out;
            }
            for (String col : selectColumns) {
                out.put(col, RowAccessor.value(row, col));
            }
            return out;
        }
    }

    /* ==================== DML 计划 ==================== */

    /** DML 基类 */
    abstract static class DmlPlan {
        String table;
        Node where;

        /**
         * 表名。
         *
         * @return 目标表
         */
        public String table() {
            return table;
        }

        /**
         * 匹配谓词。
         *
         * @return WHERE 树或 null
         */
        public Node where() {
            return where;
        }
    }

    /** INSERT 计划 */
    static final class InsertPlan extends DmlPlan {
        final List<String> columns = new ArrayList<>();
        final List<List<Object>> rows = new ArrayList<>();

        /**
         * 列清单。
         *
         * @return 列名列表
         */
        public List<String> columns() {
            return columns;
        }

        /**
         * 待插入值行。
         *
         * @return 值行集合
         */
        public List<List<Object>> rows() {
            return rows;
        }
    }

    /** UPDATE 计划 */
    static final class UpdatePlan extends DmlPlan {
        final Map<String, Object> sets = new LinkedHashMap<>();

        /**
         * SET 赋值。
         *
         * @return 列到值的映射
         */
        public Map<String, Object> sets() {
            return sets;
        }
    }

    /** DELETE 计划 */
    static final class DeletePlan extends DmlPlan {
    }

    /* ==================== 工具 ==================== */

    static Object value(Node n, Object row, ParamProvider p) {
        if (n instanceof ColumnNode) {
            return RowAccessor.value(row, ((ColumnNode) n).name());
        }
        if (n instanceof LiteralNode) {
            return LiteralNode.unwrap(((LiteralNode) n).value, p);
        }
        throw new IllegalArgumentException("无法作为值求值: " + n.getClass().getSimpleName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            double da = ((Number) a).doubleValue();
            double db = ((Number) b).doubleValue();
            return Double.compare(da, db);
        }
        if (a instanceof Comparable && b instanceof Comparable) {
            try {
                return ((Comparable) a).compareTo(b);
            } catch (ClassCastException e) {
                return String.valueOf(a).compareTo(String.valueOf(b));
            }
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
