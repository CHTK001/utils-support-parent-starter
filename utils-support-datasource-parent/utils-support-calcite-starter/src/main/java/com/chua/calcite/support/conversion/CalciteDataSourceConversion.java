package com.chua.calcite.support.conversion;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.DataSourceConversion;
import com.chua.datasource.support.engine.DataSourceEnvironment;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Calcite 数据源转换器实现类。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("CALCITE")
public class CalciteDataSourceConversion implements DataSourceConversion {

    /**
     * Calcite 连接 URL
     */
    private static final String CALCITE_URL = "jdbc:calcite:";

    /**
     * Calcite lex 属性键
     */
    private static final String CALCITE_LEX = "lex";

    /**
     * Calcite lex 属性值（MYSQL 方言）
     */
    private static final String CALCITE_LEX_MYSQL = "MYSQL";

    @Override
    public DataSource convert(List<DataSource> dataSources, DataSourceEnvironment environment) {
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个数据源");
        }
        return new CalciteDataSource(dataSources);
    }

    @Override
    public String type() {
        return "CALCITE";
    }

    /**
     * 封装 Calcite 数据源逻辑的内部类。
     */
    private static class CalciteDataSource implements DataSource {

        /**
         * 被聚合的数据源列表
         */
        private final List<DataSource> delegates;

        /**
         * 构造内部 Calcite 数据源。
         *
         * @param delegates 被聚合的数据源列表
         */
        CalciteDataSource(List<DataSource> delegates) {
            this.delegates = delegates;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Properties info = new Properties();
            info.put(CALCITE_LEX, CALCITE_LEX_MYSQL);
            Connection connection = DriverManager.getConnection(CALCITE_URL, info);
            CalciteConnection calciteConn = connection.unwrap(CalciteConnection.class);
            SchemaPlus rootSchema = calciteConn.getRootSchema();

            for (int i = 0; i < delegates.size(); i++) {
                String name = "ds_" + i;
                JdbcSchema jdbcSchema = JdbcSchema.create(rootSchema, name, delegates.get(i), null, null);
                rootSchema.add(name, jdbcSchema);
            }
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return (T) this;
            }
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        }
    }
}
