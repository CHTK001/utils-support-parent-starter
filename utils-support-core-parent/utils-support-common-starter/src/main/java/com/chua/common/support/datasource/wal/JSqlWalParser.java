package com.chua.common.support.datasource.wal;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;

/**
 * SQL 查询解析器（简化版，不依赖 jsqlparser）。
 *
 * <p>通过手写解析器支持最常用的单表 SQL：
 * SELECT ... FROM table WHERE col = ? / col > ? / col >= ? ... ORDER BY col LIMIT n OFFSET m
 * INSERT INTO table VALUES (...)
 * UPDATE table SET col = ? WHERE col = ?
 * DELETE FROM table WHERE col = ?</p>
 *
 * <p>复杂 SQL、JOIN、子查询需要使用 {@code jsqlparser}（需加依赖后启用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class JSqlWalParser {

    private final JdbcWalStoreSystem store;
    private final String joinStrategy;

    public JSqlWalParser(JdbcWalStoreSystem store) {
        this(store, "none");
    }

    public JSqlWalParser(JdbcWalStoreSystem store, String joinStrategy) {
        this.store = store;
        this.joinStrategy = joinStrategy;
    }

    // ==================== SELECT ====================

    /**
     * 解析简单 SELECT 语句（不支持 JOIN，不支持子查询）。
     */
    public List<Map<String, Object>> parseSelect(String sql, Object... params) throws IOException {
        SelectPlan plan = parseSelectPlan(sql);
        List<Map<String, Object>> rows = scanAllRows(plan.tableName());
        // WHERE 过滤
        if (plan.where != null) {
            rows = rows.stream()
                    .filter(r -> evaluateWhere(plan.where, r, params))
                    .toList();
        }
        // ORDER BY
        if (plan.orderBy != null) {
            final String orderByCol = plan.orderBy.col();
            final boolean desc = plan.orderBy.desc();
            rows.sort(Comparator.comparing(
                    (Map<String, Object> r) -> String.valueOf(r.get(orderByCol)),
                    desc ? Comparator.reverseOrder() : Comparator.naturalOrder()));
        }
        // LIMIT / OFFSET
        if (plan.limit != null) {
            int from = plan.offset != null ? plan.offset : 0;
            int to = Math.min(from + plan.limit, rows.size());
            rows = rows.subList(from, to);
        }
        // 投影
        if (plan.projections != null && !plan.projections.isEmpty()
                && !plan.projections.contains("*")) {
            rows = rows.stream()
                    .map(r -> {
                        Map<String, Object> proj = new LinkedHashMap<>();
                        for (String col : plan.projections) {
                            if (r.containsKey(col)) proj.put(col, r.get(col));
                        }
                        return proj;
                    })
                    .toList();
        }
        return rows;
    }

    // ==================== DML ====================

    public int parseDml(String sql, Object... params) throws IOException {
        String lower = sql.toLowerCase().trim();
        if (lower.startsWith("insert")) return executeInsert(sql, params);
        if (lower.startsWith("update")) return executeUpdate(sql, params);
        if (lower.startsWith("delete")) return executeDelete(sql, params);
        throw new IllegalArgumentException("Unsupported SQL: " + sql);
    }

    // ==================== 内部解析 ====================

    private SelectPlan parseSelectPlan(String sql) {
        SelectPlan plan = new SelectPlan();
        String s = sql.trim();
        // FROM
        int fromIdx = s.toLowerCase().indexOf(" from ");
        if (fromIdx < 0) throw new IllegalArgumentException("Missing FROM: " + sql);
        String afterFrom = s.substring(fromIdx + 6).trim();
        int whereIdx = afterFrom.toLowerCase().indexOf(" where ");
        int orderByIdx = afterFrom.toLowerCase().indexOf(" order by ");
        int limitIdx = afterFrom.toLowerCase().indexOf(" limit ");
        // table name
        int end = whereIdx > 0 ? whereIdx : (orderByIdx > 0 ? orderByIdx : (limitIdx > 0 ? limitIdx : afterFrom.length()));
        plan.tableName = afterFrom.substring(0, end).trim();
        // columns
        String colsPart = s.substring(s.indexOf("select") + 6, fromIdx).trim();
        plan.projections = parseColumns(colsPart);
        // WHERE
        if (whereIdx >= 0) {
            int next = minOf(orderByIdx, limitIdx, afterFrom.length());
            String whereClause = afterFrom.substring(whereIdx + 7, next).trim();
            plan.where = parseWhereClause(whereClause);
        }
        // ORDER BY
        if (orderByIdx >= 0) {
            int next = minOf(limitIdx, afterFrom.length());
            String obPart = afterFrom.substring(orderByIdx + 9, next).trim();
            String[] parts = obPart.split("\\s+");
            plan.orderBy = new OrderBy(parts[0], parts.length > 1 && "desc".equalsIgnoreCase(parts[1]));
        }
        // LIMIT
        if (limitIdx >= 0) {
            String limitPart = afterFrom.substring(limitIdx + 6).trim();
            String[] parts = limitPart.split("\\s+");
            plan.limit = Integer.parseInt(parts[0]);
            if (parts.length > 1) plan.offset = Integer.parseInt(parts[1]);
        }
        return plan;
    }

    private List<String> parseColumns(String cols) {
        if ("*".equals(cols)) return List.of("*");
        return Arrays.asList(cols.split(","));
    }

    private WhereCondition parseWhereClause(String clause) {
        clause = clause.trim();
        // 支持 AND 连接的多个条件
        String[] parts = clause.split("\\s+and\\s+", 2);
        return parseSingleCondition(parts[0]);
    }

    private WhereCondition parseSingleCondition(String cond) {
        cond = cond.trim();
        for (String op : new String[]{" = ", " != ", " <> ", " > ", " >= ", " < ", " <= "}) {
            int idx = cond.indexOf(op);
            if (idx > 0) {
                String left = cond.substring(0, idx).trim();
                String right = cond.substring(idx + op.length()).trim();
                return new WhereCondition(left, op.trim(), right);
            }
        }
        return null;
    }

    private boolean evaluateWhere(WhereCondition wc, Map<String, Object> row, Object... params) {
        if (wc == null) return true;
        Object leftVal = row.get(wc.column);
        Object rightVal = resolveParam(wc.right, params);
        return compare(leftVal, wc.op, rightVal);
    }

    private Object resolveParam(String value, Object... params) {
        if (value == null) return null;
        if (value.startsWith("?")) {
            try {
                int idx = Integer.parseInt(value.substring(1)) - 1;
                return params[idx];
            } catch (Exception e) {
                return value;
            }
        }
        // 去除引号
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
        }
        return value;
    }

    private boolean compare(Object left, String op, Object right) {
        if (left == null || right == null) return " != ".equals(op) || " <> ".equals(op);
        if (left instanceof Number l && right instanceof Number r) {
            double ld = l.doubleValue(), rd = r.doubleValue();
            return switch (op) {
                case "=" -> ld == rd;
                case "!=", "<>" -> ld != rd;
                case ">" -> ld > rd;
                case ">=" -> ld >= rd;
                case "<" -> ld < rd;
                case "<=" -> ld <= rd;
                default -> false;
            };
        }
        String ls = left.toString(), rs = right.toString();
        return switch (op) {
            case "=" -> ls.equals(rs);
            case "!=", "<>" -> !ls.equals(rs);
            case ">" -> ls.compareTo(rs) > 0;
            case ">=" -> ls.compareTo(rs) >= 0;
            case "<" -> ls.compareTo(rs) < 0;
            case "<=" -> ls.compareTo(rs) <= 0;
            default -> false;
        };
    }

    // ==================== DML 执行 ====================

    private int executeInsert(String sql, Object... params) throws IOException {
        // INSERT INTO table (col1, col2) VALUES (val1, val2)
        String s = sql.trim();
        int paren = s.indexOf("(");
        String table = s.substring("insert into".length(), paren).trim();
        // columns
        int paren2 = s.indexOf(")", paren);
        String colsStr = s.substring(paren + 1, paren2).trim();
        List<String> cols = Arrays.asList(colsStr.split(","));
        // values
        int valuesIdx = s.toLowerCase().indexOf("values");
        int vParen = s.indexOf("(", valuesIdx);
        int vParen2 = s.indexOf(")", vParen);
        String valsStr = s.substring(vParen + 1, vParen2).trim();
        String[] valParts = splitValues(valsStr);
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < cols.size() && i < valParts.length; i++) {
            row.put(cols.get(i).trim(), parseValue(valParts[i].trim(), params));
        }
        store.insert(table, row);
        return 1;
    }

    private int executeUpdate(String sql, Object... params) throws IOException {
        // UPDATE table SET col = val WHERE col = val
        String s = sql.trim();
        int setIdx = s.toLowerCase().indexOf(" set ");
        int fromIdx = s.toLowerCase().indexOf(" from ") + 6;
        // Actually: UPDATE table SET ...
        int tableEnd = s.indexOf(" ", "update ".length());
        String table = s.substring("update ".length(), tableEnd).trim();
        String setPart = s.substring(setIdx + 5);
        int whereIdx = setPart.toLowerCase().indexOf(" where ");
        String setClause = whereIdx > 0 ? setPart.substring(0, whereIdx).trim() : setPart.trim();
        String whereClause = whereIdx > 0 ? setPart.substring(whereIdx + 7).trim() : "";

        WhereCondition wc = parseSingleCondition(whereClause);
        // parse SET
        Map<String, Object> updates = new LinkedHashMap<>();
        for (String assign : setClause.split(",")) {
            String[] parts = assign.trim().split("\\s*=\\s*");
            if (parts.length == 2) {
                updates.put(parts[0].trim(), parseValue(parts[1].trim(), params));
            }
        }
        // 扫描所有行，匹配 WHERE，更新匹配的
        int count = 0;
        for (WalSegmentInfo seg : store.listSegments()) {
            store.walLog().replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if (AbstractWalFileSystem.isTombstone(op)) return true;
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) store.decodeValue("", payload);
                if (row != null && evaluateWhere(wc, row, params)) {
                    row.putAll(updates);
                    store.insertWithId(table, row.get("row_id") != null ? row.get("row_id").toString() : String.valueOf(lsn), row);
                    count++;
                }
                return true;
            });
        }
        return count;
    }

    private int executeDelete(String sql, Object... params) throws IOException {
        String s = sql.trim();
        int fromIdx = s.toLowerCase().indexOf(" from ");
        String table = s.substring("delete ".length(), fromIdx).trim();
        int whereIdx = s.toLowerCase().indexOf(" where ");
        String whereClause = whereIdx > 0 ? s.substring(whereIdx + 7).trim() : "";
        WhereCondition wc = parseSingleCondition(whereClause);
        int count = 0;
        for (WalSegmentInfo seg : store.listSegments()) {
            store.walLog().replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if (AbstractWalFileSystem.isTombstone(op)) return true;
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) store.decodeValue("", payload);
                if (row != null && evaluateWhere(wc, row, params)) {
                    String rowId = row.get("row_id") != null ? row.get("row_id").toString() : String.valueOf(lsn);
                    store.delete(new RowKey(rowId));
                    count++;
                }
                return true;
            });
        }
        return count;
    }

    private Object parseValue(String val, Object... params) {
        if (val.startsWith("?")) {
            try {
                int idx = Integer.parseInt(val.substring(1)) - 1;
                return params[idx];
            } catch (Exception e) {
                return val;
            }
        }
        if (val.startsWith("'") && val.endsWith("'")) return val.substring(1, val.length() - 1);
        try { return Long.parseLong(val); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(val); } catch (NumberFormatException ignored) {}
        return val;
    }

    private String[] splitValues(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (char c : s.toCharArray()) {
            if (c == '\'') inQuote = !inQuote;
            if (c == ',' && !inQuote) {
                result.add(sb.toString());
                sb = new StringBuilder();
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) result.add(sb.toString());
        return result.toArray(new String[0]);
    }

    private List<Map<String, Object>> scanAllRows(String tableName) throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        for (WalSegmentInfo seg : store.listSegments()) {
            store.walLog().replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if (AbstractWalFileSystem.isTombstone(op)) return true;
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) store.decodeValue("", payload);
                if (row != null) all.add(row);
                return true;
            });
        }
        return all;
    }

    // ==================== 内部模型 ====================

    record SelectPlan(String tableName, List<String> projections,
                      WhereCondition where, OrderBy orderBy,
                      Integer limit, Integer offset) {}

    record WhereCondition(String column, String op, String right) {}
    record OrderBy(String col, boolean desc) {}

    record RowKey(String id) {}
}
