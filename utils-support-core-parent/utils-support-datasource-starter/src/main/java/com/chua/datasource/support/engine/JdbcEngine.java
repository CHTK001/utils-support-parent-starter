package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.dialect.ProcedureDefinition;
import com.chua.common.support.lang.datasource.dialect.TriggerDefinition;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.ddl.DdlProvider;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.JoinClause;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.StringUtils;
import com.chua.datasource.support.index.IndexManager;
import com.chua.datasource.support.meta.JdbcMetaData;
import com.chua.datasource.support.permission.PermissionManager;
import com.chua.datasource.support.user.DataSourceAware;
import com.chua.datasource.support.user.UserManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
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
    /**
     * 执行新查询
     *
     * @param where where
     * @param args 参数
     * @param clazz clazz
     * @param limit 限制
     * @param offset 偏移量
     * @return 执行新查询的结果
     */
    protected <T> List<T> executeNewQuery(String where, Object[] args, Class<T> clazz, int limit, int offset) {
        List<T> data = getData(clazz);
        if (!data.isEmpty()) {
            if (where == null || where.trim().isEmpty()) {
                return limitSlice(data, limit, offset);
            }
            MemoryWhereParser parser = new MemoryWhereParser();
            List<Object> paramList = (args != null)
                    ? Arrays.asList(args)
                    : Collections.emptyList();
            var predicate = parser.parse(where, paramList);
            List<T> filtered = data.stream().filter(predicate).toList();
            return limitSlice(filtered, limit, offset);
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
                result.addAll(mapEntities(rs, clazz));
            }
        } catch (Exception e) {
            throw new RuntimeException("JDBC query error: " + fullSql, e);
        }

        return result;
    }

    /**
     * 执行完整查询并将 SELECT 列、GROUP BY、ORDER BY、LIMIT/OFFSET 全部下推到数据库。
     * <p>
     * 引擎内存存储（{@link #store(String, List)}）中存在该实体数据时，回退到内存
     * 过滤/排序/分页链路，与历史行为保持一致；否则构建完整 SQL 走 JDBC 物理执行，
     * 避免把全表数据加载到 JVM 后再内存排序截断。
     * </p>
     *
     * @param sql 查询 SQL 信息
     * @param <T> 实体类型
     * @return 查询结果
     */
    @Override
    protected <T> List<T> executeQueryFull(QuerySql<T> sql) {
        // 有 JOIN 时内存链路无法完成跨表关联，即使实体有内存存储数据也强制走 JDBC 执行
        if (sql.hasJoins()) {
            String fullSql = buildSelectSql(sql, true);
            return executeNativeQuery(fullSql, mergedParams(sql), sql.entityClass());
        }
        // 内存存储中存在数据时走内存链路（executeNewQuery 内存分支 + 内存排序/分页后处理）
        List<T> memoryData = getData(sql.entityClass());
        if (memoryData != null && !memoryData.isEmpty()) {
            return super.executeQueryFull(sql);
        }
        String fullSql = buildSelectSql(sql, true);
        return executeNativeQuery(fullSql, mergedParams(sql), sql.entityClass());
    }

    /**
     * 合并 WHERE 参数与 HAVING 参数为按占位符顺序排列的绑定数组。
     *
     * @param sql 查询 SQL 信息
     * @param <T> 实体类型
     * @return 参数数组（WHERE 参数在前，HAVING 参数在后）
     */
    private static <T> Object[] mergedParams(QuerySql<T> sql) {
        List<Object> all = new ArrayList<>(sql.params());
        if (sql.hasHaving()) {
            all.addAll(sql.havingParams());
        }
        return all.toArray();
    }

    /**
     * 基于 JDBC 执行查询并映射结果为实体列表。
     *
     * @param fullSql 完整 SQL 语句
     * @param args    WHERE 参数（与占位符顺序一致）
     * @param clazz   实体类类型
     * @param <T>     实体类型
     * @return 实体列表，数据源缺失或非 JDBC 时返回空列表
     */
    private <T> List<T> executeNativeQuery(String fullSql, Object[] args, Class<T> clazz) {
        List<T> result = new ArrayList<>();
        EngineDataSource<?> dataSource = getDataSource();
        if (dataSource == null) {
            return result;
        }
        Object source = dataSource.getSource();
        if (!(source instanceof DataSource ds)) {
            return result;
        }
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(fullSql)) {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                result.addAll(mapEntities(rs, clazz));
            }
        } catch (Exception e) {
            throw new RuntimeException("JDBC query error: " + fullSql, e);
        }
        return result;
    }

    /**
     * 根据查询 SQL 信息构建完整 SELECT 语句。
     * <p>子句顺序：SELECT 列 → FROM 表 → JOIN → WHERE → GROUP BY → HAVING → ORDER BY → 分页。
     * 参数值不内联，仍以 {@code ?} 占位符由 PreparedStatement 绑定；
     * 列名、表名与 ON 条件来自 Lambda 解析或调用方编写的受控片段。</p>
     *
     * @param sql                   查询 SQL 信息
     * @param includeOrderAndPaging 是否包含 ORDER BY 与分页子句（COUNT 包装时传 false）
     * @param <T>                   实体类型
     * @return 完整 SELECT 语句
     */
    private <T> String buildSelectSql(QuerySql<T> sql, boolean includeOrderAndPaging) {
        return buildSelectSql(sql, includeOrderAndPaging, null);
    }

    /**
     * 根据查询 SQL 信息构建完整 SELECT 语句（支持投影覆盖）。
     *
     * @param sql                   查询 SQL 信息
     * @param includeOrderAndPaging 是否包含 ORDER BY 与分页子句
     * @param projectionOverride    SELECT 投影覆盖片段（如 COUNT(*)），null 时按常规规则计算投影
     * @param <T>                   实体类型
     * @return 完整 SELECT 语句
     */
    private <T> String buildSelectSql(QuerySql<T> sql, boolean includeOrderAndPaging, String projectionOverride) {
        String tableName = resolveTableName(sql.entityClass());
        StringBuilder sb = new StringBuilder("SELECT ");
        if (projectionOverride != null) {
            sb.append(projectionOverride);
        } else {
            List<String> columns = sql.selectColumns();
            if (columns != null && !columns.isEmpty()) {
                sb.append(String.join(", ", columns));
            } else if (sql.hasGroupBy()) {
                // 有 GROUP BY 但无显式投影列时，只选择分组列，兼容 ONLY_FULL_GROUP_BY 严格模式
                sb.append(sql.groupByColumn());
            } else {
                sb.append('*');
            }
        }
        sb.append(" FROM ").append(tableName);
        // 渲染 JOIN 关联子句：类型 + 目标表[别名] + ON 条件
        if (sql.joins() != null) {
            for (JoinClause join : sql.joins()) {
                sb.append(" ").append(join.joinType()).append(" JOIN ")
                        .append(join.renderTable()).append(" ON ").append(join.onCondition());
            }
        }
        if (sql.hasWhere()) {
            sb.append(" WHERE ").append(sql.whereClause());
        }
        if (sql.hasGroupBy()) {
            sb.append(" GROUP BY ").append(sql.groupByColumn());
        }
        if (sql.hasHaving()) {
            sb.append(" HAVING ").append(sql.havingClause());
        }
        String coreSql = sb.toString();
        if (includeOrderAndPaging && sql.hasOrderBy()) {
            coreSql = coreSql + " ORDER BY " + String.join(", ", sql.orderBys());
        }
        if (includeOrderAndPaging && sql.limit() > 0) {
            coreSql = wrapPagination(coreSql, sql.limit(), sql.offset());
        }
        return coreSql;
    }

    /**
     * 为 SQL 追加分页子句。
     * <p>无方言或方言支持标准 LIMIT 语法（MySQL/PostgreSQL/H2/SQLite 等）时，
     * 直接追加 {@code LIMIT n OFFSET m}，任意偏移量均合法；
     * 方言不支持 LIMIT（SQL Server/Oracle 等）时统一走
     * {@link Dialect#processSql(String, Pagination)} 方言分页，
     * 其翻页模型只有页码/页大小，偏移量非页对齐无法表达，显式抛出异常。</p>
     *
     * @param coreSql 不含分页的 SQL
     * @param limit   返回行数上限
     * @param offset  偏移行数
     * @return 带分页子句的 SQL
     */
    private String wrapPagination(String coreSql, int limit, int offset) {
        Dialect d = dialect();
        if (d == null || d.supportsLimit()) {
            return coreSql + " LIMIT " + limit + " OFFSET " + offset;
        }
        if (offset % limit == 0) {
            Pagination pagination = new Pagination()
                    .setPageNum(offset / limit + 1)
                    .setPageSize(limit);
            return d.processSql(coreSql, pagination);
        }
        throw new UnsupportedOperationException("当前方言（" + d.protocol()
                + "）不支持任意偏移分页，offset 必须是 limit 的整数倍: limit=" + limit + ", offset=" + offset);
    }

    /**
     * 将 JDBC 结果集反射映射为实体列表。
     *
     * @param rs    已执行的结果集
     * @param clazz 实体类类型
     * @param <T>   实体类型
     * @return 实体列表
     * @throws Exception 反射实例化或读取结果集失败时抛出
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> mapEntities(ResultSet rs, Class<T> clazz) throws Exception {
        List<T> result = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        while (rs.next()) {
            // 统一走 ReflectUtils 实例化，避免原生反射
            T instance = ReflectUtils.instantiate(clazz);
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
        return result;
    }

    /**
     * JDBC 引擎支持物理分页：默认数据源为 JDBC 数据源且实体无内存存储数据时返回 true。
     *
     * @param entityClass 实体类类型
     * @return true 表示走 COUNT + 分页 SQL 的物理分页
     */
    @Override
    protected boolean supportsNativePaging(Class<?> entityClass) {
        // 内存存储中存在数据时回退内存分页，保证 store() 语义不被绕过
        List<?> memoryData = getData(entityClass);
        if (memoryData != null && !memoryData.isEmpty()) {
            return false;
        }
        EngineDataSource<?> ds = getDataSource();
        return ds != null && ds.getSource() instanceof DataSource;
    }

    /**
     * 执行 COUNT 查询统计总行数，用于物理分页的 total。
     * <p>将不含 ORDER BY/分页的查询包装为
     * {@code SELECT COUNT(*) FROM (...查询...) t_count} 执行，
     * JOIN 与 HAVING 子句随子查询一并下推，
     * 参数绑定顺序与原始占位符保持一致（WHERE 参数在前，HAVING 参数在后）。</p>
     *
     * @param wrapper 查询包装器（不含分页参数）
     * @param <T>     实体类型
     * @return 总行数
     */
    @Override
    protected <T> long executeCount(LambdaQueryWrapper<T> wrapper) {
        QuerySql<T> sql = wrapper.buildSql();
        String countSql;
        if (sql.hasJoins() && !sql.hasSelect() && !sql.hasGroupBy()) {
            // JOIN 且无显式投影、无分组时不能包 SELECT * 子查询（多表同名列导致
            // Duplicate column name 错误），改为 COUNT(*) 直连 JOIN 语句
            countSql = buildSelectSql(sql, false, "COUNT(*)");
        } else {
            String baseSql = buildSelectSql(sql, false);
            countSql = "SELECT COUNT(*) FROM (" + baseSql + ") t_count";
        }
        try (Connection conn = getJdbcConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            Object[] params = mergedParams(sql);
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new RuntimeException("执行 COUNT 查询失败: " + countSql, e);
        }
    }

    /**
     * 对内存数据执行 限制/偏移量 截取，dialect 不支持物理分页时的兜底实现。
     * @param data 数据
     * @param limit 限制
     * @param offset 偏移量
     * @return 限制slice的结果
     */
    private static <T> List<T> limitSlice(List<T> data, int limit, int offset) {
        if (limit <= 0 && offset <= 0) {
            return data;
        }
        int from = Math.min(offset, data.size());
        int to = limit > 0 ? Math.min(from + limit, data.size()) : data.size();
        if (from >= data.size()) {
            return Collections.emptyList();
        }
        return data.subList(from, to);
    }

    /**
     * 设置字段值
     *
     * @param instance instance
     * @param columnName column名称
     * @param value 值
     * @return 设置字段值的结果
     */
    private static <T> void setFieldValue(T instance, String columnName, Object value) {
        if (columnName == null) {
            return;
        }
        // 列名候选：原名（MySQL 小写场景）→ 全小写（H2/PostgreSQL/Oracle 默认返回大写标签）
        // → 下划线转驼峰（user_name → userName）
        String[] candidates = {
                columnName,
                columnName.toLowerCase(),
                toCamelCase(columnName)
        };
        Class<?> current = instance.getClass();
        while (current != null) {
            for (String candidate : candidates) {
                Field field = ReflectUtils.findField(current, candidate);
                if (field == null) {
                    continue;
                }
                if (field.getType().isPrimitive() && value == null) {
                    return;
                }
                // 复用 转换器 完成类型转换（如 Oracle NUMBER → BigDecimal 赋给 Long 字段）
                Object converted = Converter.convertIfNecessary(value, field.getType());
                if (converted != null) {
                    ReflectUtils.setField(instance, candidate, converted);
                }
                return;
            }
            // 兜底：忽略大小写与下划线的宽松匹配，覆盖 H2/Oracle 大写蛇形标签
            // （如 DEPT_ID → 字段 deptId、TOTAL_SALARY → totalSalary）
            Field loose = findFieldLoose(current, columnName);
            if (loose != null) {
                Object converted = Converter.convertIfNecessary(value, loose.getType());
                if (converted != null) {
                    ReflectUtils.setField(instance, loose.getName(), converted);
                }
            }
            current = current.getSuperclass();
        }
    }

    /**
     * 按忽略大小写与下划线的规则宽松查找字段。
     *
     * @param type       字段所在的类
     * @param columnName 结果集列名
     * @return 匹配的字段，未匹配返回 null
     */
    private static Field findFieldLoose(Class<?> type, String columnName) {
        String normalizedColumn = columnName.replace("_", "").toLowerCase();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            String normalizedField = field.getName().replace("_", "").toLowerCase();
            if (normalizedField.equals(normalizedColumn)) {
                return field;
            }
        }
        return null;
    }

    /**
     * 下划线列名转驼峰属性名（如 {@code user_name} → {@code userName}）。
     *
     * @param name 原始列名
     * @return 驼峰属性名
     */
    private static String toCamelCase(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(ch));
                upperNext = false;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ==================== 执行器 / 方言 ====================

    /**
     * 获取指定数据源的 SQL 执行器，非 JDBC 数据源返回 空。
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
     * 获取默认数据源的 SQL 执行器，非 JDBC 数据源返回 空。
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
     * @return 方言实例，数据源不存在或非 SQL 数据源返回 空
     */
    @Override
    public Dialect getDialect(String n) {
        EngineDataSource<?> ds = getDataSource(n);
        return ds != null ? ds.getDialect() : null;
    }

    /**
     * 获取元数据操作入口，按默认数据源的协议自动加载对应的 meta数据 实现。
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
                if (md != null) {
                    return md;
                }
            } catch (Exception ignored) {
            }
        }
        return super.meta();
    }

    /**
     * 获取默认数据源的 JDBC 数据源，失败返回 空。
     * @return 获取jdbc数据源的结果
     */
    protected javax.sql.DataSource getJdbcDataSource() {
        try {
            com.chua.common.support.lang.datasource.engine.EngineDataSource<?> eds =
                    getDataSource(getDefaultDataSourceName());
            if (eds != null) {
                Object source = eds.getSource();
                if (source instanceof javax.sql.DataSource ds) {
                    return ds;
                }
            }
            // 回退：通过 Connection.unwrap 获取
            java.sql.Connection conn = getJdbcConnection();
            return conn.unwrap(javax.sql.DataSource.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 更新 / 删除（真实 JDBC 执行） ====================

    /**
     * 基于 JDBC 执行更新操作，生成 更新 语句。
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
     * 基于 JDBC 执行删除操作，生成 删除 语句。
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
     * @return 方言实例，数据源未配置或不可用时返回 空
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
     * @return 列值，均不存在返回 空
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
     * @param schema 模式 名称，空 表示不限定
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
     * @param schema      模式 名称，空 表示不限定
     * @return 触发器定义，未找到返回 空
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
     * @param schema 模式 名称，空 表示不限定
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
     * @param schema        模式 名称，空 表示不限定
     * @return 存储过程定义，未找到返回 空
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
     *
     * @return 用户管理器实例，无可用实现时返回 null
     */
    public UserManager user() {
        return resolveManager(UserManager.class);
    }

    /**
     * 获取索引管理器入口，通过 SPI 按当前方言协议加载实现。
     *
     * @return 索引管理器实例，无可用实现时返回 null
     */
    public IndexManager index() {
        return resolveManager(IndexManager.class);
    }

    /**
     * 获取权限管理器入口，通过 SPI 按当前方言协议加载实现。
     *
     * @return 权限管理器实例，无可用实现时返回 null
     */
    public PermissionManager permission() {
        return resolveManager(PermissionManager.class);
    }

    /**
     * 获取当前方言协议名，用于 SPI 能力实现的按协议查找。
     *
     * @return 方言协议名（如 "mysql"），方言缺失时返回 "unknown"
     */
    private String currentDialectProtocol() {
        Dialect d = dialect();
        if (d != null) {
            return d.protocol();
        }
        return "unknown";
    }

    /**
     * 解析Manager。
     *
     * @param clazz 类，不允许为 null
     * @return T 对象
     */
    @SuppressWarnings("unchecked")
    private <T> T resolveManager(Class<T> clazz) {
        try {
            String type = currentDialectProtocol();
            ServiceProvider<T> provider = ServiceProvider.of(clazz);
            T ext = provider.getExtension(type);
            if (ext == null) {
                return null;
            }
            if (ext instanceof DataSourceAware aware) {
                javax.sql.DataSource ds = getJdbcDataSource();
                if (ds != null) {
                    aware.setDataSource(ds);
                }
            }
            return ext;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 数据库管理 ====================

    /**
     * 创建数据库（如果不存在）。
     * <p>建库语句优先通过 SPI 查找支持当前方言协议的
     * {@code DdlProvider} 扩展生成（如 MySQL 的 utf8mb4 语法）；
     * 无匹配扩展时使用内置语法兜底。</p>
     *
     * @param dbName 数据库名称
     * @return true 创建成功或已存在
     */
    public boolean createDatabase(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalArgumentException("数据库名不能为空");
        }
        String sql = resolveCreateDatabaseSql(dbName);
        try (Connection conn = getJdbcConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建数据库失败: " + dbName, e);
        }
    }

    /**
     * 生成建库语句。
     * <p>遍历 {@code ddl-provider} SPI 扩展，取首个支持当前方言协议的实现生成语句；
     * 无匹配扩展时使用内置 MySQL 语法兜底。</p>
     *
     * @param dbName 数据库名称
     * @return 完整建库 DDL 语句
     */
    private String resolveCreateDatabaseSql(String dbName) {
        String protocol = currentDialectProtocol();
        for (DdlProvider provider : ServiceProvider.of(DdlProvider.class)
                .getNewExtensions(DdlProvider.SPI_NAME, this)) {
            if (provider.supports(protocol)) {
                return provider.createDatabase(dbName);
            }
        }
        return "CREATE DATABASE IF NOT EXISTS " + escapeIdentifier(dbName)
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
    }

    /**
     * 检查数据库是否存在。
     *
     * @param dbName 数据库名称
     * @return true 存在
     */
    public boolean databaseExists(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            return false;
        }
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

    /**
     * escapeIdentifier。
     *
     * @param name 名称，不允许为 null
     * @return 结果字符串
     */
    private static String escapeIdentifier(String name) {
        // 调用方负责按需添加引用符，此处仅做安全转义
        return StringUtils.replace(name, "`", "``");
    }

    /**
     * escape字符串。
     * @param s s
     * @return escape字符串的结果
     */
    private static String escapeString(String s) {
        if (StringUtils.isEmpty(s)) {
            return "";
        }
        return StringUtils.replace(s, "'", "''");
    }
}

