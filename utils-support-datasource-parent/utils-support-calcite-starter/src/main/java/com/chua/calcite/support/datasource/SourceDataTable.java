package com.chua.calcite.support.datasource;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.datasource.support.datasource.MutableDataTable;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.datasource.support.engine.FileEngine;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * 将 {@link Engine} 实体表适配为可查询/可写的 {@link MutableDataTable}。
 * <p>
 * 读：通过 Engine Lambda 查询；写：同步内存 dataStores，FileEngine 时按 autoPersist 写回文件。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SourceDataTable extends MutableDataTable {

    /**
     * 底层引擎实例
     */
    private final Engine engine;

    /**
     * 实体类型
     */
    private final Class<?> entityClass;

    /**
     * 解析到的 getter 方法列表
     */
    private final List<Method> getters;

    /**
     * 列类型列表
     */
    private final List<Class<?>> columnTypes;

    /**
     * 解析到的 setter 方法列表
     */
    private final List<Method> setters;

    /**
     * 构造实体数据表。
     *
     * @param name        表名
     * @param engine      引擎实例
     * @param entityClass 实体类型
     */
    public SourceDataTable(String name, Engine engine, Class<?> entityClass) {
        super(name);
        this.engine = engine;
        this.entityClass = entityClass;
        this.getters = resolveGetters(entityClass);
        for (String col : toColumnNames(getters)) {
            addColumn(col);
        }
        this.columnTypes = toColumnTypes(getters);
        this.setters = resolveSetters(entityClass, getters);
    }

    public List<Class<?>> getColumnTypes() {
        return columnTypes;
    }

    /** 获取Engine */
    public Engine getEngine() {
        return engine;
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 获取Data */
    public List<Map<String, Object>> getData() {
        List<Map<String, Object>> rows = queryRows();
        List<Map<String, Object>> data = super.getData();
        data.clear();
        data.addAll(rows);
        return data;
    }

    @Override
    /** 获取Row计算数量 */
    public long getRowCount() {
        return getData().size();
    }

    @Override
    /** 添加Row */
    public void addRow(Map<String, Object> row) {
        super.addRow(row);
        persistSnapshot(super.getData());
    }

    /**
     * 用完整行快照替换表数据（供 Calcite ModifiableTable 写回）。
     */
    public void replaceAllRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> data = super.getData();
        data.clear();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                data.add(new LinkedHashMap<>(row));
            }
        }
        persistSnapshot(data);
    }

    @SuppressWarnings("unchecked")
    /** 查询Rows */
    private List<Map<String, Object>> queryRows() {
        try {
            var results = engine.query((Class<Object>) entityClass).list();
            if (results == null || results.isEmpty()) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> rows = new ArrayList<>(results.size());
            for (Object entity : results) {
                rows.add(entityToRow(entity));
            }
            return rows;
        } catch (Exception e) {
            log.warn("[calcite] SourceDataTable [{}] 查询失败: {}", getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** PersistSnapshot */
    private void persistSnapshot(List<Map<String, Object>> rows) {
        List<Object> entities = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            entities.add(mapToEntity(row));
        }
        String storeName = resolveStoreName();
        if (engine instanceof AbstractEngine abstractEngine) {
            abstractEngine.store(storeName, entities);
            // 兼容实体表名与 load 名
            if (!storeName.equals(getName())) {
                abstractEngine.store(getName(), entities);
            }
        }
        if (engine instanceof FileEngine fileEngine) {
            fileEngine.save(storeName);
            if (!storeName.equals(getName())) {
                fileEngine.save(getName());
            }
        }
        log.debug("[calcite] SourceDataTable [{}] 写回 {} 行 -> {}", getName(), entities.size(), storeName);
    }

    /** 解析StoreName */
    private String resolveStoreName() {
        // AbstractEngine 表名规则：User -> user
        String simple = entityClass.getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (char c : simple.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** MapToEntity */
    private Object mapToEntity(Map<String, Object> row) {
        try {
            Object instance = ReflectUtils.instantiate(entityClass);
            List<String> cols = getColumnNames();
            for (int i = 0; i < cols.size() && i < setters.size(); i++) {
                Method setter = setters.get(i);
                if (setter == null) {
                    continue;
                }
                Object value = row.get(cols.get(i));
                setter.invoke(instance, convertValue(value, setter.getParameterTypes()[0]));
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Map 转实体失败: " + entityClass.getSimpleName(), e);
        }
    }

    /** EntityToRow */
    private Map<String, Object> entityToRow(Object entity) {
        Map<String, Object> row = new LinkedHashMap<>(getters.size());
        for (int i = 0; i < getters.size(); i++) {
            try {
                row.put(getColumnNames().get(i), getters.get(i).invoke(entity));
            } catch (Exception e) {
                row.put(getColumnNames().get(i), null);
            }
        }
        return row;
    }

    /** 解析Getters */
    private static List<Method> resolveGetters(Class<?> entityClass) {
        List<Method> result = new ArrayList<>();
        for (Method method : entityClass.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0
                    || method.getReturnType() == void.class) {
                continue;
            }
            String methodName = method.getName();
            if ("getClass".equals(methodName)) {
                continue;
            }
            if ((methodName.startsWith("get") && methodName.length() > 3)
                    || (methodName.startsWith("is") && methodName.length() > 2)) {
                result.add(method);
            }
        }
        result.sort(Comparator.comparing(Method::getName));
        return Collections.unmodifiableList(result);
    }

    /** 解析Setters */
    private static List<Method> resolveSetters(Class<?> entityClass, List<Method> getters) {
        List<Method> setters = new ArrayList<>(getters.size());
        for (Method getter : getters) {
            String col = getterToColumnName(getter);
            String setterName = "set" + Character.toUpperCase(col.charAt(0)) + col.substring(1);
            Method setter = null;
            for (Method m : entityClass.getMethods()) {
                if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                    setter = m;
                    break;
                }
            }
            setters.add(setter);
        }
        return Collections.unmodifiableList(setters);
    }

    /** ToColumnNames */
    private static List<String> toColumnNames(List<Method> getters) {
        List<String> names = new ArrayList<>(getters.size());
        for (Method getter : getters) {
            names.add(getterToColumnName(getter));
        }
        return names;
    }

    private static List<Class<?>> toColumnTypes(List<Method> getters) {
        List<Class<?>> types = new ArrayList<>(getters.size());
        for (Method getter : getters) {
            types.add(getter.getReturnType());
        }
        return Collections.unmodifiableList(types);
    }

    /** GetterToColumnName */
    private static String getterToColumnName(Method getter) {
        String methodName = getter.getName();
        String prop = methodName.startsWith("is") ? methodName.substring(2) : methodName.substring(3);
        if (prop.isEmpty()) {
            return methodName;
        }
        return Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
    }

    /** 转换Value */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null || targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (value instanceof Number num) {
            if (targetType == Integer.class || targetType == int.class) {
                return num.intValue();
            }
            if (targetType == Long.class || targetType == long.class) {
                return num.longValue();
            }
            if (targetType == Double.class || targetType == double.class) {
                return num.doubleValue();
            }
            if (targetType == Float.class || targetType == float.class) {
                return num.floatValue();
            }
        }
        if (value instanceof String str && !str.isBlank()) {
            if (targetType == Integer.class || targetType == int.class) {
                return Integer.valueOf(str);
            }
            if (targetType == Long.class || targetType == long.class) {
                return Long.valueOf(str);
            }
            if (targetType == Double.class || targetType == double.class) {
                return Double.valueOf(str);
            }
        }
        return value;
    }
}
