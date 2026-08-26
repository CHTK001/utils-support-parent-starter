package com.chua.datasource.support.engine;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 词法扫描与行访问工具。
 *
 * @author CH
 * @since 4.0.0.42
 */
final class MemorySqlLex {

    private MemorySqlLex() {
    }

    /**
     * 将 SQL 切分为 token：标识符 / 数字 / '字符串' / 运算符（含 &lt;= &gt;= &lt;&gt;）/ 括号逗号星号问号。
     */
    static final class TokenStream {
        private final List<String> tokens;
        private int pos;

        TokenStream(String sql) {
            this.tokens = tokenize(sql);
        }

        boolean eof() {
            return pos >= tokens.size();
        }

        String peek() {
            if (eof()) {
                throw new IllegalArgumentException("意外的语句结尾");
            }
            return tokens.get(pos);
        }

        String next() {
            if (eof()) {
                throw new IllegalArgumentException("意外的语句结尾");
            }
            return tokens.get(pos++);
        }

        private static List<String> tokenize(String sql) {
            java.util.List<String> out = new java.util.ArrayList<>();
            int i = 0;
            int n = sql.length();
            while (i < n) {
                char c = sql.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    int end = sql.indexOf('\'', i + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException("字符串未闭合");
                    }
                    out.add(sql.substring(i, end + 1));
                    i = end + 1;
                    continue;
                }
                if (c == '<' && i + 1 < n && sql.charAt(i + 1) == '=') {
                    out.add("<=");
                    i += 2;
                    continue;
                }
                if (c == '>' && i + 1 < n && sql.charAt(i + 1) == '=') {
                    out.add(">=");
                    i += 2;
                    continue;
                }
                if (c == '<' && i + 1 < n && sql.charAt(i + 1) == '>') {
                    out.add("<>");
                    i += 2;
                    continue;
                }
                if (c == '(' || c == ')' || c == ',' || c == '*' || c == '?' || c == '='
                        || c == '<' || c == '>') {
                    out.add(String.valueOf(c));
                    i++;
                    continue;
                }
                int start = i;
                while (i < n && !Character.isWhitespace(sql.charAt(i)) && ",()*?=<>".indexOf(sql.charAt(i)) < 0
                        && sql.charAt(i) != '\'') {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("无法识别的字符: " + c);
                }
                out.add(sql.substring(start, i));
            }
            return out;
        }
    }

    /**
     * 行访问器：行可为 Map（列名忽略大小写）或 bean（getter 反射）。
     */
    static final class RowAccessor {

        private RowAccessor() {
        }

        /**
         * 读取行中指定列的值。
         *
         * @param row       行对象
         * @param column    列名
         * @return 值，缺失返回 null
         */
        static Object value(Object row, String column) {
            if (row instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) row;
                Object direct = m.get(column);
                if (direct != null) {
                    return direct;
                }
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (String.valueOf(e.getKey()).equalsIgnoreCase(column)) {
                        return e.getValue();
                    }
                }
                return null;
            }
            try {
                Method getter = row.getClass().getMethod(getterName(column));
                return getter.invoke(row);
            } catch (Exception e) {
                try {
                    Method getter = row.getClass().getMethod("get" + Character.toUpperCase(column.charAt(0)) + column.substring(1));
                    return getter.invoke(row);
                } catch (Exception ex) {
                    return null;
                }
            }
        }

        /**
         * 输出行的全部列。
         *
         * @param row 行对象
         * @return 列名到值的有序映射
         */
        static Map<String, Object> allColumns(Object row) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (row instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) row).entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
            for (Method m : row.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) {
                    continue;
                }
                String n = m.getName();
                try {
                    if (n.startsWith("get") && n.length() > 3) {
                        out.put(Character.toLowerCase(n.charAt(3)) + n.substring(4), m.invoke(row));
                    } else if (n.startsWith("is") && n.length() > 2) {
                        out.put(Character.toLowerCase(n.charAt(2)) + n.substring(3), m.invoke(row));
                    }
                } catch (Exception ignored) {
                    // 单个属性读取失败不影响整体投影
                }
            }
            return out;
        }

        /**
         * 向行写入列值（Map 忽略大小写覆盖；bean 走 setter）。
         *
         * @param row    行对象
         * @param column 列名
         * @param value  值
         * @return 是否写入成功
         */
        static boolean setValue(Object row, String column, Object value) {
            if (row instanceof Map) {
                Map<Object, Object> m = (Map<Object, Object>) (Map<?, ?>) row;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) row).entrySet()) {
                    if (String.valueOf(e.getKey()).equalsIgnoreCase(column)) {
                        m.put(e.getKey(), value);
                        return true;
                    }
                }
                m.put(column, value);
                return true;
            }
            try {
                Method setter = row.getClass().getMethod(
                        "set" + Character.toUpperCase(column.charAt(0)) + column.substring(1),
                        guessType(value));
                setter.invoke(row, value);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private static Class<?> guessType(Object v) {
            if (v == null) {
                return Object.class;
            }
            if (v instanceof Integer) {
                return int.class;
            }
            if (v instanceof Long) {
                return long.class;
            }
            if (v instanceof Double) {
                return double.class;
            }
            if (v instanceof Boolean) {
                return boolean.class;
            }
            return v.getClass();
        }

        /**
         * Map 行转 bean 实例（反射 setter 注入）。
         *
         * @param row     结果行
         * @param rowType 目标类型
         * @param <T>     类型
         * @return 实例
         */
        static <T> T toBean(Map<String, Object> row, Class<T> rowType) {
            try {
                T instance = rowType.getDeclaredConstructor().newInstance();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    setValue(instance, e.getKey(), e.getValue());
                }
                return instance;
            } catch (Exception ex) {
                throw new IllegalStateException("Map 转 bean 失败: " + rowType.getName(), ex);
            }
        }

        private static String getterName(String column) {
            if (column.startsWith("is") && column.length() > 2) {
                return column;
            }
            return "get" + Character.toUpperCase(column.charAt(0)) + column.substring(1);
        }
    }
}
