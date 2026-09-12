package com.chua.calcite.support.datasource;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.datasource.support.datasource.DataScheme;
import com.chua.datasource.support.datasource.DataSourceCreator;
import com.chua.datasource.support.datasource.DataTable;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

/**
* Calcite 数据源创建器，使用 Calcite 将多个数据源聚合为一个统一的 {@link DataSource}。
* <p>
* 支持聚合以下类型的数据源：
* <ul>
*   <li><b>JDBC 数据源</b> — 通过 {@link #addDataSource(String, DataSource)} 注册，内部转换为 {@link JdbcSchema}</li>
*   <li><b>DataScheme 虚拟库</b> — 通过 {@link #addScheme(DataScheme)} 注册，包含多个 {@link DataTable}</li>
*   <li><b>DataTable 虚拟表</b> — 通过 {@link #addTable(String, DataTable)} 注册，自动归入指定 Scheme</li>
* </ul>
* </p>
* <p>
* 使用示例：
* <pre>{@code
* // 创建聚合数据源
* DataSource unified = new CalciteDataSourceCreator()
*     .addDataSource("mydb", myDataSource)
*     .addScheme(new CalciteDataScheme("sales")
*         .addTable(new CalciteDataTable("orders", ...).addRow(...)))
*     .create();
*
* // 通过 SQL 跨源查询
* try (Connection conn = unified.getConnection()) {
*     try (ResultSet rs = conn.createStatement()
*             .executeQuery("SELECT * FROM sales.orders")) {
*         ...
*     }
* }
* }</pre>eStatement()
*             .executeQuery("SELECT * FROM sales.orders")) {
*         ...
*     }
* }
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@SpiDefault
@Spi("calcite")
@Slf4j
public class CalciteDataSourceCreator implements DataSourceCreator {

    /**
    * 已注册的 JDBC 数据源（名称 -> 数据源）
     */
    private final Map<String, DataSource> dataSources = new LinkedHashMap<>();

    /**
    * 已注册的 数据scheme 虚拟库列表
     */
    private final List<DataScheme> schemes = new ArrayList<>();

    /**
    * Calcite 连接属性
     */
    private final Properties calciteProps = new Properties();

    {
        calciteProps.put("lex", "MYSQL");
        calciteProps.put("fun", "mysql");
    }

    // ---------------------------------------------------------------
    // 构造 & 工厂方法
    // ---------------------------------------------------------------

    /**
    * 创建一个新的 {@code CalciteDataSourceCreator} 实例。
    *
    * @return 新的创建器实例
     */
    public static CalciteDataSourceCreator newCreator() {
        return new CalciteDataSourceCreator();
    }

    // ---------------------------------------------------------------
    // 注册方法
    // ---------------------------------------------------------------

    @Override
    /** 添加数据源 */
    public CalciteDataSourceCreator addDataSource(String name, DataSource dataSource) {
        Objects.requireNonNull(name, "dataSource name must not be null");
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.dataSources.put(name, dataSource);
        return this;
    }

    @Override
    /** 添加Scheme */
    public CalciteDataSourceCreator addScheme(DataScheme scheme) {
        Objects.requireNonNull(scheme, "scheme must not be null");
        this.schemes.add(scheme);
        return this;
    }

    @Override
    /** 添加Table */
    public CalciteDataSourceCreator addTable(String schemaName, DataTable table) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        Objects.requireNonNull(table, "table must not be null");

        // 查找是否已有同名 Scheme
        DataScheme existing = null;
        for (DataScheme s : schemes) {
            if (schemaName.equals(s.getName())) {
                existing = s;
                break;
            }
        }

        if (existing instanceof CalciteDataScheme) {
            ((CalciteDataScheme) existing).addTable(table);
        } else if (existing != null) {
 // 非 calcite数据scheme 的情况，创建一个新的包装
            CalciteDataScheme wrapper = new CalciteDataScheme(schemaName);
            for (String tName : existing.getTableNames()) {
                DataTable t = existing.getTable(tName);
                if (t != null) {
                    wrapper.addTable(t);
                }
            }
            wrapper.addTable(table);
            schemes.remove(existing);
            schemes.add(wrapper);
        } else {
            schemes.add(new CalciteDataScheme(schemaName).addTable(table));
        }
        return this;
    }

    // ---------------------------------------------------------------
 // 创建 数据源
    // ---------------------------------------------------------------

    /**
    * 创建
    *
    * @return 创建的结果
     */
    public DataSource create() {
        return new UnifiedCalciteDataSource(
                new LinkedHashMap<>(this.dataSources),
                new ArrayList<>(this.schemes),
                this.calciteProps
        );
    }

    // ---------------------------------------------------------------
    // 内部类：统一 Calcite 数据源
    // ---------------------------------------------------------------

    /**
    * 统一的 Calcite 数据源，内部封装了 JDBC 数据源和虚拟表的聚合逻辑。
    * @author CH
    * @since 4.0.0
     */
    private static class UnifiedCalciteDataSource implements DataSource {

        /** 数据源 */
        private final Map<String, DataSource> dataSources;
        /** Schemes */
        private final List<DataScheme> schemes;
        /** Calciteprops */
        private final Properties calciteProps;

        UnifiedCalciteDataSource(Map<String, DataSource> dataSources,
                                 List<DataScheme> schemes,
                                 Properties calciteProps) {
            this.dataSources = dataSources;
            this.schemes = schemes;
            this.calciteProps = calciteProps;
        }

        @Override
        /** 获取Connection */
        public Connection getConnection() throws SQLException {
            Connection connection = DriverManager.getConnection("jdbc:calcite:", calciteProps);
            CalciteConnection calciteConn = connection.unwrap(CalciteConnection.class);
            SchemaPlus rootSchema = calciteConn.getRootSchema();

            // 1. 注册 JDBC 数据源
            for (Map.Entry<String, DataSource> entry : dataSources.entrySet()) {
                String schemaName = entry.getKey();
                DataSource ds = entry.getValue();
                try {
                    JdbcSchema jdbcSchema = JdbcSchema.create(rootSchema, schemaName, ds, null, null);
                    rootSchema.add(schemaName, jdbcSchema);
                    log.debug("[calcite] 注册 JDBC Schema: {}", schemaName);
                } catch (Exception e) {
                    log.warn("[calcite] 注册 JDBC Schema [{}] 失败: {}", schemaName, e.getMessage());
                }
            }

 // 2. 注册 数据scheme 虚拟库
            for (DataScheme scheme : schemes) {
                String schemaName = scheme.getName();
                if (schemaName == null || schemaName.isEmpty()) {
                    schemaName = "scheme_" + System.identityHashCode(scheme);
                }

                List<DataTable> tables = new ArrayList<>();
                for (String tName : scheme.getTableNames()) {
                    DataTable t = scheme.getTable(tName);
                    if (t != null) {
                        tables.add(t);
                    }
                }
                if (tables.isEmpty()) {
                    log.debug("[calcite] 跳过空的 DataScheme: {}", schemaName);
                    continue;
                }

                Map<String, Table> calciteTableMap = new LinkedHashMap<>();
                for (DataTable table : tables) {
                    String tableName = table.getName();
                    if (tableName == null || tableName.isEmpty()) {
                        tableName = "t_" + calciteTableMap.size();
                    }
                    calciteTableMap.put(tableName, CalciteDataTableAdapter.of(table));
                    log.debug("[calcite] 注册虚拟表: {}.{}", schemaName, tableName);
                }

                rootSchema.add(schemaName, new DataSchemeSchema(calciteTableMap));
                log.info("[calcite] 注册 DataScheme: {} ({} 表)", schemaName, calciteTableMap.size());
            }

            log.info("[calcite] 统一数据源初始化完成: {} JDBC 源 + {} DataScheme",
                    dataSources.size(), schemes.size());
            return connection;
        }

        @Override
        /** 获取Connection */
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        @SuppressWarnings("unchecked")
        /**
        * Unwrap
        *
        * @param iface iface
        * @return unwrap的结果
         */
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return (T) this;
            }
            return null;
        }

        @Override
        /** 是否包装器for */
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }

        @Override
        /** 获取记录日志Writer */
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        /** 设置记录日志Writer */
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        /** 设置login超时 */
        public void setLoginTimeout(int seconds) {
        }

        @Override
        /** 获取login超时 */
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        /** 获取父日志记录器 */
        public Logger getParentLogger() {
            return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        }
    }

    /**
    * 将 数据scheme 映射为 Calcite 模式，为每个 数据table 提供 scannabletable。
    * @author CH
    * @since 4.0.0
     */
    private static class DataSchemeSchema extends AbstractSchema {

        /** table映射 */
        private final Map<String, Table> tableMap;

        DataSchemeSchema(Map<String, Table> tableMap) {
            this.tableMap = tableMap;
        }

        @Override
        /** 获取table映射 */
        protected Map<String, Table> getTableMap() {
            return tableMap;
        }
    }
}
