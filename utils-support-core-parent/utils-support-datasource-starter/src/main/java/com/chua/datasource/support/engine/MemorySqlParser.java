package com.chua.datasource.support.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.chua.datasource.support.engine.MemorySqlAst.BetweenNode;
import com.chua.datasource.support.engine.MemorySqlAst.BinaryNode;
import com.chua.datasource.support.engine.MemorySqlAst.ColumnNode;
import com.chua.datasource.support.engine.MemorySqlAst.DeletePlan;
import com.chua.datasource.support.engine.MemorySqlAst.DmlPlan;
import com.chua.datasource.support.engine.MemorySqlAst.InNode;
import com.chua.datasource.support.engine.MemorySqlAst.InsertPlan;
import com.chua.datasource.support.engine.MemorySqlAst.IsNullNode;
import com.chua.datasource.support.engine.MemorySqlAst.LikeNode;
import com.chua.datasource.support.engine.MemorySqlAst.LiteralNode;
import com.chua.datasource.support.engine.MemorySqlAst.Node;
import com.chua.datasource.support.engine.MemorySqlAst.NotNode;
import com.chua.datasource.support.engine.MemorySqlAst.OrderItem;
import com.chua.datasource.support.engine.MemorySqlAst.ParamMarker;
import com.chua.datasource.support.engine.MemorySqlAst.SelectStmt;
import com.chua.datasource.support.engine.MemorySqlAst.UpdatePlan;
import com.chua.datasource.support.engine.MemorySqlLex.TokenStream;

/**
 * 内存引擎原生 SQL 解析器：将单表 SQL 编译为二叉表达式树（AST），
 * 求值时以内存 列表 行引用为输入执行过滤 / 投影 / 排序 / 截断管道。
 *
 * <p>支持：SELECT 列清单 / * / COUNT(*)、WHERE、ORDER BY、LIMIT [OFFSET]、
 * 插入 多值、更新 设置、删除，运算符 = != &lt;&gt; &lt; &lt;= &gt; &gt;=
 * LIKE 入 BETWEEN 是否 [NOT] 空 及 和 / 或 / 括号 / NOT，支持 ? 参数绑定。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MemorySqlParser {

    /**
     * 解析并执行查询。
     *
     * @param sql    选择 语句
     * @param rows   从 表对应的行引用列表
     * @param params 绑定参数
     * @return 结果行集合
     */
    public List<java.util.Map<String, Object>> executeQuery(String sql, List<?> rows, Object[] params) {
        SelectStmt stmt = parseSelect(sql);
        stmt.bind(params == null ? List.of() : java.util.Arrays.asList(params));
        return stmt.evaluate(rows);
    }

    /**
     * 解析 DML 为执行计划（不执行），由引擎对行引用应用变更。
     *
     * @param sql DML 语句
     * @return 计划
     */
    public DmlPlan parseDml(String sql) {
        TokenStream ts = new TokenStream(sql);
        switch (ts.peek().toUpperCase(Locale.ROOT)) {
            case "INSERT":
                return parseInsert(ts);
            case "UPDATE":
                return parseUpdate(ts);
            case "DELETE":
                return parseDelete(ts);
            default:
                throw new IllegalArgumentException("不支持的 SQL 类型: " + ts.peek());
        }
    }

    /**
     * 解析 选择 文本为语句树。
     *
     * @param sql 选择 语句
     * @return 语句对象
     */
    public SelectStmt parseSelect(String sql) {
        TokenStream ts = new TokenStream(sql);
        expectKeyword(ts, "SELECT");
        SelectStmt stmt = new SelectStmt();
        if (matchKeyword(ts, "COUNT")) {
            consume(ts, "(");
            consume(ts, "*");
            consume(ts, ")");
            stmt.countStar = true;
        } else if (match(ts, "*")) {
            stmt.selectAll = true;
        } else {
            do {
                stmt.selectColumns.add(consumeIdentifier(ts));
            } while (match(ts, ","));
        }
        expectKeyword(ts, "FROM");
        stmt.table = consumeIdentifier(ts);
        if (matchKeyword(ts, "WHERE")) {
            stmt.where = parseOr(ts);
        }
        if (matchKeyword(ts, "ORDER")) {
            expectKeyword(ts, "BY");
            do {
                String col = consumeIdentifier(ts);
                boolean desc = matchKeyword(ts, "DESC");
                if (!desc) {
                    matchKeyword(ts, "ASC");
                }
                stmt.orderBys.add(new OrderItem(col, desc));
            } while (match(ts, ","));
        }
        if (matchKeyword(ts, "LIMIT")) {
            stmt.limit = consumeInt(ts);
            if (matchKeyword(ts, "OFFSET")) {
                stmt.offset = consumeInt(ts);
            }
        }
        return stmt;
    }

    /* ==================== 表达式树：优先级递归下降 ==================== */

    /**
     * 解析 或 层级表达式（最低优先级）。
     *
     * @param ts 词法流
     * @return 表达式树节点
     */
    private Node parseOr(TokenStream ts) {
        Node left = parseAnd(ts);
        while (matchKeyword(ts, "OR")) {
            left = new BinaryNode("OR", left, parseAnd(ts));
        }
        return left;
    }

    /**
     * 解析 和 层级表达式（中优先级）。
     *
     * @param ts 词法流
     * @return 表达式树节点
     */
    private Node parseAnd(TokenStream ts) {
        Node left = parseSimple(ts);
        while (matchKeyword(ts, "AND")) {
            left = new BinaryNode("AND", left, parseSimple(ts));
        }
        return left;
    }

    /**
     * 解析单个谓词：括号分组 / NOT / 是否 空 / BETWEEN / 入 / LIKE / 比较运算。
     *
     * @param ts 词法流
     * @return 谓词节点
     */
    private Node parseSimple(TokenStream ts) {
        if (match(ts, "(")) {
            Node inner = parseOr(ts);
            consume(ts, ")");
            return inner;
        }
        boolean negated = matchKeyword(ts, "NOT");
        ColumnNode col = new ColumnNode(consumeIdentifier(ts));
        Node result;
        if (matchKeyword(ts, "IS")) {
            boolean notNull = matchKeyword(ts, "NOT");
            expectKeyword(ts, "NULL");
            result = new IsNullNode(col, notNull);
        } else if (matchKeyword(ts, "BETWEEN")) {
            Object lo = consumeValueOrParam(ts);
            expectKeyword(ts, "AND");
            Object hi = consumeValueOrParam(ts);
            result = new BetweenNode(col, lo, hi);
        } else if (matchKeyword(ts, "IN")) {
            consume(ts, "(");
            List<Object> values = new ArrayList<>();
            do {
                values.add(consumeValueOrParam(ts));
            } while (match(ts, ","));
            consume(ts, ")");
            result = new InNode(col, values);
        } else if (matchKeyword(ts, "LIKE")) {
            String pattern = ts.next();
            if (!pattern.startsWith("'")) {
                throw new IllegalArgumentException("LIKE 模式需为字符串: " + pattern);
            }
            result = new LikeNode(col, pattern.substring(1, pattern.length() - 1));
        } else {
            String op = consumeOperator(ts);
            Object rhs = consumeValueOrParam(ts);
            result = new BinaryNode(op, col, new LiteralNode(rhs));
        }
        return negated ? new NotNode(result) : result;
    }

    /* ==================== DML ==================== */

    /**
     * 解析 插入 语句为计划。
     *
     * @param ts 词法流
     * @return 插入计划
     */
    private DmlPlan parseInsert(TokenStream ts) {
        expectKeyword(ts, "INSERT");
        expectKeyword(ts, "INTO");
        InsertPlan plan = new InsertPlan();
        plan.table = consumeIdentifier(ts);
        if (match(ts, "(")) {
            do {
                plan.columns.add(consumeIdentifier(ts));
            } while (match(ts, ","));
            consume(ts, ")");
        }
        expectKeyword(ts, "VALUES");
        do {
            consume(ts, "(");
            List<Object> row = new ArrayList<>();
            do {
                row.add(consumeValueOrParam(ts));
            } while (match(ts, ","));
            consume(ts, ")");
            plan.rows.add(row);
        } while (match(ts, ","));
        return plan;
    }

    /**
     * 解析 更新 语句为计划。
     *
     * @param ts 词法流
     * @return 更新计划
     */
    private DmlPlan parseUpdate(TokenStream ts) {
        expectKeyword(ts, "UPDATE");
        UpdatePlan plan = new UpdatePlan();
        plan.table = consumeIdentifier(ts);
        expectKeyword(ts, "SET");
        do {
            String col = consumeIdentifier(ts);
            consume(ts, "=");
            plan.sets.put(col, consumeValueOrParam(ts));
        } while (match(ts, ","));
        if (matchKeyword(ts, "WHERE")) {
            plan.where = parseOr(ts);
        }
        return plan;
    }

    /**
     * 解析 删除 语句为计划。
     *
     * @param ts 词法流
     * @return 删除计划
     */
    private DmlPlan parseDelete(TokenStream ts) {
        expectKeyword(ts, "DELETE");
        expectKeyword(ts, "FROM");
        DeletePlan plan = new DeletePlan();
        plan.table = consumeIdentifier(ts);
        if (matchKeyword(ts, "WHERE")) {
            plan.where = parseOr(ts);
        }
        return plan;
    }

    /* ==================== 词法辅助 ==================== */

    /**
     * 断言下一个 令牌 为指定关键字，否则抛出解析异常。
     *
     * @param ts 词法流
     * @param kw 期望关键字
     */
    private static void expectKeyword(TokenStream ts, String kw) {
        if (!matchKeyword(ts, kw)) {
            throw new IllegalArgumentException("期望 " + kw + " 但得到: "
                    + (ts.eof() ? "<EOF>" : ts.peek()));
        }
    }

    /**
     * 尝试匹配关键字，命中则消费。
     *
     * @param ts 词法流
     * @param kw 关键字（忽略大小写）
     * @return 是否命中
     */
    private static boolean matchKeyword(TokenStream ts, String kw) {
        if (!ts.eof() && ts.peek().equalsIgnoreCase(kw)) {
            ts.next();
            return true;
        }
        return false;
    }

    /**
     * 尝试匹配符号，命中则消费。
     *
     * @param ts     词法流
     * @param symbol 符号字面量
     * @return 是否命中
     */
    private static boolean match(TokenStream ts, String symbol) {
        if (!ts.eof() && ts.peek().equals(symbol)) {
            ts.next();
            return true;
        }
        return false;
    }

    /**
     * 强制消费指定符号，未命中抛出解析异常。
     *
     * @param ts     词法流
     * @param symbol 期望符号
     */
    private static void consume(TokenStream ts, String symbol) {
        if (!match(ts, symbol)) {
            throw new IllegalArgumentException("期望 " + symbol + " 但得到: "
                    + (ts.eof() ? "<EOF>" : ts.peek()));
        }
    }

    /**
     * 消费一个合法标识符（表名/列名）。
     *
     * @param ts 词法流
     * @return 标识符
     */
    private static String consumeIdentifier(TokenStream ts) {
        if (ts.eof()) {
            throw new IllegalArgumentException("意外的语句结尾");
        }
        String t = ts.next();
        if (!Character.isJavaIdentifierStart(t.charAt(0))) {
            throw new IllegalArgumentException("期望标识符但得到: " + t);
        }
        return t;
    }

    /**
     * 消费一个整数字面量（限制/偏移量）。
     *
     * @param ts 词法流
     * @return 整数值
     */
    private static int consumeInt(TokenStream ts) {
        String t = ts.next();
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("期望数字但得到: " + t);
        }
    }

    /**
     * 消费一个比较运算符。
     *
     * @param ts 词法流
     * @return 运算符字面量
     */
    private static String consumeOperator(TokenStream ts) {
        String t = ts.next();
        switch (t) {
            case "=":
            case "!=":
            case "<>":
            case "<":
            case "<=":
            case ">":
            case ">=":
                return t;
            default:
                throw new IllegalArgumentException("不支持的运算符: " + t);
        }
    }

    /**
     * 消费一个值：? 占位 / '字符串' / 空 / 数值 / 裸标识符。
     *
     * @param ts 词法流
     * @return 值对象或 参数记号笔 占位
     */
    private Object consumeValueOrParam(TokenStream ts) {
        if (match(ts, "?")) {
            return new ParamMarker();
        }
        String t = ts.next();
        if (t.startsWith("'")) {
            return t.substring(1, t.length() - 1);
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            // 非整数继续尝试其他类型
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            // 非长整型继续尝试浮点
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return t;
        }
    }
}
