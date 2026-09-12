package com.chua.neo4j.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import org.neo4j.driver.Driver;

/**
   * Neo4j 引擎数据源实现，包装 Neo4j 螺栓 驱动实例。
 * <p>
 * 该类实现了 {@link EngineDataSource} 接口，专门用于 Neo4j 图数据库的连接管理。
   * 每个 Neo4jengine数据源 实例对应一个 Neo4j 连接配置，
 * 包含驱动实例、连接 URI、认证信息等。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 创建数据源
 * Neo4jEngineDataSource ds = new Neo4jEngineDataSource("default", driver);
 *
 * // 添加到引擎
 * Neo4jEngine engine = new Neo4jEngine();
 * engine.addDataSource("default", ds);
 * }</pre>rce("default", ds);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Neo4jEngineDataSource implements EngineDataSource<Driver> {

    /**
     * 数据源名称，在 {@link com.chua.common.support.lang.datasource.engine.Engine} 中唯一标识
     */
    private final String name;

    /**
      * Neo4j 螺栓 驱动实例
     */
    private final Driver driver;

    /**
     * 数据库方言（Neo4j 是图数据库，不使用 SQL 方言）
     */
    private Dialect dialect;

    /**
     * 连接 URI
     */
    private String url;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 构造仅包含名称和驱动的简单数据源。
     *
     * @param name   数据源名称
     * @param driver Neo4j 螺栓 驱动实例
     */
    public Neo4jEngineDataSource(String name, Driver driver) {
        this.name = name;
        this.driver = driver;
    }

    /**
     * 构造包含完整连接信息的数据源。
     *
     * @param name     数据源名称
     * @param driver   Neo4j 螺栓 驱动实例
     * @param url      连接 URI
     * @param username 用户名
     * @param password 密码
     */
    public Neo4jEngineDataSource(String name, Driver driver, String url, String username, String password) {
        this.name = name;
        this.driver = driver;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    /**
     * 获取数据源名称。
     *
     * @return 名称
     */
    @Override
    public String name() {
        return name;
    }

    /**
      * 获取 Neo4j 螺栓 驱动实例。
     *
     * @return 驱动对象
     */
    @Override
    public Driver getSource() {
        return driver;
    }

    /**
     * 设置底层数据源实例。
     * <p>
     * 由于 Driver 通常由框架管理生命周期，此方法不做实际替换操作。
     * </p>
     *
     * @param source 新的数据源对象
     * @return this
     */
    @SuppressWarnings("unchecked")
    @Override
    public EngineDataSource<Driver> setSource(Object source) {
        return this;
    }

    /**
     * 获取数据库方言。
     * <p>
      * Neo4j 是图数据库，不使用 SQL 方言，始终返回 空。
     * </p>
     *
     * @return null
     */
    @Override
    public Dialect getDialect() {
        return dialect;
    }

    /**
     * 设置数据库方言。
     *
     * @param dialect 方言实例
     * @return this
     */
    @Override
    public EngineDataSource<Driver> setDialect(Dialect dialect) {
        this.dialect = dialect;
        return this;
    }

    /**
     * 获取连接 URI。
     *
     * @return URI 字符串
     */
    @Override
    public String url() {
        return url;
    }

    /**
     * 获取用户名。
     *
     * @return 用户名
     */
    @Override
    public String username() {
        return username;
    }

    /**
     * 获取密码。
     *
     * @return 密码
     */
    @Override
    public String password() {
        return password;
    }

    /**
     * 关闭数据源，释放 Neo4j 驱动资源。
     */
    @Override
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }
}
