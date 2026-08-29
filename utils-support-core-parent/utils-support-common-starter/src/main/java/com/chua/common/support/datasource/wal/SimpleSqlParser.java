package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.AbstractWalFileSystem;
import com.chua.common.support.wal.WalSegmentInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;

/**
 * 简化 SQL 解析器（不依赖 jsqlparser）。
 * 支持：SELECT/WHERE等值/ORDER BY/LIMIT/INSERT/UPDATE/DELETE
 */
@Slf4j
public class SimpleSqlParser {

    private final JdbcWalStoreSystem store;

    public SimpleSqlParser(JdbcWalStoreSystem store) {
        this.store = store;
    }

    public List<Map<String, Object>> parseSelect(String sql, Object... params) throws IOException {
        SelectPlan plan = parseSelectPlan(sql);
        List<Map<String, Object>> rows = scanAllRows(plan.tableName);
        if (plan.where != null) {
            rows = rows.stream().filter(r -> evalWhere(plan.where, r, params)).toList();
        }
        if (plan.orderBy != null) {
            final String col = plan.orderBy.col;
            final boolean desc = plan.orderBy.desc;
            rows.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get(col)),
                    desc ? Comparator.reverseOrder() : Comparator.naturalOrder()));
        }
        if (plan.limit != null) {
            int from = plan.offset != null ? plan.offset : 0;
            int to = Math.min(from + plan.limit, rows.size());
            rows = rows.subList(from, to);
        }
        if (plan.projections != null && !plan.projections.isEmpty() && !plan.projections.contains("*")) {
            rows = rows.stream().map(r -> {
                Map<String, Object> p = new LinkedHashMap<>();
                for (String col : plan.projections) if (r.containsKey(col)) p.put(col, r.get(col));
                return p;
            }).toList();
        }
        return rows;
    }

    public int parseDml(String sql, Object... params) throws IOException {
        String lower = sql.trim().toLowerCase();
        if (lower.startsWith("insert")) return execInsert(sql, params);
        if (lower.startsWith("update")) return execUpdate(sql, params);
        if (lower.startsWith("delete")) return execDelete(sql, params);
        throw new IllegalArgumentException("Unsupported SQL: " + sql);
    }

    // ==================== 内部解析 ====================

    private SelectPlan parseSelectPlan(String sql) {
        SelectPlan plan = new SelectPlan();
        String s = sql.trim();
        int fromIdx = s.toLowerCase().indexOf(" from ");
        if (fromIdx < 0) throw new IllegalArgumentException("Missing FROM: " + sql);
        String afterFrom = s.substring(fromIdx + 6).trim();
        int whereIdx = afterFrom.toLowerCase().indexOf(" where ");
        int obIdx = afterFrom.toLowerCase().indexOf(" order by ");
        int limIdx = afterFrom.toLowerCase().indexOf(" limit ");
        int end = min3(whereIdx, obIdx, limIdx, afterFrom.length());
        plan.tableName = afterFrom.substring(0, end).trim();
        String colsPart = s.substring(s.toLowerCase().indexOf("select") + 6, fromIdx).trim();
        plan.projections = parseColumns(colsPart);
        if (whereIdx >= 0) {
            int next = min2(obIdx, limIdx, afterFrom.length());
            plan.where = parseWhere(afterFrom.substring(whereIdx + 7, next).trim());
        }
        if (obIdx >= 0) {
            int next = min1(limIdx, afterFrom.length());
            String ob = afterFrom.substring(obIdx + 9, next).trim();
            String[] parts = ob.split("\\s+");
            plan.orderBy = new OrderBy(parts[0], parts.length > 1 && "desc".equalsIgnoreCase(parts[1]));
        }
        if (limIdx >= 0) {
            String[] parts = afterFrom.substring(limIdx + 6).trim().split("\\s+");
            plan.limit = Integer.parseInt(parts[0]);
            if (parts.length > 1) plan.offset = Integer.parseInt(parts[1]);
        }
        return plan;
    }

    private List<String> parseColumns(String cols) {
        if ("*".equals(cols)) return List.of("*");
        return Arrays.asList(cols.split(","));
    }

    private WhereCond parseWhere(String clause) {
        clause = clause.trim();
        for (String op : new String[]{" != ", " <> ", " >= ", " <= ", " > ", " < ", " = "}) {
            int idx = clause.indexOf(op);
            if (idx > 0) {
                return new WhereCond(clause.substring(0, idx).trim(), op.trim(),
                        clause.substring(idx + op.length()).trim());
            }
        }
        return null;
    }

    private boolean evalWhere(WhereCond wc, Map<String, Object> row, Object... params) {
        if (wc == null) return true;
        Object left = row.get(wc.col);
        Object right = resolveParam(wc.right, params);
        return compare(left, wc.op, right);
    }

    private Object resolveParam(String val, Object... params) {
        if (val == null) return null;
        if (val.startsWith("?")) {
            try { return params[Integer.parseInt(val.substring(1)) - 1]; }
            catch (Exception e) { return val; }
        }
        if (val.startsWith("'") && val.endsWith("'")) return val.substring(1, val.length() - 1);
        try { return Long.parseLong(val); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(val); } catch (NumberFormatException ignored) {}
        return val;
    }

    private boolean compare(Object left, String op, Object right) {
        if (left == null || right == null) return !"=".equals(op) && !"<>".equals(op) && !"!=".equals(op);
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

    private int execInsert(String sql, Object... params) throws IOException {
        String s = sql.trim();
        int paren = s.indexOf("(");
        String table = s.substring("insert into".length(), paren).trim();
        int paren2 = s.indexOf(")", paren);
        String cols = s.substring(paren + 1, paren2).trim();
        int valsIdx = s.toLowerCase().indexOf("values");
        int vp1 = s.indexOf("(", valsIdx);
        int vp2 = s.indexOf(")", vp1);
        String vals = s.substring(vp1 + 1, vp2).trim();
        String[] vparts = splitValues(vals);
        String[] cparts = cols.split(",");
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < cparts.length && i < vparts.length; i++) {
            row.put(cparts[i].trim(), parseValue(vparts[i].trim(), params));
        }
        store.insert(table, row);
        return 1;
    }

    private int execUpdate(String sql, Object... params) throws IOException {
        String s = sql.trim();
        int setIdx = s.toLowerCase().indexOf(" set ");
        int tableEnd = s.indexOf(" ", "update ".length());
        String table = s.substring("update ".length(), tableEnd).trim();
        String setClause = s.substring(setIdx + 5);
        int whereIdx = setClause.toLowerCase().indexOf(" where ");
        String setPart = whereIdx > 0 ? setClause.substring(0, whereIdx).trim() : setClause.trim();
        String whereClause = whereIdx > 0 ? setClause.substring(whereIdx + 7).trim() : "";
        WhereCond wc = parseWhere(whereClause);
        Map<String, Object> updates = new LinkedHashMap<>();
        for (String assign : setPart.split(",")) {
            String[] p = assign.trim().split("\\s*=\\s*");
            if (p.length == 2) updates.put(p[0].trim(), parseValue(p[1].trim(), params));
        }
        int count = 0;
        for (WalSegmentInfo seg : store.listSegments()) {
            store.walLog().replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if (AbstractWalFileSystem.isTombstone(op)) return true;
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) store.decodeValue("", payload);
                if (row != null && evalWhere(wc, row, params)) {
                    row.putAll(updates);
                    String rid = row.get("row_id") != null ? row.get("row_id").toString() : String.valueOf(lsn);
                    store.insertWithId(table, rid, row);
                    count++;
                }
                return true;
            });
        }
        return count;
    }

    private int execDelete(String sql, Object... params) throws IOException {
        String s = sql.trim();
        int fromIdx = s.toLowerCase().indexOf(" from ");
        String table = s.substring("delete ".length(), fromIdx).trim();
        int whereIdx = s.toLowerCase().indexOf(" where ");
        WhereCond wc = whereIdx > 0 ? parseWhere(s.substring(whereIdx + 7).trim()) : null;
        int count = 0;
        for (WalSegmentInfo seg : store.listSegments()) {
            store.walLog().replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if (AbstractWalFileSystem.isTombstone(op)) return true;
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) store.decodeValue("", payload);
                if (row != null && (wc == null || evalWhere(wc, row, params))) {
                    String rid = row.get("row_id") != null ? row.get("row_id").toString() : String.valueOf(lsn);
                    store.delete(rid);
                    count++;
                }
                return true;
            });
        }
        return count;
    }

    private Object parseValue(String val, Object... params) {
        if (val.startsWith("?")) {
            try { return params[Integer.parseInt(val.substring(1)) - 1]; } catch (Exception e) { return val; }
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
            if (c == ',' && !inQuote) { result.add(sb.toString()); sb = new StringBuilder(); }
            else sb.append(c);
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

    private static int min3(int a, int b, int c, int d) {
        int m = a < b ? a : b; m = m < c ? m : c; return m < d ? m : d;
    }
    private static int min2(int a, int b, int c) {
        int m = a < b ? a : b; return m < c ? m : c;
    }
    private static int min1(int a, int b) { return a < 0 ? b : (b < 0 ? a : Math.min(a, b)); }

    record SelectPlan(String tableName, List<String> projections,
                      WhereCond where, OrderBy orderBy, Integer limit, Integer offset) {}
    record WhereCond(String col, String op, String right) {}
    record OrderBy(String col, boolean desc) {}
}
