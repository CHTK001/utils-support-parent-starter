package com.chua.datasource.support.engine;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import io.r2dbc.spi.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R2DBC SQL 执行器，基于 {@link ConnectionFactory} 实现 {@link SqlExecutor}。
 *
 * <p>提供 R2DBC 驱动下的查询/更新/批量执行能力，供响应式引擎内部使用。
 * 该执行器本身为同步接口（阻塞模式），真正的非阻塞执行由 {@link AbstractR2dbcReactorEngine}
 * 的 SQL 方法通过 R2DBC 流式 API 直接完成。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public class R2dbcSqlExecutor implements SqlExecutor {

    /** 结果集列别名 */
    private static final String COUNT_ALIAS = "t";

    /** R2DBC 连接工厂 */
    private final ConnectionFactory factory;

    /** 方言（用于分页） */
    private final Dialect dialect;

    /**
     * 构造 R2DBC SQL 执行器。
     *
     * @param factory  R2DBC 连接工厂
     * @param dialect  方言，可为 null
     */
    public R2dbcSqlExecutor(ConnectionFactory factory, Dialect dialect) {
        this.factory = factory;
        this.dialect = dialect;
    }

    /**
     * 获取 R2DBC 连接工厂。
     *
     * @return 连接工厂
     */
    public ConnectionFactory getFactory() {
        return factory;
    }

    /**
     * 获取方言。
     *
     * @return 方言，未设置返回 null
     */
    public Dialect getDialect() {
        return dialect;
    }

    // ==================== SQL 执行 ====================

    private Connection acquire() {
        Connection conn = Mono.from(factory.create()).block();
        if (conn == null) {
            throw new IllegalStateException("R2DBC 连接获取失败");
        }
        return conn;
    }

    private void release(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public List<Map<String, Object>> query(String sql, Object... params) {
        Connection conn = acquire();
        try {
            Statement stmt = conn.createStatement(sql);
            bindParams(stmt, params);
            return Flux.from(stmt.execute())
                    .flatMap(result -> Flux.from(result.map(this::toMap)))
                    .collectList()
                    .block();
        } finally {
            release(conn);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
        Connection conn = acquire();
        try {
            Statement stmt = conn.createStatement(sql);
            bindParams(stmt, params);
            return (List<T>) Flux.from(stmt.execute())
                    .flatMap(result -> Flux.from(result.map((row, meta) -> toObject(row, rowType))))
                    .collectList()
                    .block();
        } finally {
            release(conn);
        }
    }

    @Override
    public int execute(String sql, Object... params) {
        Connection conn = acquire();
        try {
            Statement stmt = conn.createStatement(sql);
            bindParams(stmt, params);
            Long affected = Flux.from(stmt.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .block();
            return affected != null ? affected.intValue() : 0;
        } finally {
            release(conn);
        }
    }

    @Override
    public int[] batch(String sql, List<Object[]> batchParams) {
        Connection conn = acquire();
        List<Integer> updates = new ArrayList<>();
        try {
            if (batchParams != null) {
                for (Object[] paramArray : batchParams) {
                    Statement stmt = conn.createStatement(sql);
                    bindParams(stmt, paramArray);
                    Long affected = Flux.from(stmt.execute())
                            .flatMap(Result::getRowsUpdated)
                            .reduce(0L, Long::sum)
                            .block();
                    updates.add(affected != null ? affected.intValue() : 0);
                }
            }
        } finally {
            release(conn);
        }
        return updates.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public List<Map<String, Object>> queryPage(String sql, Pagination pagination, Object... params) {
        long total = count(sql, params);
        pagination.setTotal(total);
        String pageSql = buildPageSql(sql, pagination);
        return query(pageSql, params);
    }

    /**
     * 映射行到 Map。
     *
     * @param row       R2DBC 行
     * @param meta      行元数据
     * @return 列名 → 值的 Map
     */
    private Map<String, Object> toMap(Row row, RowMetadata meta) {
        Map<String, Object> rowMap = new LinkedHashMap<>();
        for (ColumnMetadata cm : meta.getColumnMetadatas()) {
            String name = cm.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            rowMap.put(name, row.get(name));
        }
        return rowMap;
    }

    /**
     * 映射行到实体对象。
     *
     * @param row       R2DBC 行
     * @param rowType   目标类型
     * @param <T>       实体类型
     * @return 映射后的对象
     */
    private <T> T toObject(Row row, Class<T> rowType) {
        try {
            T instance = rowType.getDeclaredConstructor().newInstance();
            row.getMetadata().getColumnMetadatas().forEach(cm -> {
                String name = cm.getName();
                if (name == null || name.isEmpty()) {
                    return;
                }
                Object value = row.get(name);
                if (value != null) {
                    setFieldValue(instance, name, value);
                }
            });
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException("映射行到 " + rowType.getName() + " 失败: " + e.getMessage(), e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 绑定参数到预编译语句，索引从 0 开始（R2DBC 约定）。
     *
     * @param stmt   语句
     * @param params 参数
     */
    private static void bindParams(Statement stmt, Object... params) {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            stmt.bind(i, params[i]);
        }
    }

    /**
     * 统计 SQL 结果总数。
     *
     * @param sql    原始 SQL
     * @param params 参数
     * @return 总记录数
     */
    private long count(String sql, Object... params) {
        String countSql = "SELECT COUNT(*) FROM (" + trimSql(sql) + ") " + COUNT_ALIAS;
        List<Map<String, Object>> rows = query(countSql, params);
        if (!rows.isEmpty()) {
            Object val = rows.get(0).values().iterator().next();
            if (val instanceof Number n) {
                return n.longValue();
            }
        }
        return 0L;
    }

    /**
     * 使用方言生成分页 SQL，无方言时直接返回原 SQL。
     *
     * @param sql        原始 SQL
     * @param pagination 分页参数
     * @return 分页 SQL
     */
    private String buildPageSql(String sql, Pagination pagination) {
        if (dialect != null) {
            return dialect.processSql(sql, pagination);
        }
        return sql + " LIMIT " + pagination.getLimit() + " OFFSET " + pagination.getOffset();
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
     * @param instance   目标对象
     * @param columnName 列名
     * @param value      列值
     */
    private static void setFieldValue(Object instance, String columnName, Object value) {
        List<String> candidates = List.of(columnName, toCamelCase(columnName));
        for (String candidate : candidates) {
            Field field = findField(instance.getClass(), candidate);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object converted = Converter.convertIfNecessary(value, field.getType());
                if (converted != null) {
                    field.set(instance, converted);
                }
                return;
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    /**
     * 在类及父类中查找字段。
     *
     * @param clazz 目标类
     * @param name  字段名
     * @return 字段，未找到返回 null
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
     * @param name 原始名称（如 user_name）
     * @return 驼峰命名（如 userName）
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
}