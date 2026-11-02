package com.chua.datasource.support.engine;
import com.chua.common.support.collection.wrap.Wraps;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存 WHERE 条件解析器。
 * <p>
 * 将 SQL 风格的 WHERE 子句解析为 Java {@link Predicate}。
 * 支持 =、!=、&lt;&gt;、&gt;、&gt;=、&lt;、&lt;=、LIKE、IN、IS [NOT] NULL、BETWEEN，
 * 以及括号分组（AND / OR 嵌套）。
 * </p>
 *
 * <p>所有子句正则均以 {@link Matcher#lookingAt()} 匹配：只要求从剩余文本头部起匹配，
 * 允许其后紧跟 AND / OR 等连接符与下一个条件。曾用 {@code String.matches()}（整串匹配），
 * 使 {@code name LIKE ? AND id = ?} 这类「LIKE / BETWEEN 未位于子句末尾」的输入
 * 直接落到 {@code break}，剩余条件被静默丢弃 —— 谓词退化为恒真，
 * 更新/删除会作用于整表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MemoryWhereParser {

    /**
     * 列名：允许 {@code 表别名.列名} 两段写法（JOIN 场景），捕获组只取列名本身。
     *
     * <p>直接写 {@code \w+} 无法覆盖 {@code u.age} 这类带限定符的列名，
     * 解析到限定列名时会整条条件解析失败；而 {@code MethodCache} 只认属性名，
     * 因此限定符需在匹配阶段丢弃、不进入捕获组。</p>
     */
    private static final String COLUMN = "(?:[\\w$#]+\\.)?([\\w$#]+)";

    /**
     * 二元比较（如 {@code age > ?}），值统一以 ? 占位
     */
    private static final Pattern BINARY_PATTERN =
            Pattern.compile("^" + COLUMN + "\\s*(!=|<>|>=|<=|>|<|=)\\s*\\?");

    /**
     * 数值常量比较（如 1 = 0），用于空集合 IN/NOT IN 渲染出的恒假/恒真条件
     */
    private static final Pattern CONSTANT_PATTERN =
            Pattern.compile("^(\\d+)\\s*(=|!=|<>|>=|<=|>|<)\\s*(\\d+)");

    /**
     * BETWEEN ? AND ?（闭区间）
     */
    private static final Pattern BETWEEN_PATTERN =
            Pattern.compile("^" + COLUMN + "\\s+BETWEEN\\s+\\?\\s+AND\\s+\\?", Pattern.CASE_INSENSITIVE);

    /**
     * LIKE ?
     */
    private static final Pattern LIKE_PATTERN =
            Pattern.compile("^" + COLUMN + "\\s+LIKE\\s+\\?", Pattern.CASE_INSENSITIVE);

    /**
     * IS NOT NULL（必须先于 IS NULL 判定）
     */
    private static final Pattern IS_NOT_NULL_PATTERN =
            Pattern.compile("^" + COLUMN + "\\s+IS\\s+NOT\\s+NULL", Pattern.CASE_INSENSITIVE);

    /**
     * IS NULL
     */
    private static final Pattern IS_NULL_PATTERN =
            Pattern.compile("^" + COLUMN + "\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE);

    /**
     * NOT IN (...)，括号内容为占位符清单（必须先于 IN 判定）
     */
    private static final Pattern NOT_IN_PATTERN =
            Pattern.compile("^" + COLUMN + "\\s+NOT\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    /**
     * IN (...)，括号内容为占位符清单
     */
    private static final Pattern IN_PATTERN =
            Pattern.compile("^" + COLUMN + "\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

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
        return parseConditions(whereClause.trim(), params == null ? Wraps.list() : params, new int[]{0});
    }

    /**
     * 解析条件
     *
     * @param where where
     * @param params 参数
     * @param idxHolder 参数索引持有者（int[0]），子分组递归时共享推进
     * @return 解析条件的结果
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Predicate<T> parseConditions(String where, List<Object> params, int[] idxHolder) {
        Predicate<T> result = t -> true;
        String remaining = where.trim();
        String lastConnector = "AND";

        while (!remaining.isEmpty()) {
            remaining = remaining.trim();
            if (remaining.isEmpty()) {
                break;
            }

            // 处理括号分组：递归解析括号内的条件
            if (remaining.charAt(0) == '(') {
                int depth = 1;
                int end = 1;
                while (end < remaining.length() && depth > 0) {
                    char ch = remaining.charAt(end);
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                    }
                    end++;
                }
                if (depth != 0) {
                    throw new IllegalArgumentException("WHERE 括号未闭合: " + where);
                }
                String inner = remaining.substring(1, end - 1).trim();
                remaining = remaining.substring(end).trim();
                // 子分组通过共享的 idxHolder 消费参数，天然推进父级索引
                Predicate<T> subPredicate = parseConditions(inner, params, idxHolder);
                result = "OR".equalsIgnoreCase(lastConnector)
                        ? result.or(subPredicate)
                        : result.and(subPredicate);
                continue;
            }

            // BETWEEN a AND b（闭区间，不可比较时不命中而非抛异常）
            Matcher between = BETWEEN_PATTERN.matcher(remaining);
            if (between.lookingAt()) {
                String field = between.group(1);
                Object startVal = nextParam(params, idxHolder, where);
                Object endVal = nextParam(params, idxHolder, where);
                Predicate<T> pred = t -> {
                    Object fv = getFieldValue(t, field);
                    if (!(fv instanceof Comparable c)) {
                        return false;
                    }
                    Object cv1 = convertToMatch(fv, startVal);
                    Object cv2 = convertToMatch(fv, endVal);
                    if (!comparableWith(fv, cv1) || !comparableWith(fv, cv2)) {
                        return false;
                    }
                    return c.compareTo(cv1) >= 0 && c.compareTo(cv2) <= 0;
                };
                result = "OR".equalsIgnoreCase(lastConnector) ? result.or(pred) : result.and(pred);
                remaining = remaining.substring(between.end()).trim();
                continue;
            }

            // LIKE（SQL 标准通配语义：% 任意序列、_ 单字符）
            Matcher like = LIKE_PATTERN.matcher(remaining);
            if (like.lookingAt()) {
                String field = like.group(1);
                Object pattern = nextParam(params, idxHolder, where);
                String patternText = pattern == null ? null : String.valueOf(pattern);
                Predicate<T> pred = t -> {
                    Object fv = getFieldValue(t, field);
                    return fv != null && MemorySqlLex.like(String.valueOf(fv), patternText);
                };
                result = "OR".equalsIgnoreCase(lastConnector) ? result.or(pred) : result.and(pred);
                remaining = remaining.substring(like.end()).trim();
                continue;
            }

            // IS NOT NULL（须先于 IS NULL）
            Matcher isNotNull = IS_NOT_NULL_PATTERN.matcher(remaining);
            if (isNotNull.lookingAt()) {
                String field = isNotNull.group(1);
                Predicate<T> pred = t -> getFieldValue(t, field) != null;
                result = "OR".equalsIgnoreCase(lastConnector) ? result.or(pred) : result.and(pred);
                remaining = remaining.substring(isNotNull.end()).trim();
                continue;
            }

            // IS NULL
            Matcher isNull = IS_NULL_PATTERN.matcher(remaining);
            if (isNull.lookingAt()) {
                String field = isNull.group(1);
                Predicate<T> pred = t -> getFieldValue(t, field) == null;
                result = "OR".equalsIgnoreCase(lastConnector) ? result.or(pred) : result.and(pred);
                remaining = remaining.substring(isNull.end()).trim();
                continue;
            }

            // NOT IN (...)
            Matcher notIn = NOT_IN_PATTERN.matcher(remaining);
            if (notIn.lookingAt()) {
                Collection<Object> values = readInValues(notIn.group(2), params, idxHolder, where);
                String field = notIn.group(1);
                Predicate<T> pred = t -> {
                    Object fv = getFieldValue(t, field);
                    return fv != null && !values.contains(fv);
                };
                result = "OR".equalsIgnoreCase(lastConnector) ? result.or(pred) : result.and(pred);
                remaining = remaining.substring(notIn.end()).trim();
                continue;
            }

            // IN (...)
            Matcher in = IN_PATTERN.matcher(remaining);
            if (in.lookingAt()) {
                Collection<Object> values = readInValues(in.group(2), params, idxHolder, where);
                String field = in.group(1);
                Predicate<T> pred = t -> {
                    Object fv = getFieldValue(t, field);
                    return fv != null && values.contains(fv);
                };
                result = "OR".equalsIgnoreCase(lastConnector) ? result.or(pred) : result.and(pred);
                remaining = remaining.substring(in.end()).trim();
                continue;
            }

            // 数值常量比较（如空集合 IN 渲染出的 1 = 0 / 1 = 1）
            Matcher constant = CONSTANT_PATTERN.matcher(remaining);
            if (constant.lookingAt()) {
                long left = Long.parseLong(constant.group(1));
                String cop = constant.group(2);
                long right = Long.parseLong(constant.group(3));
                boolean constResult = switch (cop) {
                    case "=" -> left == right;
                    case "!=", "<>" -> left != right;
                    case ">" -> left > right;
                    case ">=" -> left >= right;
                    case "<" -> left < right;
                    case "<=" -> left <= right;
                    default -> true;
                };
                Predicate<T> pred = t -> constResult;
                result = "OR".equalsIgnoreCase(lastConnector) ? result.or(pred) : result.and(pred);
                remaining = remaining.substring(constant.end()).trim();
                continue;
            }

            // 二元操作符 (col op ?)
            Matcher binary = BINARY_PATTERN.matcher(remaining);
            if (binary.lookingAt()) {
                String field = binary.group(1);
                String op = binary.group(2);
                Object val = nextParam(params, idxHolder, where);
                Predicate<T> pred = t -> {
                    Object fv = getFieldValue(t, field);
                    Object cv = convertToMatch(fv, val);
                    return switch (op) {
                        case "=" -> fv != null && fv.equals(cv);
                        // SQL 语义：NULL 参与比较结果为 unknown，不命中
                        case "!=", "<>" -> fv != null && !fv.equals(cv);
                        case ">" -> comparableWith(fv, cv) && ((Comparable) fv).compareTo(cv) > 0;
                        case ">=" -> comparableWith(fv, cv) && ((Comparable) fv).compareTo(cv) >= 0;
                        case "<" -> comparableWith(fv, cv) && ((Comparable) fv).compareTo(cv) < 0;
                        case "<=" -> comparableWith(fv, cv) && ((Comparable) fv).compareTo(cv) <= 0;
                        default -> true;
                    };
                };
                result = "OR".equalsIgnoreCase(lastConnector) ? result.or(pred) : result.and(pred);
                remaining = remaining.substring(binary.end()).trim();
                continue;
            }

            // AND / OR（要求独立词，避免把 ANDROID 之类标识符误当连接符）
            if (startsWithKeyword(remaining, "AND")) {
                lastConnector = "AND";
                remaining = remaining.substring(3).trim();
                continue;
            }
            if (startsWithKeyword(remaining, "OR")) {
                lastConnector = "OR";
                remaining = remaining.substring(2).trim();
                continue;
            }

            throw new IllegalArgumentException("无法解析的 WHERE 片段: " + remaining);
        }
        return result;
    }

    /**
     * 判断剩余文本是否以指定关键字开头（忽略大小写，且关键字后须为词边界）。
     *
     * @param text    剩余文本
     * @param keyword 关键字
     * @return 是否为独立关键字
     */
    private static boolean startsWithKeyword(String text, String keyword) {
        if (text.length() < keyword.length()
                || !text.regionMatches(true, 0, keyword, 0, keyword.length())) {
            return false;
        }
        return text.length() == keyword.length()
                || !Character.isJavaIdentifierPart(text.charAt(keyword.length()));
    }

    /**
     * 读取 IN / NOT IN 括号内的占位符个数并依次消费参数。
     *
     * @param placeholders 括号内文本（逗号分隔的 ? 清单）
     * @param params       参数列表
     * @param idxHolder    参数索引持有者
     * @param where        原始 WHERE 文本（用于异常定位）
     * @return 绑定值集合
     */
    private static Collection<Object> readInValues(String placeholders, List<Object> params,
                                                   int[] idxHolder, String where) {
        Collection<Object> values = Wraps.list();
        if (placeholders == null || placeholders.isBlank()) {
            return values;
        }
        for (String token : placeholders.split(",")) {
            if (!"?".equals(token.trim())) {
                continue;
            }
            values.add(nextParam(params, idxHolder, where));
        }
        return values;
    }

    /**
     * 取下一个绑定参数。
     *
     * @param params    参数列表
     * @param idxHolder 参数索引持有者
     * @param where     原始 WHERE 文本（用于异常定位）
     * @return 参数值
     * @throws IllegalStateException 占位符多于绑定参数时抛出
     */
    private static Object nextParam(List<Object> params, int[] idxHolder, String where) {
        if (idxHolder[0] >= params.size()) {
            throw new IllegalStateException("WHERE 占位符数量超过参数个数: " + where
                    + "，参数个数=" + params.size());
        }
        return params.get(idxHolder[0]++);
    }

    /**
     * 判断字段值与该候选值是否可直接比较。
     *
     * <p>只有同类型（或同为数值）才交给 {@link Comparable#compareTo}：
     * 否则诸如 {@code Integer.compareTo("abc")} 会抛 {@link ClassCastException}，
     * 让一条类型不匹配的查询把整个查询请求打成异常。</p>
     *
     * @param fieldValue 字段值
     * @param candidate  转换后的候选值
     * @return 可以比较返回 true
     */
    private static boolean comparableWith(Object fieldValue, Object candidate) {
        if (!(fieldValue instanceof Comparable) || candidate == null) {
            return false;
        }
        if (fieldValue instanceof Number && candidate instanceof Number) {
            return true;
        }
        return fieldValue.getClass().isInstance(candidate);
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
            } catch (NumberFormatException e) {
                log.debug("[datasource] WHERE 条件数值转换失败，回退字符串比较: {}", e.getMessage());
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
