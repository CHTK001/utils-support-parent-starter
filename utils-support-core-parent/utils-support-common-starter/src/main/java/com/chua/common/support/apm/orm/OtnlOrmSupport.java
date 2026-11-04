package com.chua.common.support.apm.orm;

import com.chua.common.support.lang.datasource.engine.wrapper.Condition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Otnl 引擎 ORM 通道的内存求值器。
 * <p>
 * {@link com.chua.common.support.apm.OtnlEngine#dataset(Class)} 给出实体行，
 * 本类负责把 {@link Condition} 条件树、排序串与分页参数作用在这些行上 ——
 * 于是「可观测数据」与「数据库表」共用同一套 Lambda 链式写法，
 * 差异只留在取数那一层（HTTP GraphQL 还是 SQL）。
 * </p>
 * <h3>列名到属性</h3>
 * <p>
 * 条件里的列名是 {@code AbstractLambdaWrapper} 解析出来的下划线形式
 * （{@code errorRate} → {@code error_rate}）。求值时先还原成驼峰，
 * 再按 {@code 同名访问器 → getXxx → isXxx → 字段} 的顺序取值，
 * 因此 record 与普通 POJO 都能直接当实体。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class OtnlOrmSupport {

    /**
     * 访问器缓存：实体类 → (列名 → 访问方法)，不存在时存 {@link #NO_ACCESSOR}
     */
    private static final Map<Class<?>, Map<String, Method>> ACCESSORS = new ConcurrentHashMap<>();

    /**
     * 字段缓存：实体类 → (列名 → 字段)
     */
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new ConcurrentHashMap<>();

    /**
     * 访问器缺失标记（ConcurrentHashMap 不允许 null 值）
     */
    private static final Method NO_ACCESSOR;

    static {
        try {
            NO_ACCESSOR = OtnlOrmSupport.class.getDeclaredMethod("noAccessor");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private OtnlOrmSupport() {
    }

    /**
     * 占位方法，仅用于标记「该列没有访问器」
     */
    private static void noAccessor() {
        // 占位
    }

    /**
     * 判断单行是否命中全部条件（条件之间 AND，嵌套条件按自身算子）
     *
     * @param row        实体行
     * @param conditions 条件列表
     * @return 命中返回 true
     */
    public static boolean matches(Object row, List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Condition condition : conditions) {
            if (!match(row, condition)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断单行是否命中单个条件
     *
     * @param row       实体行
     * @param condition 条件
     * @return 命中返回 true
     */
    private static boolean match(Object row, Condition condition) {
        if (condition == null) {
            return true;
        }
        if (condition.isNested()) {
            return matchNested(row, condition);
        }
        String column = condition.getColumnName();
        if (column == null || column.isBlank()) {
            return true;
        }
        Object actual = value(row, column);
        return compare(actual, condition.getOperator(), condition.getValue());
    }

    /**
     * 嵌套条件按 AND / OR 合并
     *
     * @param row       实体行
     * @param condition 嵌套条件
     * @return 命中返回 true
     */
    private static boolean matchNested(Object row, Condition condition) {
        List<Condition> children = condition.getNested();
        if (children == null || children.isEmpty()) {
            return true;
        }
        boolean or = "OR".equalsIgnoreCase(condition.getNestedOperator());
        for (Condition child : children) {
            boolean hit = match(row, child);
            if (or && hit) {
                return true;
            }
            if (!or && !hit) {
                return false;
            }
        }
        return !or;
    }

    /**
     * 按算子比较
     *
     * @param actual   实际值
     * @param operator 算子
     * @param expected 期望值
     * @return 命中返回 true
     */
    private static boolean compare(Object actual, String operator, Object expected) {
        String op = operator == null ? "=" : operator.trim().toUpperCase(Locale.ROOT);
        return switch (op) {
            case "=" -> equalsValue(actual, expected);
            case "!=", "<>" -> !equalsValue(actual, expected);
            case ">" -> sign(actual, expected) > 0;
            case ">=" -> sign(actual, expected) >= 0;
            case "<" -> sign(actual, expected) < 0;
            case "<=" -> sign(actual, expected) <= 0;
            case "LIKE" -> like(actual, expected);
            case "IN" -> in(actual, expected);
            case "NOT IN" -> !in(actual, expected);
            case "IS NULL" -> actual == null;
            case "IS NOT NULL" -> actual != null;
            case "BETWEEN" -> between(actual, expected);
            default -> equalsValue(actual, expected);
        };
    }

    /**
     * 等值比较：数值按大小比，字符串按文本比，枚举按名称比
     *
     * @param actual   实际值
     * @param expected 期望值
     * @return 相等返回 true
     */
    private static boolean equalsValue(Object actual, Object expected) {
        if (actual == null) {
            return expected == null;
        }
        if (expected == null) {
            return false;
        }
        if (actual instanceof Number || expected instanceof Number) {
            Double left = toDouble(actual);
            Double right = toDouble(expected);
            if (left != null && right != null) {
                return Double.compare(left, right) == 0;
            }
        }
        if (actual instanceof Boolean || expected instanceof Boolean) {
            return Boolean.parseBoolean(String.valueOf(actual)) == Boolean.parseBoolean(String.valueOf(expected));
        }
        if (actual instanceof Enum<?> e) {
            return e.name().equalsIgnoreCase(String.valueOf(expected));
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    /**
     * 大小比较：数值按大小，其余按文本（null 视为最小）
     *
     * @param actual   实际值
     * @param expected 期望值
     * @return 负数 / 0 / 正数
     */
    private static int sign(Object actual, Object expected) {
        if (actual == null && expected == null) {
            return 0;
        }
        if (actual == null) {
            return -1;
        }
        if (expected == null) {
            return 1;
        }
        Double left = toDouble(actual);
        Double right = toDouble(expected);
        if (left != null && right != null) {
            return Double.compare(left, right);
        }
        return String.valueOf(actual).compareTo(String.valueOf(expected));
    }

    /**
     * LIKE：{@code %} 对应任意串、{@code _} 对应单字符，大小写不敏感
     *
     * @param actual   实际值
     * @param expected 模式
     * @return 命中返回 true
     */
    private static boolean like(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        String pattern = String.valueOf(expected);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            switch (ch) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(String.valueOf(actual)).matches();
    }

    /**
     * IN：期望值为集合或数组
     *
     * @param actual   实际值
     * @param expected 期望集合
     * @return 命中返回 true
     */
    private static boolean in(Object actual, Object expected) {
        for (Object candidate : iterable(expected)) {
            if (equalsValue(actual, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * BETWEEN：期望值为 {@code [start, end]} 二元组
     *
     * @param actual   实际值
     * @param expected 期望区间
     * @return 落在闭区间内返回 true
     */
    private static boolean between(Object actual, Object expected) {
        List<Object> bounds = iterable(expected);
        if (bounds.size() < 2) {
            return false;
        }
        return sign(actual, bounds.get(0)) >= 0 && sign(actual, bounds.get(1)) <= 0;
    }

    /**
     * 展开集合 / 数组 / 单个值为列表
     *
     * @param value 值
     * @return 列表（非 null）
     */
    private static List<Object> iterable(Object value) {
        List<Object> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof Iterable<?> it) {
            for (Object item : it) {
                result.add(item);
            }
            return result;
        }
        if (value instanceof Object[] array) {
            for (Object item : array) {
                result.add(item);
            }
            return result;
        }
        result.add(value);
        return result;
    }

    /**
     * 数值归一：任何可解析为数字的输入转 double，否则 null
     *
     * @param value 值
     * @return 数值，不可解析返回 null
     */
    private static Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1D : 0D;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text).doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读取实体某列的值
     *
     * @param row    实体行
     * @param column 列名（下划线或驼峰均可）
     * @return 值，取不到返回 null
     */
    public static Object value(Object row, String column) {
        if (row == null || column == null || column.isBlank()) {
            return null;
        }
        String property = toCamel(column);
        Method accessor = accessor(row.getClass(), column, property);
        if (accessor != null) {
            try {
                return accessor.invoke(row);
            } catch (Exception e) {
                return null;
            }
        }
        Field field = field(row.getClass(), column, property);
        if (field != null) {
            try {
                return field.get(row);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 解析访问器：记录组件 {@code name()} 优先，其次 getXxx / isXxx
     *
     * @param type     实体类型
     * @param column   列名
     * @param property 驼峰属性名
     * @return 访问方法，不存在返回 null
     */
    private static Method accessor(Class<?> type, String column, String property) {
        Map<String, Method> cache = ACCESSORS.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        Method cached = cache.get(column);
        if (cached != null) {
            return cached == NO_ACCESSOR ? null : cached;
        }
        Method found = findAccessor(type, property);
        cache.put(column, found == null ? NO_ACCESSOR : found);
        return found;
    }

    /**
     * 按名字查找无参方法
     *
     * @param type     实体类型
     * @param property 驼峰属性名
     * @return 方法，不存在返回 null
     */
    private static Method findAccessor(Class<?> type, String property) {
        String capitalized = property.isEmpty() ? property
                : Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (String name : new String[]{property, "get" + capitalized, "is" + capitalized}) {
            try {
                Method method = type.getMethod(name);
                if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // 尝试下一个命名
            }
        }
        return null;
    }

    /**
     * 查找同名字段（私有字段也允许读）
     *
     * @param type     实体类型
     * @param column   列名
     * @param property 驼峰属性名
     * @return 字段，不存在返回 null
     */
    private static Field field(Class<?> type, String column, String property) {
        Map<String, Field> cache = FIELDS.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        Field cached = cache.get(column);
        if (cached != null) {
            return cached;
        }
        Field found = null;
        Class<?> current = type;
        while (current != null && current != Object.class && found == null) {
            try {
                found = current.getDeclaredField(property);
                found.setAccessible(true);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        if (found != null) {
            cache.put(column, found);
        }
        return found;
    }

    /**
     * 下划线列名 → 驼峰属性名
     *
     * @param column 列名
     * @return 驼峰名
     */
    public static String toCamel(String column) {
        if (column.indexOf('_') < 0 && column.indexOf('.') < 0) {
            return column;
        }
        String source = column.substring(Math.max(column.lastIndexOf('_'), column.lastIndexOf('.')) + 1);
        StringBuilder sb = new StringBuilder(source.length());
        boolean upper = false;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '_') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(ch) : ch);
            upper = false;
        }
        return sb.toString();
    }

    /**
     * 解析排序串（{@code "error_rate DESC"}）为列与方向
     *
     * @param orderBy 排序串
     * @return 二元组：列名、是否降序
     */
    public static OrderKey orderKey(String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            return new OrderKey("", false);
        }
        String text = orderBy.trim();
        boolean desc = text.toUpperCase(Locale.ROOT).endsWith(" DESC");
        if (desc) {
            text = text.substring(0, text.length() - 5).trim();
        } else if (text.toUpperCase(Locale.ROOT).endsWith(" ASC")) {
            text = text.substring(0, text.length() - 4).trim();
        }
        return new OrderKey(text, desc);
    }

    /**
     * 排序键
     *
     * @param column 列名
     * @param desc   是否降序
     */
    public record OrderKey(String column, boolean desc) {
    }
}
