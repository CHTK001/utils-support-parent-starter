package com.chua.datasource.support.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
* 内存 WHERE 条件解析器。
* <p>
* 将 SQL 风格的 WHERE 子句解析为 Java {@link Predicate}。
* 支持 =、!=、&lt;&gt;、&gt;、&gt;=、&lt;、&lt;=、LIKE、入、是否 空、是否 NOT 空、BETWEEN，
* 以及括号分组（和 / 或 嵌套）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class MemoryWhereParser {

    /**
    * 解析 WHERE 子句为 Predicate。
    *
    * @param whereClause WHERE 子句（不含 WHERE 关键字）
    * @param params      参数值列表
    * @param <T>         实体类型
    * @return 谓词
     */
    public <T> Predicate<T> parse(String whereClause, List<Object> params) {
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return t -> true;
        }
        return parseConditions(whereClause.trim(), params, 0);
    }

    @SuppressWarnings("unchecked")
    /**
    * 解析条件
    *
    * @param where where
    * @param params 参数
    * @param startIdx 启动idx
    * @return 解析条件的结果
     */
    private <T> Predicate<T> parseConditions(String where, List<Object> params, int startIdx) {
        Predicate<T> result = t -> true;
        String remaining = where.trim();
        int idx = startIdx;
        String lastConnector = "AND";

        while (!remaining.isEmpty()) {
            remaining = remaining.trim();

            // 处理括号分组：递归解析括号内的条件
            if (remaining.startsWith("(")) {
                int depth = 1;
                int end = 1;
                while (end < remaining.length() && depth > 0) {
                    if (remaining.charAt(end) == '(') {
                        depth++;
                    }
                    else if (remaining.charAt(end) == ')') {
                        depth--;
                    }
                    end++;
                }
                String inner = remaining.substring(1, end - 1).trim();
                remaining = remaining.substring(end).trim();
                Predicate<T> subPredicate = parseConditions(inner, params, idx);
                if ("OR".equalsIgnoreCase(lastConnector)) {
                    result = result.or(subPredicate);
                } else {
                    result = result.and(subPredicate);
                }
                continue;
            }

 // BETWEEN a 和 b
            if (remaining.matches("(?i)^\\w+\\s+BETWEEN\\s+\\?\\s+AND\\s+\\?")) {
                String field = remaining.replaceAll("(?i)\\s+BETWEEN\\s+\\?\\s+AND\\s+\\?.*", "");
                int idxAfter = remaining.indexOf("BETWEEN");
                if (idxAfter < 0) {
                    idxAfter = remaining.indexOf("between");
                }
                int start = remaining.indexOf('?', idxAfter);
                int end = remaining.indexOf("AND", start);
                if (end < 0) {
                    end = remaining.indexOf("and", start);
                }
                int secondQ = remaining.indexOf('?', end);
                if (secondQ > 0) {
                    Object startVal = params.get(idx++);
                    Object endVal = params.get(idx++);
                    Object sv = startVal;
                    Object ev = endVal;
                    Predicate<T> pred = t -> {
                        Object fv = getFieldValue(t, field);
                        if (fv instanceof Comparable c) {
                            Object cv1 = convertToMatch(fv, sv);
                            Object cv2 = convertToMatch(fv, ev);
                            return c.compareTo(cv1) >= 0 && c.compareTo(cv2) <= 0;
                        }
                        return false;
                    };
                    if ("OR".equalsIgnoreCase(lastConnector)) {
                        result = result.or(pred);
                    } else {
                        result = result.and(pred);
                    }
                    remaining = remaining.substring(secondQ + 1).trim();
                    continue;
                }
            }

            // LIKE
            if (remaining.matches("(?i)^\\w+\\s+LIKE\\s+\\?")) {
                String field = remaining.replaceAll("(?i)\\s+LIKE\\s+\\?.*", "");
                int idxLike = remaining.indexOf("LIKE");
                if (idxLike < 0) {
                    idxLike = remaining.indexOf("like");
                }
                int qPos = remaining.indexOf('?', idxLike);
                if (qPos > 0) {
                    String pattern = String.valueOf(params.get(idx++));
                    Predicate<T> pred = t -> {
                        String val = String.valueOf(getFieldValue(t, field));
                        return val.toLowerCase().contains(
                                pattern.replace("%", "").toLowerCase());
                    };
                    if ("OR".equalsIgnoreCase(lastConnector)) {
                        result = result.or(pred);
                    } else {
                        result = result.and(pred);
                    }
                    remaining = remaining.substring(qPos + 1).trim();
                    continue;
                }
            }

 // 是否 NOT 空
            if (remaining.matches("(?i)^\\w+\\s+IS\\s+NOT\\s+NULL\\s*.*")) {
                String field = remaining.replaceAll("(?i)\\s+IS\\s+NOT\\s+NULL.*", "");
                Predicate<T> pred = t -> getFieldValue(t, field) != null;
                if ("OR".equalsIgnoreCase(lastConnector)) {
                    result = result.or(pred);
                } else {
                    result = result.and(pred);
                }
                remaining = remaining.replaceFirst("(?i)^\\w+\\s+IS\\s+NOT\\s+NULL\\s*", "").trim();
                continue;
            }

 // 是否 空
            if (remaining.matches("(?i)^\\w+\\s+IS\\s+NULL\\s*.*")) {
                String field = remaining.replaceAll("(?i)\\s+IS\\s+NULL.*", "");
                Predicate<T> pred = t -> getFieldValue(t, field) == null;
                if ("OR".equalsIgnoreCase(lastConnector)) {
                    result = result.or(pred);
                } else {
                    result = result.and(pred);
                }
                remaining = remaining.replaceFirst("(?i)^\\w+\\s+IS\\s+NULL\\s*", "").trim();
                continue;
            }

            // NOT IN (...)
            if (remaining.matches("(?i)^\\w+\\s+NOT\\s+IN\\s*\\(.*\\)\\s*.*")) {
                String field = remaining.replaceAll("(?i)\\s+NOT\\s+IN\\s*\\(.*", "");
                int inIdx = remaining.indexOf("IN");
                int parenStart = remaining.indexOf('(', inIdx);
                int parenEnd = remaining.indexOf(')', parenStart);
                String placeholders = remaining.substring(parenStart + 1, parenEnd).trim();
                int count = placeholders.split(",").length;
                Collection<Object> values = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    values.add(params.get(idx++));
                }
                Collection<Object> fv = values;
                Predicate<T> pred = t -> !fv.contains(getFieldValue(t, field));
                if ("OR".equalsIgnoreCase(lastConnector)) {
                    result = result.or(pred);
                } else {
                    result = result.and(pred);
                }
                remaining = remaining.substring(parenEnd + 1).trim();
                continue;
            }

            // IN (...)
            if (remaining.matches("(?i)^\\w+\\s+IN\\s*\\(.*\\)\\s*.*")) {
                String field = remaining.replaceAll("(?i)\\s+IN\\s*\\(.*", "");
                int inIdx = remaining.indexOf("IN");
                if (inIdx < 0) {
                    inIdx = remaining.indexOf("in");
                }
                int parenStart = remaining.indexOf('(', inIdx);
                int parenEnd = remaining.indexOf(')', parenStart);
                String placeholders = remaining.substring(parenStart + 1, parenEnd).trim();
                int count = placeholders.split(",").length;
                Collection<Object> values = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    values.add(params.get(idx++));
                }
                Collection<Object> fv = values;
                Predicate<T> pred = t -> fv.contains(getFieldValue(t, field));
                if ("OR".equalsIgnoreCase(lastConnector)) {
                    result = result.or(pred);
                } else {
                    result = result.and(pred);
                }
                remaining = remaining.substring(parenEnd + 1).trim();
                continue;
            }

            // 二元操作符 (col op ?)
            Pattern binPattern = Pattern.compile("^(\\w+)\\s*(!=|<>|>=|<=|>|<|=)\\s*\\?");
            var m = binPattern.matcher(remaining);
            if (m.find()) {
                String field = m.group(1);
                String op = m.group(2);
                Object val = params.get(idx++);
                Object matchedVal = val;
                Predicate<T> pred = t -> {
                    Object fv = getFieldValue(t, field);
                    Object cv = convertToMatch(fv, matchedVal);
                    return switch (op) {
                        case "=" -> fv != null && fv.equals(matchedVal);
                        case "!=", "<>" -> fv != null && !fv.equals(matchedVal);
                        case ">" -> fv instanceof Comparable c && c.compareTo(cv) > 0;
                        case ">=" -> fv instanceof Comparable c && c.compareTo(cv) >= 0;
                        case "<" -> fv instanceof Comparable c && c.compareTo(cv) < 0;
                        case "<=" -> fv instanceof Comparable c && c.compareTo(cv) <= 0;
                        default -> true;
                    };
                };
                if ("OR".equalsIgnoreCase(lastConnector)) {
                    result = result.or(pred);
                } else {
                    result = result.and(pred);
                }
                remaining = remaining.substring(m.end()).trim();
                continue;
            }

 // 和 / 或
            if (remaining.startsWith("AND") || remaining.startsWith("and")) {
                lastConnector = "AND";
                remaining = remaining.substring(3).trim();
                continue;
            }
            if (remaining.startsWith("OR") || remaining.startsWith("or")) {
                lastConnector = "OR";
                remaining = remaining.substring(2).trim();
                continue;
            }

            break;
        }
        return result;
    }

    /**
    * 将参数值转换为与字段值相同的类型，避免 类cast异常。
    * @param fieldValue 字段值
    * @param paramValue 参数值
    * @return 转换转为匹配的结果
     */
    private Object convertToMatch(Object fieldValue, Object paramValue) {
        if (fieldValue == null || paramValue == null) {
            return paramValue;
        }
        if (fieldValue.getClass().isInstance(paramValue)) {
            return paramValue;
        }
 // 数字 类型转换
        if (fieldValue instanceof Number) {
            try {
                String s = String.valueOf(paramValue).trim();
                if (fieldValue instanceof Integer) {
                    return Integer.valueOf(s);
                }
                if (fieldValue instanceof Long) {
                    return Long.valueOf(s);
                }
                if (fieldValue instanceof Double) {
                    return Double.valueOf(s);
                }
                if (fieldValue instanceof Float) {
                    return Float.valueOf(s);
                }
                if (fieldValue instanceof Short) {
                    return Short.valueOf(s);
                }
                if (fieldValue instanceof Byte) {
                    return Byte.valueOf(s);
                }
            } catch (NumberFormatException ignored) {
            }
        }
 // 字符串
        if (fieldValue instanceof String) {
            return String.valueOf(paramValue);
        }
        return paramValue;
    }

    /**
    * 通过反射获取对象字段的值。
    * @param obj obj
    * @param field 字段
    * @return 获取字段值的结果
     */
    private <T> Object getFieldValue(T obj, String field) {
        return MethodCache.getValue(obj, field);
    }
}