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
        /**
         * 对行求值。
         *
         * @param row 行对象（Map 或 bean）
         * @param p   参数游标
         * @return 布真结果
         */
        abstract boolean eval(Object row, ParamProvider p);
    }

    /** 二元逻辑/比较节点 */
    static final class BinaryNode extends Node {
        private final String op;
        private final Node left;
        private final Node right;

        /**
         * 构造二元节点。
         *
         * @param op    运算符（AND/OR/= /!=/<>/</<=/>/>=）
         * @param left  左子树
         * @param right 右子树
         */
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

        /**
         * 构造取反节点。
         *
         * @param child 子谓词
         */
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

        /**
         * 构造列引用。
         *
         * @param name 列名
         */
        ColumnNode(String name) {
            this.name = name;
        }

        /**
         * 获取列名。
         *
         * @return 列名
         */
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

        /**
         * 构造字面量节点。
         *
         * @param value 字面值或 ParamMarker 占位
         */
        LiteralNode(Object value) {
            this.value = value;
        }

        @Override
        boolean eval(Object row, ParamProvider p) {
            return Boolean.TRUE.equals(value);
        }

        /**
         * 获取内部原始值。
         *
         * @return 原始值（可能为 ParamMarker）
         */
        Object value() {
            return value;
        }

        /**
     * 解析字面量或参数占位为实际值。
     *
     * @param v 待解析对象（LiteralNode / ParamMarker / 原始值）
     * @param p 参数游标，null 时占位返回 null
     * @return 解析后的值
     */
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

        /**
         * 构造空值判断节点。
         *
         * @param col     目标列
         * @param notNull true 表示 IS NOT NULL
         */
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

        /**
         * 构造闭区间判断节点。
         *
         * @param col 目标列
         * @param lo  下界（可为占位）
         * @param hi  上界（可为占位）
         */
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

        /**
         * 构造 IN 列表节点。
         *
         * @param col    目标列
         * @param values 候选值集合（元素可为占位）
         */
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

        /**
         * 构造 LIKE 节点。
         *
         * @param col     目标列
         * @param pattern 模式串，仅支持 % 通配（前缀/后缀/包含）
         */
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

        /** 排序列名 */
        final String column;

        /** 是否降序 */
        final boolean desc;

        /**
         * 构造排序项。
         *
         * @param column 列名
         * @param desc   是否降序
         */
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

        /**
         * 消费下一个绑定参数。
         *
         * @return 参数值，耗尽返回 null
         */
        Object next();
    }

    /** SELECT 语句：FROM 表行引用 + WHERE 树 + 投影/排序/截断 */
    static final class SelectStmt {

        /** 是否为 COUNT(*) 聚合 */
        boolean countStar;

        /** 是否 SELECT ALL */
        boolean selectAll;

        /** 投影列清单 */
        final List<String> selectColumns = new ArrayList<>();

        /** FROM 表名 */
        String table;

        /** WHERE 表达式树根节点，null 表示无条件 */
        Node where;

        /** 排序项列表 */
        final List<OrderItem> orderBys = new ArrayList<>();

        /** 返回上限，默认整型最大值表示不限 */
        int limit = Integer.MAX_VALUE;

        /** 偏移量，默认 0 */
        int offset;

        /** 绑定后的参数列表 */
        private List<Object> boundParams;

        /**
         * 绑定 ? 参数列表。
         *
         * @param params 参数值集合，null 视为空集
         */
        void bind(List<Object> params) {
            this.boundParams = params == null ? List.of() : params;
        }

        /**
         * 创建独立的按序参数游标（每行求值需新建以保证绑定值一致）。
         *
         * @return 游标提供器
         */
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

        /**
         * 依据 orderBys 构建多列比较器（null 值排最前）。
         *
         * @return 行比较器
         */
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

        /**
         * 对单行执行列投影。
         *
         * @param row 行对象
         * @return 投影后的有序映射
         */
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

        /** 显式列清单，未指定时由首行推断 */
        final List<String> columns = new ArrayList<>();

        /** 各待插入行的值集合 */
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

        /** SET 赋值映射（保持语句顺序） */
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
            /* SET 子句已消费前 N 个参数，WHERE 从 N 开始 */
            int setCount = upd.sets().size();
            List<Object> whereParams = safeParams.subList(setCount, safeParams.size());
            return applyUpdate(upd, rows, whereParams);
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

    /**
     * 将表达式节点作为值求值（列引用取行值，字面量解析占位）。
     *
     * @param n   表达式节点
     * @param row 行对象
     * @param p   参数游标
     * @return 求值结果
     */
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
    /**
     * 通用比较：数值按 double 比较，可比对象直接比较，否则退化字符串比较。
     *
     * @param a 左值
     * @param b 右值
     * @return 比较结果
     */
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
