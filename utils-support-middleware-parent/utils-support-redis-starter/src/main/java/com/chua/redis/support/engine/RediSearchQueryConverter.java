package com.chua.redis.support.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RediSearch 查询语句转换器。
 *
 * <p>将 SQL WHERE 子句转换为 RediSearch FT.SEARCH 查询语法，
 * 支持 AND/OR/NOT/BETWEEN/LIKE/IN/IS NULL/比较运算符等常见 SQL 表达式。</p>
 *
 * @author CH
 * @since 4.0.0.41
 */
public class RediSearchQueryConverter {

    /**
     * 将 SQL WHERE 子句转换为 RediSearch 查询语法。
     *
     * @param sqlWhere SQL WHERE 子句
     * @param params   参数列表
     * @return RediSearch 查询字符串
     */
    public String convertToQuery(String sqlWhere, List<Object> params) {
        if (sqlWhere == null || sqlWhere.trim().isEmpty()) {
            return "*";
        }
        String where = sqlWhere.trim();
        if (where.toUpperCase().startsWith("WHERE ")) {
            where = where.substring(6).trim();
        }
        if (where.isEmpty()) {
            return "*";
        }

        ParamHolder ph = new ParamHolder(params);
        StringBuilder out = new StringBuilder();
        parse(where, ph, out);
        String result = out.toString().trim();
        if (result.isEmpty()) {
            return "*";
        }
        return result;
    }

    /**
     * 递归解析 SQL 条件表达式。
     *
     * @param expr 条件表达式
     * @param ph   参数持有者
     * @param out  输出缓冲区
     */
    private void parse(String expr, ParamHolder ph, StringBuilder out) {
        expr = expr.trim();
        if (expr.isEmpty()) {
            return;
        }

        List<String> parts = splitTopLevel(expr);
        if (parts.isEmpty()) {
            return;
        }

        String joinOp = detectTopLevelOp(expr);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                if (" ".equals(joinOp)) {
                    out.append(" ");
                } else {
                    out.append(" | ");
                }
            }
            String p = parts.get(i).trim();
            if (p.startsWith("(") && p.endsWith(")")) {
                out.append("(");
                parse(p.substring(1, p.length() - 1), ph, out);
                out.append(")");
            } else if (p.startsWith("NOT ") || p.startsWith("NOT(")) {
                String inner = p.substring(3).trim();
                if (inner.startsWith("(") && inner.endsWith(")")) {
                    inner = inner.substring(1, inner.length() - 1);
                }
                out.append("-(");
                parse(inner, ph, out);
                out.append(")");
            } else {
                convertCondition(p, ph, out);
            }
        }
    }

    /**
     * 按顶层 AND/OR 分割表达式。
     *
     * @param expr SQL 表达式
     * @return 分割后的子表达式列表
     */
    private List<String> splitTopLevel(String expr) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                int remaining = expr.length() - i;
                if (remaining >= 5 && " AND ".equalsIgnoreCase(expr.substring(i, i + 5))) {
                    parts.add(expr.substring(start, i));
                    start = i + 5;
                } else if (remaining >= 4 && " OR ".equalsIgnoreCase(expr.substring(i, i + 4))) {
                    parts.add(expr.substring(start, i));
                    start = i + 4;
                }
            }
        }
        if (start < expr.length()) {
            parts.add(expr.substring(start));
        }
        return parts;
    }

    /**
     * 检测顶层连接运算符。
     *
     * @param expr SQL 表达式
     * @return "|" 或 " "
     */
    private String detectTopLevelOp(String expr) {
        if (expr.toUpperCase().contains(" OR ")) {
            return "|";
        }
        return " ";
    }

    /**
     * 转换单个条件为 RediSearch 语法。
     *
     * @param cond 单个条件
     * @param ph   参数持有者
     * @param out  输出缓冲区
     */
    private void convertCondition(String cond, ParamHolder ph, StringBuilder out) {
        cond = cond.trim();

        String[] ops = {
                " NOT BETWEEN ", " BETWEEN ", " NOT LIKE ", " LIKE ",
                " IS NOT NULL", " IS NULL", " <> ", " >= ", " <= ", " > ", " < ", " = "
        };
        for (String op : ops) {
            int idx = cond.toUpperCase().indexOf(op);
            if (idx < 0) {
                continue;
            }
            String field = cond.substring(0, idx).trim();
            String rest = cond.substring(idx + op.length()).trim();

            switch (op.trim()) {
                case "=":
                    out.append("@").append(field).append(":[")
                            .append(ph.next()).append(" ").append(ph.nextPrev()).append("]");
                    return;
                case "<>":
                    out.append("-@").append(field).append(":[")
                            .append(ph.next()).append(" ").append(ph.nextPrev()).append("]");
                    return;
                case ">":
                    out.append("@").append(field).append(":[(")
                            .append(ph.next()).append(" +inf]");
                    return;
                case ">=":
                    out.append("@").append(field).append(":[")
                            .append(ph.next()).append(" +inf]");
                    return;
                case "<":
                    out.append("@").append(field).append(":[-inf (")
                            .append(ph.next()).append("]");
                    return;
                case "<=":
                    out.append("@").append(field).append(":[-inf ")
                            .append(ph.next()).append("]");
                    return;
                case "LIKE":
                    out.append("@").append(field).append(":")
                            .append(ph.next()).append("*");
                    return;
                case "NOT LIKE":
                    out.append("-@").append(field).append(":")
                            .append(ph.next()).append("*");
                    return;
                case "BETWEEN":
                    out.append("@").append(field).append(":[")
                            .append(ph.next()).append(" ").append(ph.next()).append("]");
                    return;
                case "NOT BETWEEN":
                    out.append("-@").append(field).append(":[")
                            .append(ph.next()).append(" ").append(ph.next()).append("]");
                    return;
                case "IS NULL":
                    out.append("-").append(field).append(":*");
                    return;
                case "IS NOT NULL":
                    out.append(field).append(":*");
                    return;
                default:
                    break;
            }
            break;
        }

        /*
         * 处理 IN/NOT IN 条件。
         * 转换为 RediSearch 的 (val1|val2|...) 语法。
         */
        if (cond.toUpperCase().contains("IN")) {
            int inIdx = cond.toUpperCase().indexOf("IN (");
            if (inIdx < 0) {
                inIdx = cond.toUpperCase().indexOf("IN(");
            }
            if (inIdx >= 0) {
                boolean not = cond.substring(0, inIdx).toUpperCase().contains("NOT");
                String field = cond.substring(0, inIdx).replaceAll("(?i)NOT\\s*", "").trim();
                String listPart = cond.substring(cond.indexOf('(') + 1, cond.lastIndexOf(')'));
                int count = listPart.split(",").length;
                if (not) {
                    out.append("-");
                }
                out.append("@").append(field).append(":(");
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        out.append("|");
                    }
                    out.append(ph.next());
                }
                out.append(")");
            }
        }
    }

    /**
     * 参数持有者。
     *
     * <p>按顺序提供 SQL 参数值，记录上一个值用于范围查询。</p>
     */
    static class ParamHolder {

        /**

         * * 参数列表

         */
        final List<Object> params;

        /**

         * * 当前参数索引

         */
        int idx;

        /**

         * * 上一个参数值

         */
        Object prev;

        /**
         * 创建参数持有者。
         *
         * @param params 参数列表
         */
        ParamHolder(List<Object> params) {
            this.params = params;
            this.idx = 0;
        }

        /**
         * 获取下一个参数值。
         *
         * @return 参数值
         */
        Object next() {
            if (idx < params.size()) {
                prev = params.get(idx);
            } else {
                prev = "";
            }
            idx++;
            return prev;
        }

        /**
         * 获取上一个参数值。
         *
         * @return 上一个参数值
         */
        Object nextPrev() {
            return prev;
        }
    }
}
