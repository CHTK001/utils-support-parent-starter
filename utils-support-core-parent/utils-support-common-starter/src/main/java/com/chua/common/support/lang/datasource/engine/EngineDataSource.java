package com.chua.common.support.lang.datasource.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;

/**
 * 引擎数据源泛型接口，封装任意类型的数据源及其关联的方言信息。
 * <p>
 * 一个 EngineDataSource 实例对应一个数据连接配置，泛型参数 {@code T} 表示底层源的类型。
 * 支持不同类型的数据源，例如：
 * <ul>
 *   <li>{@code EngineDataSource<DataSource>} — JDBC 关系型数据库（MySQL、PostgreSQL、Oracle 等）</li>
 *   <li>{@code EngineDataSource<RedisClient>} — Redis 缓存数据库</li>
 *   <li>{@code EngineDataSource<MongoClient>} — MongoDB 文档数据库</li>
 *   <li>{@code EngineDataSource<ClickHouseClient>} — ClickHouse 分析型数据库</li>
 * </ul>
 * </p>
 * <p>
 * 核心属性：
 * <ul>
 *   <li>{@link #name()} — 数据源名称，在 {@link Engine} 中唯一标识</li>
 *   <li>{@link #getSource()} — 底层数据源实例</li>
 *   <li>{@link #getDialect()} — 数据库方言（仅 SQL 类型有效，非 SQL 类型返回 null）</li>
 *   <li>{@link #url()} / {@link #username()} / {@link #password()} — 连接信息（可选）</li>
 * </ul>
 * </p>
 *
 * @param <T> 底层数据源类型
 * @author CH
 * @since 2024/12/12
 */
@SuppressWarnings("unchecked")
public interface EngineDataSource<T> extends AutoCloseable {

    /**
     * 获取数据源名称，用于在 {@link Engine} 中唯一标识。
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 获取底层数据源实例。
     * <p>返回值类型由泛型参数 {@code T} 决定，例如：</p>
     * <ul>
     *   <li>JDBC 数据源返回 {@link javax.sql.DataSource}</li>
     *   <li>Redis 数据源返回 {@code RedisClient} 或 {@code JedisCluster}</li>
     *   <li>MongoDB 数据源返回 {@code MongoClient}</li>
     * </ul>
     *
     * @return 底层数据源实例
     */
    T getSource();

    /**
     * 获取指定类型的底层数据源实例。
     * <p>当需要访问特定接口或实现类时使用，例如获取 HikariDataSource 的配置信息。</p>
     *
     * @param type 目标类型
     * @param <R>  类型参数
     * @return 类型匹配的实例，不匹配返回 null
     */
    default <R> R getSource(Class<R> type) {
        T source = getSource();
        return type.isInstance(source) ? (R) source : null;
    }

    /**
     * 设置底层数据源对象。
     * <p>由数据源自身托管生命周期的实现不支持运行期替换，调用时将抛出
     * {@link UnsupportedOperationException}，此时应重新向引擎注册数据源。</p>
     *
     * @param source 新的数据源对象
     * @return this
     */
    EngineDataSource<T> setSource(Object source);

    /**
     * 获取数据库方言。
     * <p>仅 JDBC/SQL 类型的数据源需要返回有效的方言实例；
     * 非 SQL 类型（如 Redis、MongoDB）应返回 null。</p>
     *
     * @return 方言实例，非 SQL 数据源返回 null
     */
    Dialect getDialect();

    /**
     * 设置数据库方言。
     *
     * @param dialect 方言实例
     * @return this
     */
    EngineDataSource<T> setDialect(Dialect dialect);

    /**
     * 获取连接 URL。
     * <p>JDBC 数据源返回如 {@code jdbc:mysql://localhost:3306/mydb}；
     * 非 SQL 数据源返回自定义连接串或 null。</p>
     *
     * @return 连接 URL 或 null
     */
    String url();

    /**
     * 获取用户名。
     *
     * @return 用户名或 null
     */
    String username();

    /**
     * 获取密码。
     *
     * @return 密码或 null
     */
    String password();

    /**
     * 获取数据库名称（MongoDB 等非 JDBC 数据源使用）。
     *
     * @return 数据库名称或 null
     */
    default String database() {
        return null;
    }

    /**
     * 获取隧道本地端口号。
     * <p>当数据源通过 SSH 隧道连接时，该字段存储隧道实际绑定的本地端口。</p>
     * <p>默认值为 0，表示未启用隧道。</p>
     *
     * @return 隧道本地端口号，未启用隧道返回 0
     */
    default int tunnelPort() {
        return 0;
    }

    /**
     * 设置隧道本地端口号。
     * <p>当数据源通过 SSH 隧道连接时，由引擎在开启隧道后自动设置该值。</p>
     *
     * @param tunnelPort 隧道本地端口号
     * @return this
     */
    default EngineDataSource<T> setTunnelPort(int tunnelPort) {
        return this;
    }

    /**
     * 关闭数据源，释放底层资源。
     * <p>如果底层数据源实现了 {@link AutoCloseable}，则自动调用 close。</p>
     * <p>底层释放失败会抛出 {@link IllegalStateException}，不静默泄漏资源；
     * 需要“关闭其余资源不受影响”的调用方（如引擎批量关闭）自行逐个捕获并记录。</p>
     */
    @Override
    default void close() {
        T source = getSource();
        if (source instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                throw new IllegalStateException("数据源关闭失败: "
                        + (source == null ? "null" : source.getClass().getName()), e);
            }
        }
    }
}
