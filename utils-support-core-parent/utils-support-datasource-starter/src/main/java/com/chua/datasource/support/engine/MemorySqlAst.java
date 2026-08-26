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

    /* ==================== 共享 DML 执行器 ==================== */

    /**
     * DML 统一执行入口：绑定参数后对目标表行引用应用变更。
     *
     * @param plan          解析得到的 DML 计划
     * @param params        ? 绑定参数
     * @param tableResolver 表行引用解析器（惰性调用一次；内存引擎可在此建空表）
     * @return 影响行数
     */
    public static int executeDml(DmlPlan plan, List<Object> params, java.util.function.Supplier<List<Object>> tableResolver) {
        java.util.Objects.requireNonNull(plan, "plan must not be null");
        List<Object> safeParams = params == null ? List.of() : params;
        bindPlanParams(plan, rowProvider(safeParams));
        List<Object> rows = tableResolver.get();
        if (plan instanceof InsertPlan ins) {
            return applyInsert(ins, rows);
        }
        if (plan instanceof UpdatePlan upd) {
            return applyUpdate(upd, rows, safeParams);
        }
        return applyDelete((DeletePlan) plan, rows, safeParams);
    }


    /**
     * 将解析期收集的 INSERT / UPDATE SET 参数占位按序绑定。
     *
     * @param plan     DML 计划
     * @param provider 共享参数游标
     */
    public static void bindPlanParams(DmlPlan plan, ParamProvider provider) {
        if (plan instanceof InsertPlan ins) {
            for (List<Object> row : ins.rows()) {
                row.replaceAll(v -> v instanceof ParamMarker ? provider.next() : v);
            }
        } else if (plan instanceof UpdatePlan upd) {
            upd.sets().replaceAll((k, v) -> v instanceof ParamMarker ? provider.next() : v);
        }
    }

    /**
     * 构造独立的按序参数游标（供单行 WHERE 求值使用）。
     *
     * @param params 绑定参数
     * @return 游标提供器
     */
    public static ParamProvider rowProvider(List<Object> params) {
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        return () -> idx.get() < params.size() ? params.get(idx.getAndIncrement()) : null;
    }

    /**
     * 对行引用列表应用 INSERT 计划。
     *
     * @param plan 插入计划
     * @param rows 目标行引用
     * @return 影响行数
     */
    public static int applyInsert(InsertPlan plan, List<Object> rows) {
        for (List<Object> values : plan.rows()) {
            LinkedHashMap<String, Object> rowMap = new LinkedHashMap<>();
            List<String> cols = !plan.columns().isEmpty() ? plan.columns()
                    : (rows.isEmpty()
                            ? List.of()
                            : new ArrayList<>(RowAccessor.allColumns(rows.get(0)).keySet()));
            if (cols.isEmpty()) {
                throw new IllegalStateException("无法推断插入列，请显式指定列清单");
            }
            if (cols.size() != values.size()) {
                throw new IllegalArgumentException(
                        "列数与值数不匹配: " + cols.size() + " vs " + values.size());
            }
            for (int i = 0; i < values.size(); i++) {
                rowMap.put(cols.get(i), values.get(i));
            }
            rows.add(rowMap);
        }
        return plan.rows().size();
    }

    /**
     * 对行引用列表应用 UPDATE 计划。
     *
     * @param plan   更新计划
     * @param rows   目标行引用
     * @param params 绑定参数（WHERE 占位逐行重置消费）
     * @return 影响行数
     */
    public static int applyUpdate(UpdatePlan plan, List<Object> rows, List<Object> params) {
        int affected = 0;
        for (Object row : rows) {
            /* 每行重置参数游标：绑定值不随行变化 */
            if (plan.where() != null && !plan.where().eval(row, rowProvider(params))) {
                continue;
            }
            boolean touched = false;
            for (Map.Entry<String, Object> e : plan.sets().entrySet()) {
                touched |= RowAccessor.setValue(row, e.getKey(), e.getValue());
            }
            if (touched) {
                affected++;
            }
        }
        return affected;
    }

    /**
     * 对行引用列表应用 DELETE 计划。
     *
     * @param plan   删除计划
     * @param rows   目标行引用
     * @param params 绑定参数
     * @return 影响行数
     */
    public static int applyDelete(DeletePlan plan, List<Object> rows, List<Object> params) {
        int before = rows.size();
        rows.removeIf(row -> plan.where() == null || plan.where().eval(row, rowProvider(params)));
        return before - rows.size();
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
