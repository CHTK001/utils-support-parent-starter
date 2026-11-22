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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Calcite 数据源转换器实现类。
 *
 * <p>支持两种聚合模式：</p>
 * <ul>
 *   <li>{@link #convert(List, DataSourceEnvironment)}：按传入顺序以 ds_0/ds_1... 命名 schema；</li>
 *   <li>{@link #convertNamed(Map)}：按调用方给定的 schema 名注册成员，用于运行期联邦配置
 *       （schema 名由配置表/页面指定，可在不重启的情况下换入新成员）。</li>
 * </ul>
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
     * Calcite lex 属性值（MySQL 方言）
     */
    private static final String CALCITE_LEX_MYSQL = "MYSQL";

    /**
     * 转换（顺序命名模式：ds_0、ds_1 ...）
     */
    @Override
    public DataSource convert(List<DataSource> dataSources, DataSourceEnvironment environment) {
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个数据源");
        }
        return new CalciteDataSource(dataSources);
    }

    /**
     * 按指定 schema 名聚合数据源（命名模式）。
     *
     * <p>key = Calcite 对外暴露的 schema 名，value = 成员数据源。
     * 每次 {@code getConnection} 都按当前成员注册 schema，调用方替换成员引用后，
     * 新连接立即反映最新配置，天然支持运行期热刷新。</p>
     *
     * @param namedDelegates schema 名 - 成员数据源映射（保持顺序）
     * @return Calcite 聚合数据源
     */
    public DataSource convertNamed(Map<String, DataSource> namedDelegates) {
        if (namedDelegates == null || namedDelegates.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个数据源");
        }
        return new CalciteDataSource(new LinkedHashMap<>(namedDelegates));
    }

    /**
     * 类型
     */
    @Override
    public String type() {
        return "CALCITE";
    }

    /**
     * 封装 Calcite 数据源逻辑的内部类。
     *
     * <p>两种成员持有方式互斥：顺序列表（delegates）或命名映射（namedDelegates）。
     * 字段均为 final，实例本身不可变；运行期刷新由调用方创建新实例并替换引用完成。</p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class CalciteDataSource implements DataSource {

        /**
         * 被聚合的数据源列表（顺序命名模式）
         */
        private final List<DataSource> delegates;

        /**
         * schema 名 - 成员数据源映射（命名模式，与 delegates 互斥）
         */
        private final Map<String, DataSource> namedDelegates;

        /**
         * 顺序命名模式构造。
         *
         * @param delegates 被聚合的数据源列表
         */
        CalciteDataSource(List<DataSource> delegates) {
            this.delegates = delegates;
            this.namedDelegates = null;
        }

        /**
         * 命名模式构造。
         *
         * @param namedDelegates schema 名 - 成员数据源映射
         */
        CalciteDataSource(Map<String, DataSource> namedDelegates) {
            this.delegates = null;
            this.namedDelegates = namedDelegates;
        }

        /**
         * 获取连接：每次连接都按当前成员注册 Calcite schema。
         */
        @Override
        public Connection getConnection() throws SQLException {
            Properties info = new Properties();
            info.put(CALCITE_LEX, CALCITE_LEX_MYSQL);
            Connection connection = DriverManager.getConnection(CALCITE_URL, info);
            CalciteConnection calciteConn = connection.unwrap(CalciteConnection.class);
            SchemaPlus rootSchema = calciteConn.getRootSchema();

            if (namedDelegates != null) {
                for (Map.Entry<String, DataSource> entry : namedDelegates.entrySet()) {
                    registerSchema(rootSchema, entry.getKey(), entry.getValue());
                }
            } else {
                for (int i = 0; i < delegates.size(); i++) {
                    registerSchema(rootSchema, "ds_" + i, delegates.get(i));
                }
            }
            return connection;
        }

        /**
         * 注册单个成员 schema，成员失效时跳过而不中断整体聚合。
         *
         * @param rootSchema Calcite 根 schema
         * @param name       schema 名
         * @param member     成员数据源
         */
        private void registerSchema(SchemaPlus rootSchema, String name, DataSource member) {
            try {
                JdbcSchema jdbcSchema = JdbcSchema.create(rootSchema, name, member, null, null);
                rootSchema.add(name, jdbcSchema);
            } catch (Exception e) {
                // 单个成员不可达时降级跳过，保留其它成员的联邦能力
            }
        }

        /**
         * 获取连接
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        /**
         * Unwrap
         */
        @Override
        @SuppressWarnings("unchecked")
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return (T) this;
            }
            return null;
        }

        /**
         * 是否包装器for
         */
        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }

        /**
         * 获取记录日志Writer
         */
        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        /**
         * 设置记录日志Writer
         */
        @Override
        public void setLogWriter(PrintWriter out) {
        }

        /**
         * 设置login超时
         */
        @Override
        public void setLoginTimeout(int seconds) {
        }

        /**
         * 获取login超时
         */
        @Override
        public int getLoginTimeout() {
            return 0;
        }

        /**
         * 获取父日志记录器
         */
        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        }
    }
}
