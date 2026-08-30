package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.ProcedureDefinition;
import com.chua.common.support.lang.datasource.dialect.TriggerDefinition;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.StringUtils;
import com.chua.datasource.support.index.IndexManager;
import com.chua.datasource.support.meta.JdbcMetaData;
import com.chua.datasource.support.user.DataSourceAware;
import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
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
 * @since 4.0.0.42
 */
public abstract class JdbcEngine extends AbstractEngine {

    @Override
    @SuppressWarnings("unchecked")
    /** 执行New查询 */
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

        String tableName = resolveTableName(clazz);
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
                    // 使用反射无参构造实例化，避免 MethodHandle 对部分类的访问限制
                    Constructor<?> constructor = clazz.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    T instance = (T) constructor.newInstance();
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

    /** 设置FieldValue */
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

    // ==================== 执行器 / 方言 ====================

    /**
     * 获取指定数据源的 SQL 执行器，非 JDBC 数据源返回 null。
     *
     * @param n 数据源名称
     * @return SQL 执行器
     */
    @Override
    public SqlExecutor getExecutor(String n) {
        EngineDataSource<?> ds = getDataSource(n);
        if (ds == null || !(ds.getSource() instanceof DataSource)) {
            return null;
        }
        return new JdbcSqlExecutor(this);
    }

    /**
     * 获取默认数据源的 SQL 执行器，非 JDBC 数据源返回 null。
     *
     * @return SQL 执行器
     */
    @Override
    public SqlExecutor getExecutor() {
        return getExecutor(getDefaultDataSourceName());
    }

    /**
     * 获取指定数据源的方言。
     *
     * @param n 数据源名称
     * @return 方言实例，数据源不存在或非 SQL 数据源返回 null
     */
    @Override
    public Dialect getDialect(String n) {
        EngineDataSource<?> ds = getDataSource(n);
        return ds != null ? ds.getDialect() : null;
    }

    /**
     * 获取元数据操作入口，按默认数据源的协议自动加载对应的 MetaData 实现。
     *
     * <p>优先尝试通过 SPI 按 {@link Dialect#protocol()} 协议名查找注册的 MetaData 实现类，
     * 例如协议为 {@code "mysql"} 时加载 {@code MysqlMetaData}；
     * 未找到对应实现时回退到 {@link JdbcMetaData}。</p>
     *
     * @return 元数据操作接口
     */
    @Override
    public MetaData meta() {
        Dialect d = dialect();
        String protocol = d != null ? d.protocol() : null;
        if (protocol != null) {
            try {
                MetaData md = ServiceProvider.of(MetaData.class).getNewExtension(protocol, this);
                if (md instanceof JdbcMetaData jdbcMd) {
                    jdbcMd.setDataSource(getJdbcDataSource());
                }
                if (md != null) return md;
            } catch (Exception ignored) {
            }
        }
        return super.meta();
    }

    /**
     * 获取默认数据源的 JDBC DataSource，失败返回 null。
     */
    protected javax.sql.DataSource getJdbcDataSource() {
        try {
            java.sql.Connection conn = getJdbcConnection();
            return (javax.sql.DataSource) conn.unwrap(javax.sql.DataSource.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 更新 / 删除（真实 JDBC 执行） ====================

    /**
     * 基于 JDBC 执行更新操作，生成 UPDATE 语句。
     *
     * <p>表名取实体类简单名的小写形式，与 {@link #executeNewQuery} 保持一致。</p>
     *
     * @param sql 更新 SQL 信息
     * @param <T> 实体类型
     * @return 受影响行数
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        // 与查询使用相同的表名（实体类简单名小写）
        String tableName = entityTableName(sql.entityClass());
        StringBuilder sqlBuilder = new StringBuilder("UPDATE ")
                .append(tableName)
                .append(" SET ")
                .append(sql.setClause());
        if (sql.hasWhere()) {
            sqlBuilder.append(" WHERE ").append(sql.whereClause());
        }
        String updateSql = sqlBuilder.toString();
        try (Connection conn = getJdbcConnection();
             PreparedStatement ps = conn.prepareStatement(updateSql)) {
            bindParams(ps, sql.params());
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("执行更新失败: " + updateSql, e);
        }
    }

    /**
     * 基于 JDBC 执行删除操作，生成 DELETE 语句。
     *
     * <p>表名取实体类简单名的小写形式，与 {@link #executeNewQuery} 保持一致。</p>
     *
     * @param sql 删除 SQL 信息
     * @param <T> 实体类型
     * @return 受影响行数
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        // 与查询使用相同的表名（实体类简单名小写）
        String tableName = entityTableName(sql.entityClass());
        StringBuilder sqlBuilder = new StringBuilder("DELETE FROM ").append(tableName);
        if (sql.hasWhere()) {
            sqlBuilder.append(" WHERE ").append(sql.whereClause());
        }
        String deleteSql = sqlBuilder.toString();
        try (Connection conn = getJdbcConnection();
             PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            bindParams(ps, sql.params());
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("执行删除失败: " + deleteSql, e);
        }
    }

    /**
     * 将实体类解析为表名，优先读取 {@link com.chua.datasource.support.annotation.TableName}
     * 注解，未标注时驼峰转下划线，与 {@link #executeNewQuery} 的表名策略一致。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 表名
     */
    private static <T> String entityTableName(Class<T> entityClass) {
        return resolveTableName(entityClass);
    }

    /**
     * 绑定参数到预编译语句，索引从 1 开始。
     *
     * @param ps     预编译语句
     * @param params 参数列表
     * @throws SQLException 绑定失败
     */
    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    // ==================== 触发器 / 存储过程获取（方言 SQL + JDBC 执行） ====================

    /**
     * 获取当前方言提供的默认数据源方言实例。
     *
     * @return 方言实例，数据源未配置或不可用时返回 null
     */
    private Dialect dialect() {
        String name = getDefaultDataSourceName();
        if (name == null) {
            return null;
        }
        EngineDataSource<?> ds = getDataSource(name);
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

    // ==================== SPI 能力入口 ====================

    /**
     * 获取用户管理器入口，通过 SPI 按当前方言协议加载实现。
     */
    public UserManager user() {
        return resolveManager(UserManager.class);
    }

    /**
     * 获取索引管理器入口，通过 SPI 按当前方言协议加载实现。
     */
    public IndexManager index() {
        return resolveManager(IndexManager.class);
    }

    private String currentDialectProtocol() {
        Dialect d = dialect();
        return d != null ? d.protocol() : "unknown";
    }

    @SuppressWarnings("unchecked")
    private <T> T resolveManager(Class<T> clazz) {
        try {
            String type = currentDialectProtocol();
            ServiceProvider<T> provider = ServiceProvider.of(clazz);
            T ext = provider.getExtension(type);
            if (ext == null) return null;
            if (ext instanceof DataSourceAware aware) {
                javax.sql.DataSource ds = getJdbcDataSource();
                if (ds != null) aware.setDataSource(ds);
            }
            return ext;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 数据库管理 ====================

    /**
     * 创建数据库（如果不存在）。
     * <p>使用当前默认数据源的连接执行 {@code CREATE DATABASE IF NOT EXISTS} 语句。</p>
     * <p>此为基础实现，各数据库子类可重写以支持特定语法（如字符集、排序规则）。</p>
     *
     * @param dbName 数据库名称
     * @return true 创建成功或已存在
     */
    public boolean createDatabase(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalArgumentException("数据库名不能为空");
        }
        String sql = "CREATE DATABASE IF NOT EXISTS `" + escapeIdentifier(dbName) + "`"
                + " DEFAULT CHARACTER SET utf8mb4"
                + " COLLATE utf8mb4_unicode_ci";
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建数据库失败: " + dbName, e);
        }
    }

    /**
     * 检查数据库是否存在。
     *
     * @param dbName 数据库名称
     * @return true 存在
     */
    public boolean databaseExists(String dbName) {
        if (dbName == null || dbName.isBlank()) return false;
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA "
                             + "WHERE SCHEMA_NAME = '" + escapeString(dbName) + "'")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 列出所有数据库。
     *
     * @return 数据库名列表
     */
    public List<String> listDatabases() {
        List<String> result = new ArrayList<>();
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new RuntimeException("列出数据库失败", e);
        }
        return result;
    }

    private static String escapeIdentifier(String name) {
        return "`" + StringUtils.replace(name, "`", "``") + "`";
    }

    private static String escapeString(String s) {
        if (StringUtils.isEmpty(s)) return "";
        return StringUtils.replace(s, "'", "''");
    }
}
