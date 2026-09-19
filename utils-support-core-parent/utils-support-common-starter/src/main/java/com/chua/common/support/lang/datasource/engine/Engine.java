package com.chua.common.support.lang.datasource.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.flyway.DefaultFlyway;
import com.chua.common.support.lang.datasource.flyway.Flyway;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Map;

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
 *   <li><b>数据库迁移</b> — 通过 {@link #flyway()} 获取 SQL 脚本版本化迁移工具</li>
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
     * Engine SPI 扩展名，用于 {@code META-INF/extensions/} 注册与 SPI 查找。
     */
    String SPI_NAME = "engine";

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
     * 执行数据操作语句（INSERT / UPDATE / DELETE / CREATE 等）。
     * <p>参数名为 ql（查询语言），SQL 引擎执行 SQL，NoSQL 引擎执行对应方言。</p>
     * <p>默认实现通过 {@link #getExecutor()} 代理，非 SQL 引擎应覆盖实现。
     * 执行前后依次回调 {@link EngineInterceptor} 扩展的
     * {@code beforeUpdate / afterUpdate / onError}。</p>
     *
     * @param ql     数据操作语句
     * @param params 参数
     * @return 受影响行数
     */
    default int execute(String ql, Object... params) {
        SqlExecutor e = getExecutor();
        if (e == null) {
            UnsupportedOperationException ex = new UnsupportedOperationException("当前引擎不支持数据操作: " + getClass().getName());
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.onError(ql, params, ex);
            }
            throw ex;
        }
        for (EngineInterceptor interceptor : getInterceptors()) {
            interceptor.beforeUpdate(ql, params);
        }
        try {
            int affected = e.execute(ql, params);
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.afterUpdate(ql, params, affected);
            }
            return affected;
        } catch (RuntimeException re) {
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.onError(ql, params, re);
            }
            throw re;
        }
    }

    /**
     * 执行原生查询语句，返回 Map 行列表。
     * <p>参数名为 ql（查询语言），SQL 引擎执行 SQL，NoSQL 引擎执行对应方言。</p>
     * <p>默认实现通过 {@link #getExecutor()} 代理，非 SQL 引擎应覆盖实现。
     * 执行前后依次回调 {@link EngineInterceptor} 扩展的
     * {@code beforeQuery / afterQuery / onError}。</p>
     *
     * @param ql     查询语句
     * @param params 参数
     * @return 查询结果行列表
     */
    default List<Map<String, Object>> query(String ql, Object... params) {
        SqlExecutor e = getExecutor();
        if (e == null) {
            UnsupportedOperationException ex = new UnsupportedOperationException("当前引擎不支持 SQL 查询: " + getClass().getName());
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.onError(ql, params, ex);
            }
            throw ex;
        }
        for (EngineInterceptor interceptor : getInterceptors()) {
            interceptor.beforeQuery(ql, params);
        }
        try {
            List<Map<String, Object>> result = e.query(ql, params);
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.afterQuery(ql, params, result);
            }
            return result;
        } catch (RuntimeException re) {
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.onError(ql, params, re);
            }
            throw re;
        }
    }

    /**
     * 执行原生查询语句，自动映射为指定类型的对象列表。
     * <p>默认实现通过 {@link #getExecutor()} 代理，非 SQL 引擎应覆盖实现。
     * 执行前后依次回调 {@link EngineInterceptor} 扩展的
     * {@code beforeQuery / afterQuery / onError}。</p>
     *
     * @param ql      查询语句
     * @param rowType 行类型
     * @param params  参数
     * @param <T>     行类型参数
     * @return 类型化结果列表
     */
    default <T> List<T> query(String ql, Class<T> rowType, Object... params) {
        SqlExecutor e = getExecutor();
        if (e == null) {
            UnsupportedOperationException ex = new UnsupportedOperationException("当前引擎不支持 SQL 查询: " + getClass().getName());
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.onError(ql, params, ex);
            }
            throw ex;
        }
        for (EngineInterceptor interceptor : getInterceptors()) {
            interceptor.beforeQuery(ql, params);
        }
        try {
            List<T> result = e.query(ql, rowType, params);
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.afterQuery(ql, params, result);
            }
            return result;
        } catch (RuntimeException re) {
            for (EngineInterceptor interceptor : getInterceptors()) {
                interceptor.onError(ql, params, re);
            }
            throw re;
        }
    }

    /**
     * 判断当前引擎是否支持原生 SQL/方言语句执行。
     *
     * @return true 表示 {@link #getExecutor()} 返回有效执行器
     */
    default boolean supportsSql() {
        return getExecutor() != null;
    }

    /**
     * 判断当前引擎是否支持元数据操作。
     * <p>不支持元数据操作的引擎（如 Prometheus）应覆盖返回 false。</p>
     *
     * @return 默认返回 true
     */
    default boolean supportsMeta() {
        return true;
    }

    /**
     * 获取引擎拦截器扩展列表。
     * <p>通过 SPI 查找 {@code engine-interceptor} 扩展点实现，
     * 结果按 order 降序排列，无注册实现时返回空列表。</p>
     *
     * @return 拦截器列表（非 null）
     */
    private List<EngineInterceptor> getInterceptors() {
        List<EngineInterceptor> interceptors = ServiceProvider.of(EngineInterceptor.class)
                .getNewExtensions(EngineInterceptor.SPI_NAME, this);
        return interceptors == null ? List.of() : interceptors;
    }

    /**
     * 获取数据库迁移工具，提供类似 Flyway 的 SQL 脚本版本化管理。
     * <p>获取逻辑：通过 {@code ServiceProvider.of(Flyway.class).getNewExtensions(SPI_NAME, this)}
     * 获取 {@code flyway} 扩展点实现（结果列表按 SPI order 降序排列，首个即最高优先级）；
     * 取列表中首个（即最高优先级）扩展实例，如业务方注册的
     * {@code utils-support-flyway-starter} 增强实现；无匹配扩展时兜底返回
     * 默认实现 {@link DefaultFlyway}。</p>
     *
     * @return 迁移工具（非 null：SPI 扩展或默认实现二选一）
     */
    default Flyway flyway() {
        for (Flyway f : ServiceProvider.of(Flyway.class)
                .getNewExtensions(Flyway.SPI_NAME, this)) {
            return f;
        }
        return new DefaultFlyway(this);
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
