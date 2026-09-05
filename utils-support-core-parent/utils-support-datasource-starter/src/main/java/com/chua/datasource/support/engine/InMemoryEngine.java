package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.MemorySqlAst.DeletePlan;
import com.chua.datasource.support.engine.MemorySqlAst.DmlPlan;
import com.chua.datasource.support.engine.MemorySqlAst.InsertPlan;
import com.chua.datasource.support.engine.MemorySqlAst.UpdatePlan;
import com.chua.datasource.support.wrapper.EngineDeleteWrapper;
import com.chua.datasource.support.wrapper.EngineQueryWrapper;
import com.chua.datasource.support.wrapper.EngineUpdateWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 内存引擎实现，基于 ConcurrentHashMap 提供表级别的轻量数据存储与查询。
 * <p>
 * 支持二级索引加速等值查询（{@code =} 条件走索引，否则回退全表扫描）。
 * 通过 SPI 注册为 {@code "memory"}，可作为单元测试或无持久化场景下的默认 Engine。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("memory")
public class InMemoryEngine extends AbstractEngine {

    /** indexes */
    private final Map<String, Map<String, Map<Object, List<Object>>>> indexes = new ConcurrentHashMap<>();

    @Override
    /** Store */
    public <T> Engine store(String name, List<T> data) {
        super.store(name, data);
        return this;
    }

    /**
     * 为当前全部已存表构建二级索引，加速后续等值条件查询。
     *
     * @return 当前引擎实例
     */
    public InMemoryEngine index() {
        for (Map.Entry<String, List<?>> e : dataStores.entrySet()) {
            buildIndex(e.getKey(), e.getValue());
        }
        return this;
    }

    /** 构建Index */
    private <T> void buildIndex(String name, List<T> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) data;
        T first = data.get(0);
        Map<String, Map<Object, List<Object>>> tableIdx = indexes.computeIfAbsent(
                name, k -> new ConcurrentHashMap<>());
        tableIdx.clear();
        List<String> fields = getGetters(first.getClass());
        for (Object row : rows) {
            for (String field : fields) {
                Object val = MethodCache.getValue(row, field);
                tableIdx.computeIfAbsent(field, k -> new HashMap<>())
                        .computeIfAbsent(val, k -> new ArrayList<>())
                        .add(row);
            }
        }
    }

    /** 获取Getters */
    private static List<String> getGetters(Class<?> clazz) {
        List<String> fields = new ArrayList<>();
        for (var m : clazz.getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) {
                continue;
            }
            String n = m.getName();
            if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))) {
                fields.add(Character.toLowerCase(n.charAt(3)) + n.substring(4));
            } else if (n.startsWith("is") && n.length() > 2 && Character.isUpperCase(n.charAt(2))) {
                fields.add(Character.toLowerCase(n.charAt(2)) + n.substring(3));
            } else if (!n.startsWith("get") && !n.startsWith("is") && !n.startsWith("set")
                    && !n.equals("hashCode") && !n.equals("toString") && !n.equals("equals")) {
                fields.add(n);
            }
        }
        return fields;
    }

    @Override
    /** 查询 */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new InMemQueryWrapper<>(entityClass);
    }

    @Override
    /** 更新 */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new InMemUpdateWrapper<>(entityClass);
    }

    @Override
    /** 删除 */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new InMemDeleteWrapper<>(entityClass);
    }

    @Override
    /** 执行New查询 */
    protected <T> List<T> executeNewQuery(String where, Object[] args, Class<T> clazz, int limit, int offset) {
        throw new UnsupportedOperationException();
    }

    /**
     * 执行原生 SELECT：SQL 编译为 AST 后在表行引用上求值。
     *
     * @param sql    SELECT 语句（支持 WHERE/ORDER BY/LIMIT/COUNT(*)）
     * @param params ? 绑定参数
     * @return 结果行
     */
    public List<Map<String, Object>> querySql(String sql, Object... params) {
        String table = extractTable(sql);
        List<?> rows = dataStores.getOrDefault(table, Collections.emptyList());
        return new MemorySqlParser().executeQuery(sql, rows, params);
    }

    /**
     * 执行原生 DML：INSERT / UPDATE / DELETE 直接作用于表行引用。
     *
     * @param sql    DML 语句
     * @param params ? 绑定参数
     * @return 影响行数
     */
    public int executeSql(String sql, Object... params) {
        Objects.requireNonNull(sql, "sql must not be null");
        var plan = new MemorySqlParser().parseDml(sql);
        return MemorySqlAst.executeDml(plan,
                java.util.Arrays.asList(params == null ? new Object[0] : params),
                () -> mutableRowsFor(plan.table()));
    }

    /**
     * 获取指定表的可变行引用，表不存在时创建空表挂载。
     *
     * @param table 表名
     * @return 可变行引用列表
     */
    private List<Object> mutableRowsFor(String table) {
        dataStores.computeIfAbsent(table, k -> new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) dataStores.get(table);
        return rows;
    }

    /**
     * 提取 FROM 子句后的表名，供 SELECT 定位数据。
     *
     * @param sql SELECT 语句
     * @return 表名
     * @throws IllegalArgumentException 缺少 FROM 子句时抛出
     */
    private static String extractTable(String sql) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)FROM\\s+([\\w]+)").matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("缺少 FROM 子句: " + sql);
        }
        return m.group(1);
    }

    @SuppressWarnings("unchecked")
    <T> List<T> evaluateQuery(EngineQueryWrapper<T> wrapper) {
        List<T> data = getData(wrapper.getEntityClass());
        if (data.isEmpty()) {
            return data;
        }
        List<Condition> conditions = wrapper.getConditions();
        List<T> result;
        if (conditions.isEmpty()) {
            result = data;
        } else {
            List<T> candidates = tryIndexLookup(wrapper.getEntityClass(), conditions, data);
            if (candidates.isEmpty()) {
                return candidates;
            }
            Predicate<T> predicate = buildPredicate(conditions);
            result = candidates.stream().filter(predicate).toList();
        }
        List<String> orderBys = wrapper.getOrderBys();
        if (!orderBys.isEmpty()) {
            result = new ArrayList<>(result);
            result.sort((a, b) -> compareOrdered(a, b, orderBys));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    /** TryIndexLookup */
    private <T> List<T> tryIndexLookup(Class<T> clazz, List<Condition> conditions, List<T> data) {
        Condition first = conditions.get(0);
        if (!"=".equals(first.getOperator())) {
            return data;
        }
        String field = first.getColumnName();
        String tableName = getTableName(clazz);
        Map<String, Map<Object, List<Object>>> tableIdx = indexes.get(tableName);
        if (tableIdx == null) {
            tableIdx = findIndexForData(data);
        }
        if (tableIdx == null) {
            return data;
        }
        Map<Object, List<Object>> fieldIdx = tableIdx.get(field);
        if (fieldIdx == null) {
            return data;
        }
        List<Object> matched = fieldIdx.getOrDefault(first.getValue(), Collections.emptyList());
        if (matched.isEmpty()) {
            return Collections.emptyList();
        }
        return (List<T>) matched;
    }

    @SuppressWarnings("unchecked")
    /** 查找IndexForData */
    private <T> Map<String, Map<Object, List<Object>>> findIndexForData(List<T> data) {
        for (Map.Entry<String, Map<String, Map<Object, List<Object>>>> e : indexes.entrySet()) {
            for (Map.Entry<String, Map<Object, List<Object>>> fe : e.getValue().entrySet()) {
                for (List<Object> rows : fe.getValue().values()) {
                    if (!rows.isEmpty() && rows.get(0) != null && rows.get(0).getClass() == data.get(0).getClass()) {
                        return e.getValue();
                    }
                }
            }
        }
        return null;
    }

    <T> Page<T> evaluatePage(EngineQueryWrapper<T> wrapper, int pn, int ps) {
        List<T> all = evaluateQuery(wrapper);
        int from = (pn - 1) * ps;
        int to = Math.min(from + ps, all.size());
        if (from >= all.size()) {
            return new Page<>(pn, ps, all.size(), Collections.emptyList());
        }
        return new Page<>(pn, ps, all.size(), all.subList(from, to));
    }

    <T> int evaluateUpdate(EngineUpdateWrapper<T> wrapper) {
        List<T> data = getData(wrapper.getEntityClass());
        if (data.isEmpty()) {
            return 0;
        }
        Predicate<T> predicate = buildPredicate(wrapper.getConditions());
        Map<String, Object> setValues = wrapper.getSetValues();
        if (setValues.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (T item : data) {
            if (predicate.test(item)) {
                for (Map.Entry<String, Object> e : setValues.entrySet()) {
                    MethodCache.setValue(item, e.getKey(), e.getValue());
                }
                count++;
            }
        }
        return count;
    }

    <T> int evaluateDelete(EngineDeleteWrapper<T> wrapper) {
        List<T> data = getData(wrapper.getEntityClass());
        if (data.isEmpty()) {
            return 0;
        }
        Predicate<T> predicate = buildPredicate(wrapper.getConditions());
        int before = data.size();
        List<T> remaining = data.stream().filter(predicate.negate()).toList();
        int removed = before - remaining.size();
        if (removed > 0) {
            for (Map.Entry<String, List<?>> entry : dataStores.entrySet()) {
                if (entry.getValue() == data) {
                    dataStores.put(entry.getKey(), remaining);
                }
            }
        }
        return removed;
    }

    /** 构建Predicate */
    private static <T> Predicate<T> buildPredicate(List<Condition> conditions) {
        Predicate<T> result = t -> true;
        for (Condition c : conditions) {
            result = result.and(toPredicate(c));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /** ToPredicate */
    private static <T> Predicate<T> toPredicate(Condition c) {
        String field = c.getColumnName();
        String op = c.getOperator();
        Object val = c.getValue();
        return switch (op) {
            case "=" -> t -> Objects.equals(MethodCache.getValue(t, field), val);
            case "!=", "<>" -> t -> !Objects.equals(MethodCache.getValue(t, field), val);
            case ">" -> t -> compare((Comparable) MethodCache.getValue(t, field), val) > 0;
            case ">=" -> t -> compare((Comparable) MethodCache.getValue(t, field), val) >= 0;
            case "<" -> t -> compare((Comparable) MethodCache.getValue(t, field), val) < 0;
            case "<=" -> t -> compare((Comparable) MethodCache.getValue(t, field), val) <= 0;
            case "LIKE" -> t -> like(MethodCache.getValue(t, field), val);
            case "IS NULL" -> t -> MethodCache.getValue(t, field) == null;
            case "IS NOT NULL" -> t -> MethodCache.getValue(t, field) != null;
            case "IN" -> t -> val instanceof Collection<?> col
                    && col.contains(MethodCache.getValue(t, field));
            case "NOT IN" -> t -> !(val instanceof Collection<?> col)
                    || !col.contains(MethodCache.getValue(t, field));
            case "BETWEEN" -> {
                Object[] range = (Object[]) val;
                Object start = range[0];
                Object end = range[1];
                yield t -> {
                    Comparable fv = (Comparable) MethodCache.getValue(t, field);
                    return fv != null && compare(fv, start) >= 0 && compare(fv, end) <= 0;
                };
            }
            default -> t -> true;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /** 比较 */
    private static int compare(Comparable fieldVal, Object paramVal) {
        if (fieldVal == null) {
            return -1;
        }
        Object cv = convertToMatch(fieldVal, paramVal);
        return fieldVal.compareTo(cv);
    }

    /** Like */
    private static boolean like(Object fieldVal, Object patternVal) {
        if (fieldVal == null || patternVal == null) {
            return false;
        }
        String val = fieldVal.toString().toLowerCase();
        String pattern = patternVal.toString().replace("%", "").toLowerCase();
        return val.contains(pattern);
    }

    /** 转换ToMatch */
    private static Object convertToMatch(Object fieldValue, Object paramValue) {
        if (fieldValue == null || paramValue == null) {
            return paramValue;
        }
        if (fieldValue.getClass().isInstance(paramValue)) {
            return paramValue;
        }
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
        if (fieldValue instanceof String) {
            return String.valueOf(paramValue);
        }
        return paramValue;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /** 比较Ordered */
    private static <T> int compareOrdered(T a, T b, List<String> orderBys) {
        for (String ob : orderBys) {
            String[] parts = ob.trim().split("\\s+");
            String field = parts[0];
            boolean desc = parts.length > 1 && "DESC".equalsIgnoreCase(parts[1]);
            Object va = MethodCache.getValue(a, field);
            Object vb = MethodCache.getValue(b, field);
            int cmp;
            if (va == null && vb == null) {
                cmp = 0;
            } else if (va == null) {
                cmp = -1;
            } else if (vb == null) {
                cmp = 1;
            } else if (va instanceof Comparable c1 && vb instanceof Comparable c2) {
                cmp = c1.compareTo(c2);
            } else {
                cmp = va.toString().compareTo(vb.toString());
            }
            if (cmp != 0) {
                return desc ? -cmp : cmp;
            }
        }
        return 0;
    }

    private class InMemQueryWrapper<T> extends EngineQueryWrapper<T> {
        InMemQueryWrapper(Class<T> clazz) {
            super(InMemoryEngine.this, clazz);
        }

        @Override
        /** List */
        public List<T> list() {
            return evaluateQuery(this);
        }

        @Override
        /** One */
        public T one() {
            List<T> list = list();
            return list.isEmpty() ? null : list.get(0);
        }

        @Override
        /** Page */
        public Page<T> page(int pn, int ps) {
            return evaluatePage(this, pn, ps);
        }
    }

    private class InMemUpdateWrapper<T> extends EngineUpdateWrapper<T> {
        InMemUpdateWrapper(Class<T> clazz) {
            super(InMemoryEngine.this, clazz);
        }

        @Override
        /** 更新 */
        public int update() {
            return evaluateUpdate(this);
        }
    }

    private class InMemDeleteWrapper<T> extends EngineDeleteWrapper<T> {
        InMemDeleteWrapper(Class<T> clazz) {
            super(InMemoryEngine.this, clazz);
        }

        @Override
        /** 移除 */
        public int remove() {
            return evaluateDelete(this);
        }
    }
}
