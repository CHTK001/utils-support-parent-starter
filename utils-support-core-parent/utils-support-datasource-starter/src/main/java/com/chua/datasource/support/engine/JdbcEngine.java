package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.ProcedureDefinition;
import com.chua.common.support.lang.datasource.dialect.TriggerDefinition;
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
 * @since 4.0.0.42
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

    // ==================== 触发器 / 存储过程获取（方言 SQL + JDBC 执行） ====================

    /**
     * 获取当前方言提供的默认数据源方言实例。
     *
     * @return 方言实例，不可用返回 null
     */
    private Dialect dialect() {
        EngineDataSource<?> ds = getDataSource(getDefaultDataSourceName());
        return ds != null ? ds.getDialect() : null;
    }

    /**
     * 获取默认数据源对应的 JDBC 连接。
     *
     * @return JDBC 连接
     * @throws Exception 数据源缺失或类型不支持时抛出
     */
    protected Connection getJdbcConnection() throws Exception {
        EngineDataSource<?> ds = getDataSource(getDefaultDataSourceName());
        if (ds == null) {
            throw new IllegalStateException("默认数据源未配置");
        }
        Object source = ds.getSource();
        if (source instanceof DataSource dataSource) {
            return dataSource.getConnection();
        }
        throw new IllegalStateException("数据源类型不支持 JDBC 连接获取: "
                + (source != null ? source.getClass().getName() : "null"));
    }

    /**
     * 从结果集中按优先级读取字符串列，某些列不存在时降级读取下一个别名。
     *
     * @param rs     结果集
     * @param labels 候选列名（按优先级排列）
     * @return 列值，均不存在返回 null
     */
    private static String getString(ResultSet rs, String... labels) {
        for (String label : labels) {
            if (label == null) {
                continue;
            }
            try {
                return rs.getString(label);
            } catch (SQLException ignore) {
                // 该列名不存在，尝试下一个
            }
        }
        return null;
    }

    /**
     * 获取默认数据源下的所有触发器定义。
     *
     * @param schema schema 名称，null 表示不限定
     * @return 触发器定义列表
     */
    public List<TriggerDefinition> getTriggers(String schema) {
        Dialect dialect = dialect();
        String sql = dialect != null ? dialect.getTriggerListSql(schema) : null;
        if (sql == null || sql.isEmpty()) {
            throw new UnsupportedOperationException("当前数据库不支持获取触发器: "
                    + (dialect != null ? dialect.protocol() : "unknown"));
        }
        List<TriggerDefinition> result = new ArrayList<>();
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(parseTrigger(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException("获取触发器列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 获取指定触发器的定义。
     *
     * @param triggerName 触发器名
     * @param schema      schema 名称，null 表示不限定
     * @return 触发器定义，未找到返回 null
     */
    public TriggerDefinition getTrigger(String triggerName, String schema) {
        Dialect dialect = dialect();
        String sql = dialect != null ? dialect.getTriggerSql(triggerName, schema) : null;
        if (sql == null || sql.isEmpty()) {
            throw new UnsupportedOperationException("当前数据库不支持获取触发器: "
                    + (dialect != null ? dialect.protocol() : "unknown"));
        }
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return parseTrigger(rs);
            }
        } catch (Exception e) {
            throw new RuntimeException("获取触发器定义失败: " + triggerName, e);
        }
        return null;
    }

    /**
     * 获取默认数据源下的所有存储过程定义。
     *
     * @param schema schema 名称，null 表示不限定
     * @return 存储过程定义列表
     */
    public List<ProcedureDefinition> getProcedures(String schema) {
        Dialect dialect = dialect();
        String sql = dialect != null ? dialect.getProcedureListSql(schema) : null;
        if (sql == null || sql.isEmpty()) {
            throw new UnsupportedOperationException("当前数据库不支持获取存储过程: "
                    + (dialect != null ? dialect.protocol() : "unknown"));
        }
        List<ProcedureDefinition> result = new ArrayList<>();
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(parseProcedure(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException("获取存储过程列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 获取指定存储过程的定义。
     *
     * @param procedureName 存储过程名
     * @param schema        schema 名称，null 表示不限定
     * @return 存储过程定义，未找到返回 null
     */
    public ProcedureDefinition getProcedure(String procedureName, String schema) {
        Dialect dialect = dialect();
        String sql = dialect != null ? dialect.getProcedureSql(procedureName, schema) : null;
        if (sql == null || sql.isEmpty()) {
            throw new UnsupportedOperationException("当前数据库不支持获取存储过程: "
                    + (dialect != null ? dialect.protocol() : "unknown"));
        }
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return parseProcedure(rs);
            }
        } catch (Exception e) {
            throw new RuntimeException("获取存储过程定义失败: " + procedureName, e);
        }
        return null;
    }

    /**
     * 将触发器查询结果行映射为 {@link TriggerDefinition}。
     *
     * @param rs 结果集（已定位到当前行）
     * @return 触发器定义
     * @throws SQLException 读取出错
     */
    private static TriggerDefinition parseTrigger(ResultSet rs) throws SQLException {
        TriggerDefinition def = new TriggerDefinition();
        def.setName(getString(rs, "TRIGGER_NAME"));
        def.setSchema(getString(rs, "TRIGGER_SCHEMA", "TRIGGER_SCHEM"));
        def.setCatalog(getString(rs, "TRIGGER_CAT", "TRIGGER_CATALOG"));
        def.setTableName(getString(rs, "TABLE_NAME", "EVENT_OBJECT_TABLE"));
        def.setTiming(getString(rs, "ACTION_TIMING"));
        def.setEvent(getString(rs, "EVENT_MANIPULATION"));
        def.setBody(getString(rs, "ACTION_STATEMENT"));
        def.setStatus(getString(rs, "STATUS"));
        String orientation = getString(rs, "ACTION_ORIENTATION");
        def.setForEachRow(orientation == null || "ROW".equalsIgnoreCase(orientation));
        // 关联表缺失时从内容提取
        def.fillTableNameFromBody();
        return def;
    }

    /**
     * 将存储过程查询结果行映射为 {@link ProcedureDefinition}。
     *
     * @param rs 结果集（已定位到当前行）
     * @return 存储过程定义
     * @throws SQLException 读取出错
     */
    private static ProcedureDefinition parseProcedure(ResultSet rs) throws SQLException {
        ProcedureDefinition def = new ProcedureDefinition();
        def.setName(getString(rs, "ROUTINE_NAME", "PROCEDURE_NAME"));
        def.setSchema(getString(rs, "ROUTINE_SCHEMA", "PROCEDURE_SCHEM"));
        def.setCatalog(getString(rs, "ROUTINE_CATALOG", "PROCEDURE_CAT"));
        String returnType = getString(rs, "DATA_TYPE", "RETURN_TYPE");
        if (returnType != null && !returnType.isEmpty() && !"null".equalsIgnoreCase(returnType)) {
            def.setReturnType(returnType);
        }
        def.setBody(getString(rs, "ROUTINE_DEFINITION", "BODY"));
        def.setComment(getString(rs, "ROUTINE_COMMENT", "REMARKS"));
        def.setSecurityType(getString(rs, "SECURITY_TYPE"));
        String language = getString(rs, "ROUTINE_BODY", "LANGUAGE");
        if (language != null && !"null".equalsIgnoreCase(language)) {
            def.setLanguage(language);
        }
        def.setStatus(getString(rs, "STATUS"));
        return def;
    }
}
