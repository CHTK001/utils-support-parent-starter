package com.chua.datasource.support.engine;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC SQL 执行器，基于 {@link JdbcEngine} 提供的连接执行原生 SQL。
 *
 * <p>作为 {@link JdbcEngine#getExecutor()} 的默认实现，提供以下能力：</p>
 * <ul>
 *   <li>查询 — {@link #query(String, Object...)} 返回 {@code Map} 行</li>
 *   <li>类型化查询 — {@link #query(String, Class, Object...)} 反射映射实体</li>
 *   <li>分页查询 — {@link #queryPage(String, Pagination, Object...)} 基于方言分页</li>
 *   <li>更新 — {@link #execute(String, Object...)} 执行 INSERT/UPDATE/DELETE</li>
 *   <li>批量操作 — {@link #batch(String, List)} 批量执行</li>
 * </ul>
 *
 * <p>所有参数使用 {@code ?} 占位符，通过 PreparedStatement 预编译执行，防止 SQL 注入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JdbcSqlExecutor implements SqlExecutor {

    /**
     * 统计总数子查询的别名
     */
    private static final String COUNT_ALIAS = "t";

    /**
     * 所属 JDBC 引擎，用于获取连接与方言
     */
    private final JdbcEngine engine;

    /**
     * 构造 JDBC SQL 执行器。
     *
     * @param engine 所属 JDBC 引擎，用于获取连接与方言
     */
    public JdbcSqlExecutor(JdbcEngine engine) {
        this.engine = engine;
    }

    @Override
    public List<Map<String, Object>> query(String sql, Object... params) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = engine.getJdbcConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String label = meta.getColumnLabel(i);
                        if (label == null || label.isEmpty()) {
                            label = meta.getColumnName(i);
                        }
                        row.put(label, rs.getObject(i));
                    }
                    result.add(row);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("执行查询失败: " + sql, e);
        }
        return result;
    }

    @Override
    public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
        List<T> result = new ArrayList<>();
        try (Connection conn = engine.getJdbcConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                while (rs.next()) {
                    T instance = ReflectUtils.instantiate(rowType);
                    for (int i = 1; i <= columnCount; i++) {
                        String label = meta.getColumnLabel(i);
                        if (label == null || label.isEmpty()) {
                            label = meta.getColumnName(i);
                        }
                        Object value = rs.getObject(i);
                        if (value != null) {
                            setFieldValue(instance, label, value);
                        }
                    }
                    result.add(instance);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("执行类型化查询失败: " + sql, e);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> queryPage(String sql, Pagination pagination, Object... params) {
        // 先统计总数
        long total = count(sql, params);
        pagination.setTotal(total);

        // 使用方言生成分页 SQL
        String pageSql = buildPageSql(sql, pagination);
        return query(pageSql, params);
    }

    @Override
    public int execute(String sql, Object... params) {
        try (Connection conn = engine.getJdbcConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("执行更新失败: " + sql, e);
        }
    }

    @Override
    public int[] batch(String sql, List<Object[]> batchParams) {
        try (Connection conn = engine.getJdbcConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (batchParams != null) {
                for (Object[] paramArray : batchParams) {
                    bindParams(ps, paramArray);
                    ps.addBatch();
                }
            }
            return ps.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("执行批量操作失败: " + sql, e);
        }
    }

    /**
     * 统计 SQL 结果总数，用于填充分页 total。
     *
     * @param sql    原始 SQL
     * @param params 参数列表
     * @return 总记录数
     */
    private long count(String sql, Object... params) {
        String countSql = "SELECT COUNT(*) FROM (" + trimSql(sql) + ") " + COUNT_ALIAS;
        try (Connection conn = engine.getJdbcConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("统计总数失败: " + countSql, e);
        }
        return 0L;
    }

    /**
     * 使用方言生成分页 SQL，无方言时直接返回原 SQL（由调用方自行截取）。
     *
     * @param sql        原始 SQL
     * @param pagination 分页参数（含 偏移量 / 限制）
     * @return 分页 SQL
     */
    private String buildPageSql(String sql, Pagination pagination) {
        Dialect dialect = engine.getDialect(engine.getDefaultDataSourceName());
        if (dialect != null) {
            return dialect.processSql(sql, pagination);
        }
        return sql;
    }

    /**
     * 去掉 SQL 末尾的分号与空白，便于子查询包装。
     *
     * @param sql 原始 SQL
     * @return 清理后的 SQL
     */
    private static String trimSql(String sql) {
        if (sql == null) {
            return "";
        }
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /**
     * 反射设置对象字段值，兼容原列名与驼峰化列名。
     *
     * <p>字段匹配顺序：精确匹配原列名 → 驼峰化列名（如 {@code user_name} → {@code userName}）。
     * 类型转换复用 {@link Converter}，支持数值、字符串、枚举等类型的自动转换。</p>
     *
     * @param instance   目标对象
     * @param columnName 列名
     * @param value      列值
     */
    private static void setFieldValue(Object instance, String columnName, Object value) {
        Class<?> clazz = instance.getClass();
        // 依次尝试原列名与驼峰化列名
        String[] candidates = {
                columnName,
                toCamelCase(columnName)
        };
        for (String candidate : candidates) {
            try {
                Field field = findField(clazz, candidate);
                if (field == null) {
                    continue;
                }
                field.setAccessible(true);
 // 复用 转换器 完成类型转换
                Object converted = Converter.convertIfNecessary(value, field.getType());
                if (converted != null) {
                    field.set(instance, converted);
                }
                return;
            } catch (IllegalAccessException ignored) {
                // 忽略访问异常，尝试下一个候选
            }
        }
    }

    /**
     * 在类及父类中查找字段。
     *
     * @param clazz 目标类
     * @param name  字段名
     * @return 字段，未找到返回 空
     */
    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 将下划线命名转换为驼峰命名。
     *
     * @param name 原始名称（如 用户_名称）
     * @return 驼峰命名（如 用户名）
     */
    private static String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 绑定参数到预编译语句，索引从 1 开始。
     *
     * @param ps     预编译语句
     * @param params 参数数组
     * @throws SQLException 绑定失败
     */
    private static void bindParams(PreparedStatement ps, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
