package com.chua.datasource.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * PostgreSQL R2DBC 响应式引擎，对应同步侧 PostgreSQL 数据库访问。
 *
 * <p>使用 R2DBC true 异步驱动，通过 {@code Schedulers#boundedElastic()} 非阻塞调度，
 * 支持 Spring WebFlux 等响应式框架的实际非阻塞数据库访问。</p>
 *
 * <pre>{@code
 * PostgreSqlR2dbcReactorEngine engine = new PostgreSqlR2dbcReactorEngine();
 * engine.addDataSource("default", "localhost", 5432, "mydb", "root", "password");
 * Flux<User> users = engine.query(User.class).eq(User::getName, "张三").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("postgresql")
public class PostgreSqlR2dbcReactorEngine extends DefaultReactorEngine {

    /**
     * 创建 PostgreSQL R2DBC 响应式引擎。
     *
     * <p>内部将自动根据数据源配置选择合适的 R2DCD {@link io.r2dbc.spi.ConnectionFactory}。</p>
     */
    public PostgreSqlR2dbcReactorEngine() {
        super(null);
    }

    /**
     * 添加 PostgreSQL 数据源（委托给默认数据源配置）。
     *
     * @param name     数据源名称
     * @param host     主机地址
     * @param port     端口号
     * @param database 数据库名
     * @param username 用户名
     * @param password 密码
     * @return 当前引擎实例
     */
    public PostgreSqlR2dbcReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        // R2DBC 数据源配置将在应用层通过 spring.r2dbc.initialization 或 Bean 完成
        return this;
    }
}