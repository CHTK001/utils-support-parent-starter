package com.chua.common.support.lang.datasource.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.meta.MetaData;

import java.util.List;

/**
 * 引擎接口，是数据源管理和 Lambda 链式操作的核心入口。
 * <p>
 * Engine 负责以下职责：
 * <ul>
 *   <li><b>数据源管理</b> — 通过 {@link #addDataSource(String, EngineDataSource)} 注册多种类型的数据源</li>
 *   <li><b>Lambda 链式查询</b> — 通过 {@link #query(Class)} 创建 LambdaQueryWrapper，流式构建查询条件</li>
 *   <li><b>Lambda 链式更新</b> — 通过 {@link #update(Class)} 创建 LambdaUpdateWrapper</li>
 *   <li><b>Lambda 链式删除</b> — 通过 {@link #delete(Class)} 创建 LambdaDeleteWrapper</li>
 *   <li><b>SQL 执行</b> — 通过 {@link #getExecutor()} / {@link #getExecutor(String)} 获取 JDBC 执行器</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // SPI 创建引擎
 * Engine engine = Engine.create("jdbc");
 *
 * // 添加 JDBC 数据源
 * HikariDataSource ds = new HikariDataSource();
 * ds.setJdbcUrl("jdbc:mysql://localhost:3306/mydb");
 * ds.setUsername("root");
 * ds.setPassword("123456");
 * Dialect dialect = Dialect.getExtension("mysql");
 * engine.addDataSource("default", new SimpleEngineDataSource<>("default", ds, dialect));
 *
 * // 添加 Redis 数据源
 * RedisClient redis = new RedisClient("localhost", 6379);
 * engine.addDataSource("cache", new SimpleEngineDataSource<>("cache", redis));
 *
 * // Lambda 链式查询
 * List<User> list = engine.query(User.class)
 *     .eq(User::getName, "张三")
 *     .gt(User::getAge, 18)
 *     .like(User::getEmail, "@qq.com")
 *     .orderByDesc(User::getId)
 *     .list();
 *
 * // Lambda 分页查询
 * Page<User> page = engine.query(User.class)
 *     .like(User::getName, "张")
 *     .page(1, 10);
 *
 * // Lambda 链式更新
 * engine.update(User.class)
 *     .set(User::getName, "李四")
 *     .set(User::getAge, 25)
 *     .eq(User::getId, 1)
 *     .update();
 *
 * // Lambda 链式删除
 * engine.delete(User.class)
 *     .eq(User::getId, 1)
 *     .remove();
 *
 * // 原生 SQL 查询
 * List<Map<String, Object>> rows = engine.getExecutor()
 *     .query("select * from user where age > ?", 18);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public interface Engine extends AutoCloseable {

    /**
     * 添加一个数据源到引擎。
     * <p>数据源通过名称区分，支持同时管理多种类型的数据源。
     * 泛型参数由具体的 {@link EngineDataSource} 实现决定。</p>
     *
     * @param name       数据源名称（如 "default"、"db1"、"slave"、"cache"）
     * @param dataSource 数据源封装实例
     * @param <T>        底层源类型
     * @return this
     */
    <T> Engine addDataSource(String name, EngineDataSource<T> dataSource);

    /**
     * 将数据列表存储到引擎内存中。
     * <p>数据按名称存储，可通过 {@link #query(Class)} 检索。</p>
     *
     * @param name 数据存储名称（表名）
     * @param data 数据列表
     * @param <T>  数据类型
     * @return this
     */
    <T> Engine store(String name, List<T> data);

    /**
     * 设置默认数据源名称。
     * <p>当不指定数据源名称时，使用默认数据源执行操作。</p>
     *
     * @param name 数据源名称
     * @return this
     */
    Engine setDefaultDataSourceName(String name);

    /**
     * 根据名称获取 SQL 执行器。
     * <p>仅对 JDBC 类型的数据源有效，非 SQL 数据源调用将返回 null 或抛出异常。</p>
     *
     * @param dataSourceName 数据源名称
     * @return SQL 执行器
     * @deprecated 请使用 {@link #execute(String, Object...)} 或 {@link #query(String, Object...)} 代替
     */
    @Deprecated
    SqlExecutor getExecutor(String dataSourceName);

    /**
     * 为指定数据源设置隧道。
     * <p>隧道开启后，JDBC URL 中的端口将被替换为隧道实际绑定的本地端口。</p>
     *
     * @param dataSourceName 数据源名称
     * @param tunnel 隧道实例
     * @return this
     */
    default Engine setTunnel(String dataSourceName, com.chua.common.support.network.tunnel.Tunnel tunnel) {
        return this;
    }

    /**
     * 为指定数据源开启隧道并获取实际端口。
     * <p>开启成功后，JDBC URL 中的端口将被替换为隧道端口。</p>
     *
     * @param dataSourceName 数据源名称
     * @param tunnel 隧道实例
     * @return 隧道实际绑定的本地端口，开启失败返回 -1
     */
    default int openTunnel(String dataSourceName, com.chua.common.support.network.tunnel.Tunnel tunnel) {
        return -1;
    }

    /**
     * 关闭指定数据源的隧道。
     *
     * @param dataSourceName 数据源名称
     * @return this
     */
    default Engine closeTunnel(String dataSourceName) {
        return this;
    }

    /**
     * 获取默认数据源的 SQL 执行器。
     *
     * @return SQL 执行器
     * @deprecated 请使用 {@link #execute(String, Object...)} 或 {@link #query(String, Object...)} 代替
     */
    @Deprecated
    SqlExecutor getExecutor();

    /**
     * 执行 SQL 更新语句（INSERT / UPDATE / DELETE / DDL）。
     * <p>默认实现通过 {@link #getExecutor()} 代理，建议子类直接覆盖。</p>
     *
     * @param sql    SQL 语句
     * @param params 参数
     * @return 受影响行数
     */
    default int execute(String sql, Object... params) {
        SqlExecutor e = getExecutor();
        return e != null ? e.execute(sql, params) : 0;
    }

    /**
     * 根据名称获取数据源封装对象。
     *
     * @param name 数据源名称
     * @param <T> 底层源类型
     * @return 数据源封装实例
     */
    <T> EngineDataSource<T> getDataSource(String name);

    /**
     * 获取默认数据源封装对象。
     *
     * @param <T> 底层源类型
     * @return 数据源封装实例
     */
    <T> EngineDataSource<T> getDataSource();

    /**
     * 创建 Lambda 查询包装器，用于链式构建查询条件并执行查询。
     * <p>
     * 使用方法引用替代字符串列名，编译期类型安全。
     * </p>
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询包装器
     */
    <T> LambdaQueryWrapper<T> query(Class<T> entityClass);

    /**
     * 创建 Lambda 更新包装器，用于链式构建 SET 和 WHERE 条件并执行更新。
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 更新包装器
     */
    <T> LambdaUpdateWrapper<T> update(Class<T> entityClass);

    /**
     * 创建 Lambda 删除包装器，用于链式构建 WHERE 条件并执行删除。
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 删除包装器
     */
    <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass);

    /**
     * 获取指定数据源的方言。
     *
     * @param dataSourceName 数据源名称
     * @return 方言实例，非 SQL 数据源返回 null
     */
    Dialect getDialect(String dataSourceName);

    /**
     * 获取默认数据源名称。
     *
     * @return 默认数据源名称
     */
    default String getDefaultDataSourceName() {
        return null;
    }

    /**
     * 获取元数据操作入口。
     * <p>通过该入口可以执行表、视图、索引、触发器、存储过程、外键、搜索引擎索引的 CRUD 操作。</p>
     *
     * @return 元数据操作接口
     */
    default MetaData meta() {
        throw new UnsupportedOperationException("该引擎不支持元数据操作");
    }

    /**
     * 通过 SPI 创建引擎实例。
     *
     * @param type SPI 扩展键（如 "jdbc"）
     * @return 引擎实例
     */
    static Engine create(String type) {
        return com.chua.common.support.spi.ServiceProvider.of(Engine.class).getExtension(type);
    }

    /**
     * 关闭引擎，释放所有已注册数据源的资源。
     */
    @Override
    void close();
}