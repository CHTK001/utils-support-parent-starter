package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JDBC 引擎基类，提供基于 JDBC 的数据库查询实现。
 *
 * @author CH
 * @since 2024/12/12
 */
public abstract class JdbcEngine extends AbstractEngine {

    @Override
    @SuppressWarnings("unchecked")
    protected <T> List<T> executeNewQuery(String where, Object[] args, Class<T> clazz) {
        List<T> data = getData(clazz);
        if (!data.isEmpty()) {
            if (where == null || where.trim().isEmpty()) {
                return data;
            }
            MemoryWhereParser parser = new MemoryWhereParser();
            List<Object> paramList = (args != null)
                    ? Arrays.asList(args)
                    : Collections.emptyList();
            var predicate = parser.parse(where, paramList);
            return data.stream().filter(predicate).toList();
        }

        List<T> result = new ArrayList<>();
        EngineDataSource<?> dataSource = getDataSource();
        if (dataSource == null) {
            return result;
        }

        Object source = dataSource.getSource();
        if (!(source instanceof DataSource)) {
            return result;
        }

        String tableName = clazz.getSimpleName().toLowerCase();
        StringBuilder fullSql = new StringBuilder("SELECT * FROM ");
        fullSql.append(tableName);
        if (where != null && !where.trim().isEmpty()) {
            fullSql.append(" WHERE ").append(where);
        }

        try (Connection conn = ((DataSource) source).getConnection();
             PreparedStatement ps = conn.prepareStatement(fullSql.toString())) {

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
            }

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (rs.next()) {
                    T instance = clazz.getDeclaredConstructor().newInstance();
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnLabel(i);
                        if (columnName == null || columnName.isEmpty()) {
                            columnName = metaData.getColumnName(i);
                        }
                        Object value = rs.getObject(i);
                        if (value != null) {
                            setFieldValue(instance, columnName, value);
                        }
                    }
                    result.add(instance);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("JDBC query error: " + fullSql, e);
        }

        return result;
    }

    private static <T> void setFieldValue(T instance, String columnName, Object value) {
        try {
            Field field = instance.getClass().getDeclaredField(columnName);
            field.setAccessible(true);
            if (field.getType().isPrimitive() && value == null) {
                return;
            }
            field.set(instance, value);
        } catch (NoSuchFieldException e) {
            try {
                Field field = instance.getClass().getSuperclass().getDeclaredField(columnName);
                field.setAccessible(true);
                if (field.getType().isPrimitive() && value == null) {
                    return;
                }
                field.set(instance, value);
            } catch (Exception ex) {
                // ignore
            }
        } catch (Exception e) {
            // ignore
        }
    }
}
