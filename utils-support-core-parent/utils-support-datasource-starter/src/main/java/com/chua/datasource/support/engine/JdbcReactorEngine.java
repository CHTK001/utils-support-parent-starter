package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;

/**
 * JDBC 响应式引擎抽象基类，对应同步侧的 {@link JdbcEngine}。
 *
 * <p>为 JDBC 类数据源（MySQL / SQLite / DuckDB 等）提供响应式包装的公共基类。</p>
 * <p>已集成 R2DBC 响应式驱动，支持真正的非阻塞数据库访问。</p>
 *
 * <p>子类（如 {@code MysqlReactorEngine}）通过 {@code @Spi} 注册，
 * 复用同步引擎的数据源配置能力，仅提供响应式访问入口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class JdbcReactorEngine extends DefaultReactorEngine {

    /**
     * 用同步 JDBC 引擎构造响应式包装。
     *
     * @param delegate 同步 JDBC 引擎
     */
    protected JdbcReactorEngine(Engine delegate) {
        super(delegate);
    }
}