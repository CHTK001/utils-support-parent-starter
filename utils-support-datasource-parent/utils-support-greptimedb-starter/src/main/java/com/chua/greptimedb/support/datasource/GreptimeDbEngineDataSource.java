package com.chua.greptimedb.support.datasource;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.greptimedb.support.dialect.GreptimeDialect;
import io.greptime.GreptimeDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * greptimedb 数据源封装，持有 {@link GreptimeDB} gRPC 客户端实例。
 * <p>
 * 供 {@link com.chua.greptimedb.support.engine.GreptimeDbEngine} 按名称管理多个 greptimedb 集群。
 * 关闭后客户端被释放且不可再次注入，重复 {@code close()} 无副作用。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GreptimeDbEngineDataSource implements EngineDataSource<GreptimeDB> {

    /**
     * 日志记录器
     */
    private static final Logger log = LoggerFactory.getLogger(GreptimeDbEngineDataSource.class);

    /**
     * 方言 SPI 注册键
     */
    private static final String DIALECT_KEY = "greptime";

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * 底层 gRPC 客户端，关闭后置 空
     */
    private volatile GreptimeDB source;

    /**
     * 连接端点
     */
    private final String url;

    /**
     * 用户名
     */
    private final String username;

    /**
     * 密码
     */
    private final String password;

    /**
     * 数据库名
     */
    private final String database;

    /**
     * 是否已关闭
     */
    private volatile boolean closed;

    /**
     * 方言实例，首次获取时按 SPI 解析，可通过 setDialect 替换。
     */
    private volatile Dialect dialect;

    /**
     * 构造方法
     *
     * @param name     数据源名称，不可为 空
     * @param url      连接端点
     * @param username 用户名
     * @param password 密码
     * @param database 数据库名
     * @param source   greptimedb 客户端
     * @throws IllegalArgumentException 数据源名称为空时抛出
     */
    public GreptimeDbEngineDataSource(String name, String url, String username,
                                      String password, String database, GreptimeDB source) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("GreptimeDB 数据源名称不能为空");
        }
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.database = database;
        this.source = source;
    }

    /**
     * 名称
     *
     * @return 数据源名称
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * 获取客户端
     *
     * @return gRPC 客户端，已关闭时为 空
     */
    @Override
    public GreptimeDB getSource() {
        return source;
    }

    /**
     * 设置客户端：仅接受 {@link GreptimeDB} 实例，关闭后禁止再注入。
     *
     * @param source 源
     * @return 设置源的结果
     * @throws IllegalStateException    数据源已关闭时抛出
     * @throws IllegalArgumentException 源类型非法或为 空 时抛出
     */
    @Override
    public EngineDataSource<GreptimeDB> setSource(Object source) {
        if (closed) {
            throw new IllegalStateException("GreptimeDB 数据源已关闭，不可重新注入客户端: " + name);
        }
        if (!(source instanceof GreptimeDB client)) {
            throw new IllegalArgumentException("GreptimeDB 数据源仅接受 GreptimeDB 客户端，实际为: "
                    + (source == null ? "null" : source.getClass().getName()));
        }
        this.source = client;
        return this;
    }

    /**
     * 获取方言：优先 SPI 注册实现，未注册时回退本模块 {@link GreptimeDialect}。
     *
     * @return 方言实例，非 空
     */
    @Override
    public Dialect getDialect() {
        Dialect current = dialect;
        if (current == null) {
            Dialect spi = null;
            try {
                spi = Dialect.getExtension(DIALECT_KEY);
            } catch (Exception e) {
                log.warn("GreptimeDB 方言 SPI 解析失败，回退内置实现: key={}", DIALECT_KEY, e);
            }
            current = spi == null ? new GreptimeDialect() : spi;
            dialect = current;
        }
        return current;
    }

    /**
     * 设置方言。
     *
     * @param dialect 方言实例，不可为 空
     * @return 设置方言的结果
     * @throws IllegalArgumentException 方言为 空 时抛出
     */
    @Override
    public EngineDataSource<GreptimeDB> setDialect(Dialect dialect) {
        if (dialect == null) {
            throw new IllegalArgumentException("GreptimeDB 方言不能为空，如需恢复默认请传入 new GreptimeDialect()");
        }
        this.dialect = dialect;
        return this;
    }

    /**
     * 连接端点
     *
     * @return gRPC 端点串
     */
    @Override
    public String url() {
        return url;
    }

    /**
     * 用户名
     *
     * @return 用户名，可为 空
     */
    @Override
    public String username() {
        return username;
    }

    /**
     * 密码
     *
     * @return 密码，可为 空
     */
    @Override
    public String password() {
        return password;
    }

    /**
     * 数据库名
     *
     * @return 数据库名，可为 空（引擎侧回退 {@code public}）
     */
    @Override
    public String database() {
        return database;
    }

    /**
     * 是否已关闭。
     *
     * @return true 表示客户端已释放
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 关闭客户端：释放 gRPC 通道并置空引用，阻止关闭后继续使用。
     * <p>{@code GreptimeDB} 只提供 {@code shutdownGracefully()} 而非
     * {@link AutoCloseable}，故此处不复用接口默认的关闭分支。</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        GreptimeDB client = source;
        source = null;
        if (client == null) {
            return;
        }
        try {
            client.shutdownGracefully();
        } catch (Exception e) {
            log.warn("GreptimeDB 数据源关闭失败: name={}, cause={}", name, e.getMessage());
        }
    }
}
