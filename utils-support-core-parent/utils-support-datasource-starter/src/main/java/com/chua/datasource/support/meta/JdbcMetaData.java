package com.chua.datasource.support.meta;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.*;
import com.chua.common.support.lang.datasource.meta.model.*;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
* 基于方言的通用 JDBC 元数据入口。
* <p>
* 通过 {@link Dialect} 提供的 SQL 查询触发器、存储过程等元数据，
* 并通过 JDBC {@link DatabaseMetaData} 读取表结构、索引、视图等信息。
* 子类仅需覆盖数据库特有逻辑（如用户管理、搜索引擎索引等）。
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 查询所有表
* List<TableDef> tables = engine.meta().table().list();
*
* // 查询单表结构
* TableDef user = engine.meta().table("user").get();
*
* // 列出索引
* List<IndexMetadata> indexes = engine.meta().index().onTable("user").list();
* }</pre>ist();
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public abstract class JdbcMetaData extends AbstractMetaData implements DataSourceAware {

    /** 当前连接的 数据源（由引擎注入） */
    protected DataSource dataSource;

    /**
    * 构造方法。
    *
    * @param engine 引擎实例
     */
    protected JdbcMetaData(Engine engine) {
        super(engine);
    }

    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
    * 获取当前方言实例。
    * @return dialect的结果
     */
    protected Dialect dialect() {
        com.chua.common.support.lang.datasource.engine.EngineDataSource<?> eds =
                engine.getDataSource(engine.getDefaultDataSourceName());
        return eds != null ? eds.getDialect() : null;
    }

    // ==================== MetaUser 默认实现 ====================

    @Override
    public MetaUser user() {
        throw new UnsupportedOperationException("当前数据库不支持用户管理");
    }

    @Override
    public MetaPermission permission() {
        throw new UnsupportedOperationException("当前数据库不支持权限管理");
    }

    // ==================== JDBC 通用查询辅助 ====================

    /**
    * 从 {@link DatabaseMetaData} 中列出所有表名。
    * @param catalog catalog
    * @param schemaPattern 模式模式
    * @return 列表table名称的结果
     */
    protected List<String> listTableNames(String catalog, String schemaPattern) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection()) {
            DatabaseMetaData dbmd = conn.getMetaData();
            try (ResultSet rs = dbmd.getTables(catalog, schemaPattern, "%",
                    new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name != null) {
                        result.add(name);
                    }
                }
            }
        }
        return result;
    }

    /**
    * 从 {@link DatabaseMetaData} 中列出指定表的所有列。
    * @param catalog catalog
    * @param schema 模式
    * @param tableName table名称
    * @return 列表column名称的结果
     */
    protected List<String> listColumnNames(String catalog, String schema, String tableName) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection()) {
            DatabaseMetaData dbmd = conn.getMetaData();
            try (ResultSet rs = dbmd.getColumns(catalog, schema, tableName, "%")) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col != null) {
                        result.add(col);
                    }
                }
            }
        }
        return result;
    }

    /**
    * 执行方言提供的 SQL 并返回结果集行列表。
    * @param sql SQL
    * @return 查询dialectsql的结果
     */
    protected List<String[]> queryDialectSql(String sql) {
        List<String[]> result = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            java.sql.ResultSetMetaData rm = rs.getMetaData();
            int cols = rm.getColumnCount();
            while (rs.next()) {
                String[] row = new String[cols];
                for (int i = 1; i <= cols; i++) {
                    row[i - 1] = rs.getString(i);
                }
                result.add(row);
            }
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
    * 获取当前默认数据源的 JDBC 数据源。
    * 优先从 engine数据源 接口获取，失败时回退到 Connection.unwrap。
    * @return 获取数据源的结果
     */
    protected DataSource getDataSource() {
        if (dataSource != null) {
            return dataSource;
        }
        try {
            com.chua.common.support.lang.datasource.engine.EngineDataSource<?> eds =
                    engine.getDataSource(engine.getDefaultDataSourceName());
            if (eds != null && eds.getSource() instanceof DataSource ds) {
                dataSource = ds;
                return ds;
            }
            // 回退：通过 Connection 获取
            DataSource temp = getJdbcConnection().unwrap(DataSource.class);
            setDataSource(temp);
            return temp;
        } catch (Exception e) {
            throw new IllegalStateException("无法获取 DataSource，请先调用 setDataSource()", e);
        }
    }

    /**
    * 获取 JDBC 连接（与 jdbcengine 同模式）。
    * @return 获取jdbcconnection的结果
     */
    protected Connection getJdbcConnection() throws Exception {
        com.chua.common.support.lang.datasource.engine.EngineDataSource<?> eds =
                engine.getDataSource(engine.getDefaultDataSourceName());
        if (eds == null) {
            throw new IllegalStateException("默认数据源未配置");
        }
        Object source = eds.getSource();
        if (source instanceof DataSource ds) {
            return ds.getConnection();
        }
        throw new IllegalStateException("数据源类型不支持 JDBC: " + source.getClass().getName());
    }
}
