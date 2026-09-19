package com.chua.calcite.support.datasource;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.datasource.support.datasource.DataScheme;
import com.chua.datasource.support.datasource.DataTable;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将简单 SQL 更新 路由到 {@link Engine}（Calcite modifiabletable 不支持 更新）。
 * <p>
 * 支持形态（MySQL 词法，反引号可选）：
 * <pre>
 * UPDATE [`schema`.]`table` SET `c1`=v1, `c2`=v2 WHERE `c3`=v3 [AND `c4`=v4 ...]
 * </pre>
 * 设置/WHERE 值支持：字符串字面量、数字、空、布尔。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class EngineUpdateSqlExecutor {

    /**
     * 更新 语句解析正则
     */
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+(?:(?:`([^`]+)`|([A-Za-z_][\\w$]*))\\s*\\.\\s*)?(?:`([^`]+)`|([A-Za-z_][\\w$]*))\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?\\s*$"
    );

    /**
     * SQL 更新 关键字
     */
    private static final String SQL_UPDATE_KEYWORD = "UPDATE";

    /** 可路由条件的列名白名单：不带引用符的简单标识符 */
    private static final java.util.regex.Pattern COLUMN_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][\\w$]{0,127}");

    /** 字面量解析失败哨兵：不能把无法识别的片段当作字符串值写入 */
    private static final Object INVALID_LITERAL = new Object();

    /**
     * 已注册的引擎方案列表
     */
    private final List<DataScheme> schemes;

    /**
     * 构造路由执行器。
     *
     * @param schemes 已注册的引擎方案列表
     */
    public EngineUpdateSqlExecutor(List<DataScheme> schemes) {
        this.schemes = schemes != null ? schemes : List.of();
    }

    /**
     * 若 SQL 为可路由的 更新 则执行并返回影响行数；否则返回 空 交由 Calcite。
     *
     * @param sql 原始 SQL
     * @return 影响行数；不可路由返回 空
     */
    public Integer tryExecute(String sql) {
        RoutableUpdate update = parse(sql);
        return update == null ? null : update.execute();
    }

    /**
     * 解析并判定 SQL 是否可路由到引擎，**不执行任何写入**。
     * <p>调用方据此把「是否路由」的决策放在语句准备阶段，把「执行」推迟到
     * {@code executeUpdate} 时刻，避免预编译即产生副作用。</p>
     *
     * @param sql 原始 SQL
     * @return 可路由的更新描述；不可路由返回 空
     */
    public RoutableUpdate parse(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (!trimmed.regionMatches(true, 0, SQL_UPDATE_KEYWORD, 0, SQL_UPDATE_KEYWORD.length())) {
            return null;
        }
        // 一次执行只能复现一条语句
        if (trimmed.indexOf(';') >= 0) {
            log.debug("[calcite] UPDATE 含多条语句，不路由: {}", sql);
            return null;
        }
        // 占位符的绑定值只在 JDBC 侧可见，引擎侧无法复现
        if (hasPlaceholder(trimmed)) {
            log.debug("[calcite] UPDATE 含参数占位符，不路由: {}", sql);
            return null;
        }
        Matcher m = UPDATE.matcher(trimmed);
        if (!m.matches()) {
            log.debug("[calcite] UPDATE 未匹配可路由形态，交由 Calcite: {}", sql);
            return null;
        }
        String schema = first(m.group(1), m.group(2));
        String table = first(m.group(3), m.group(4));
        String setPart = m.group(5);
        String wherePart = m.group(6);

        Map<String, Object> sets = parseAssignments(setPart);
        if (sets == null || sets.isEmpty()) {
            log.debug("[calcite] UPDATE 的 SET 含无法解析的赋值，不路由: {}", sql);
            return null;
        }
        Map<String, Object> wheres;
        if (wherePart == null || wherePart.isBlank()) {
            wheres = Map.of();
        } else {
            wheres = parseAndEquals(wherePart);
            if (wheres == null) {
                // WHERE 存在却解析不出完整等值条件：路由将退化为全表更新，必须拒绝
                log.debug("[calcite] UPDATE 的 WHERE 含非等值条件，不路由: {}", sql);
                return null;
            }
        }

        SourceDataTable source = resolveTable(schema, table);
        if (source == null) {
            return null;
        }
        return new RoutableUpdate(source, sets, wheres);
    }

    /**
     * 一条已判定可路由的 更新，实际写入推迟到 {@link #execute()}。
     */
    public static final class RoutableUpdate {

        private final SourceDataTable source;
        private final Map<String, Object> sets;
        private final Map<String, Object> wheres;

        private RoutableUpdate(SourceDataTable source, Map<String, Object> sets, Map<String, Object> wheres) {
            this.source = source;
            this.sets = sets;
            this.wheres = wheres;
        }

        /**
         * 执行路由到引擎的更新。
         *
         * @return 影响行数
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public int execute() {
            Engine engine = source.getEngine();
            Class<?> entityClass = source.getEntityClass();
            LambdaUpdateWrapper wrapper = (LambdaUpdateWrapper) engine.update(entityClass);
            for (Map.Entry<String, Object> e : sets.entrySet()) {
                wrapper.set(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Object> e : wheres.entrySet()) {
                wrapper.eq(e.getKey(), e.getValue());
            }
            int rows = wrapper.update();
            log.debug("[calcite] Engine UPDATE {}.{} 影响 {} 行",
                    source.getName(), entityClass.getSimpleName(), rows);
            return rows;
        }
    }

    /**
     * 字符串字面量之外是否存在参数占位符。
     *
     * @param sql 已裁剪的 SQL
     * @return 存在返回 true
     */
    private static boolean hasPlaceholder(String sql) {
        boolean inStr = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (inStr && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inStr = !inStr;
            } else if (!inStr && c == '?') {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析Table
     *
     * @param schema 模式
     * @param table table
     * @return resolveTable的结果
     */
    private SourceDataTable resolveTable(String schema, String table) {
        String tableKey = table == null ? null : table.toLowerCase(Locale.ROOT);
        for (DataScheme scheme : schemes) {
            if (schema != null && scheme.getName() != null
                    && !scheme.getName().equalsIgnoreCase(schema)) {
                continue;
            }
            DataTable t = scheme.getTable(table);
            if (t == null && tableKey != null) {
                for (String name : scheme.getTableNames()) {
                    if (name != null && name.equalsIgnoreCase(table)) {
                        t = scheme.getTable(name);
                        break;
                    }
                }
            }
            if (t instanceof SourceDataTable source) {
                return source;
            }
        }
        return null;
    }

    /**
     * 解析Assignments
     *
     * @param part part
     * @return 解析assignments的结果
     */
    private static Map<String, Object> parseAssignments(String part) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String seg : splitTopLevel(part, ',')) {
            Map.Entry<String, Object> entry = parseCondition(seg);
            if (entry == null) {
                return null;
            }
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    /**
     * 解析 AND 连接的等值条件。
     *
     * @param where where
     * @return 列名到字面量的映射；含不可解析或非等值条件时返回 空
     */
    private static Map<String, Object> parseAndEquals(String where) {
        Map<String, Object> map = new LinkedHashMap<>();
        // 仅支持 AND 连接的 col = val，任何一段解析失败都不能当作"没有条件"
        for (String seg : splitTopLevel(where, "AND")) {
            Map.Entry<String, Object> entry = parseCondition(seg);
            if (entry == null) {
                return null;
            }
            map.put(entry.getKey(), entry.getValue());
        }
        return map.isEmpty() ? null : map;
    }

    /**
     * 解析单个 {@code 列 = 字面量} 条件。
     *
     * @param seg 单个片段
     * @return 列名与值；不合法返回 空
     */
    private static Map.Entry<String, Object> parseCondition(String seg) {
        if (seg == null || seg.isBlank()) {
            return null;
        }
        int eq = indexOfAssign(seg);
        if (eq < 0 || eq + 1 >= seg.length()) {
            return null;
        }
        String col = unquoteIdent(seg.substring(0, eq).trim());
        if (col == null || !COLUMN_NAME.matcher(col).matches()) {
            return null;
        }
        Object val = parseLiteral(seg.substring(eq + 1).trim());
        if (val == INVALID_LITERAL) {
            return null;
        }
        return new java.util.AbstractMap.SimpleEntry<>(col, val);
    }

    /**
     * 索引的assign
     *
     * @param seg seg
     * @return 索引的assign的结果
     */
    private static int indexOfAssign(String seg) {
        boolean inStr = false;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '\'') {
                if (inStr && i + 1 < seg.length() && seg.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inStr = !inStr;
            } else if (!inStr && c == '=') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 分割top级别
     *
     * @param text 文本
     * @param sep sep
     * @return 分割top级别的结果
     */
    private static List<String> splitTopLevel(String text, char sep) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                cur.append(c);
                if (inStr && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    cur.append(text.charAt(++i));
                } else {
                    inStr = !inStr;
                }
            } else if (!inStr && c == sep) {
                parts.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            parts.add(cur.toString().trim());
        }
        return parts;
    }

    /**
     * 分割top级别
     *
     * @param text 文本
     * @param keyword keyword
     * @return 分割top级别的结果
     */
    private static List<String> splitTopLevel(String text, String keyword) {
        List<String> parts = new ArrayList<>();
        String upper = text.toUpperCase(Locale.ROOT);
        String key = keyword.toUpperCase(Locale.ROOT);
        int start = 0;
        boolean inStr = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (inStr && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inStr = !inStr;
            } else if (!inStr && i + key.length() <= text.length()
                    && upper.startsWith(key, i)
                    && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))
                    && (i + key.length() == text.length()
                    || Character.isWhitespace(text.charAt(i + key.length())))) {
                parts.add(text.substring(start, i).trim());
                i += key.length() - 1;
                start = i + 1;
            }
        }
        parts.add(text.substring(start).trim());
        return parts;
    }

    /**
     * unquoteident
     *
     * @param ident ident
     * @return unquoteIdent的结果
     */
    private static String unquoteIdent(String ident) {
        if (ident == null) {
            return null;
        }
        ident = ident.trim();
        if (ident.length() >= 2 && ident.startsWith("`") && ident.endsWith("`")) {
            return ident.substring(1, ident.length() - 1);
        }
        if (ident.length() >= 2 && ident.startsWith("\"") && ident.endsWith("\"")) {
            return ident.substring(1, ident.length() - 1);
        }
        return ident;
    }

    /**
     * 解析字面量
     *
     * @param raw raw
     * @return 解析字面量的结果
     */
    private static Object parseLiteral(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || "NULL".equalsIgnoreCase(s)) {
            return null;
        }
        if ("TRUE".equalsIgnoreCase(s)) {
            return true;
        }
        if ("FALSE".equalsIgnoreCase(s)) {
            return false;
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        if (s.matches("-?\\d+")) {
            try {
                long v = Long.parseLong(s);
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                    return (int) v;
                }
                return v;
            } catch (NumberFormatException e) {
                // 超出 long 范围的整数字面量按精确十进制保留
                return new java.math.BigInteger(s);
            }
        }
        if (s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?")) {
            return Double.parseDouble(s);
        }
        // 列引用、表达式、函数、子查询等一律不可路由，绝不能当作字符串值写入
        return INVALID_LITERAL;
    }

    /**
     * 第一个
     *
     * @param a a
     * @param b b
     * @return 第一个的结果
     */
    private static String first(String a, String b) {
        return a != null ? a : b;
    }
}
