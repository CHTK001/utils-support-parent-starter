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
 * 将简单 SQL UPDATE 路由到 {@link Engine}（Calcite ModifiableTable 不支持 UPDATE）。
 * <p>
 * 支持形态（MySQL 词法，反引号可选）：
 * <pre>
 * UPDATE [`schema`.]`table` SET `c1`=v1, `c2`=v2 WHERE `c3`=v3 [AND `c4`=v4 ...]
 * </pre>
 * SET/WHERE 值支持：字符串字面量、数字、NULL、布尔。
 * </p>
 *
 * @since 4.0.0.42
 */
@Slf4j
public final class EngineUpdateSqlExecutor {

    /**
     * UPDATE 语句解析正则
     */
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+(?:(?:`([^`]+)`|([A-Za-z_][\\w$]*))\\s*\\.\\s*)?(?:`([^`]+)`|([A-Za-z_][\\w$]*))\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?\\s*$"
    );

    /**
     * SQL UPDATE 关键字
     */
    private static final String SQL_UPDATE_KEYWORD = "UPDATE";

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
     * 若 SQL 为可路由的 UPDATE 则执行并返回影响行数；否则返回 null 交由 Calcite。
     *
     * @param sql 原始 SQL
     * @return 影响行数；不可路由返回 null
     */
    public Integer tryExecute(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.trim();
        if (!trimmed.regionMatches(true, 0, SQL_UPDATE_KEYWORD, 0, SQL_UPDATE_KEYWORD.length())) {
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

        SourceDataTable source = resolveTable(schema, table);
        if (source == null) {
            return null;
        }

        Map<String, Object> sets = parseAssignments(setPart);
        if (sets.isEmpty()) {
            return 0;
        }
        Map<String, Object> wheres = wherePart == null || wherePart.isBlank()
                ? Map.of()
                : parseAndEquals(wherePart);

        return executeUpdate(source, sets, wheres);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int executeUpdate(SourceDataTable source, Map<String, Object> sets, Map<String, Object> wheres) {
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
        log.debug("[calcite] Engine UPDATE {}.{} 影响 {} 行", source.getName(), entityClass.getSimpleName(), rows);
        return rows;
    }

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

    private static Map<String, Object> parseAssignments(String part) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String seg : splitTopLevel(part, ',')) {
            int eq = indexOfAssign(seg);
            if (eq < 0) {
                continue;
            }
            String col = unquoteIdent(seg.substring(0, eq).trim());
            Object val = parseLiteral(seg.substring(eq + 1).trim());
            if (col != null && !col.isEmpty()) {
                map.put(col, val);
            }
        }
        return map;
    }

    private static Map<String, Object> parseAndEquals(String where) {
        Map<String, Object> map = new LinkedHashMap<>();
        // 仅支持 AND 连接的 col = val
        for (String seg : splitTopLevel(where, "AND")) {
            int eq = indexOfAssign(seg);
            if (eq < 0) {
                continue;
            }
            String col = unquoteIdent(seg.substring(0, eq).trim());
            Object val = parseLiteral(seg.substring(eq + 1).trim());
            if (col != null && !col.isEmpty()) {
                map.put(col, val);
            }
        }
        return map;
    }

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
            } catch (NumberFormatException ignored) {
                return s;
            }
        }
        if (s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?")) {
            return Double.parseDouble(s);
        }
        return s;
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }
}
