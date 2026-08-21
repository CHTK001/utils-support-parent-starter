package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import io.r2dbc.spi.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 *抽象 R2DBC 响应式引擎基类。
 *
 * <p>底层直接使用 {@link ConnectionFactory} 执行 R2DBC 异步 SQL，
 * 覆盖原生 SQL 查询/更新/批量方法为真正的非阻塞发布者（{@link Flux} / {@link Mono}），
 * 不再依赖 {@code boundedElastic} 调度阻塞 JDBC 调用。</p>
 *
 * <p>Lambda 链式 API 通过内部 {@link R2dbcEngine} 适配器 +
 * {@link ReactorLambdaQueryWrapper} 等实现 R2DBC 访问。</p>
 *
 * <p>通过 {@code @Spi} 注册后，用户可以使用
 * {@code ReactorEngine.create("mysql")} /
 * {@code ReactorEngine.create("postgresql")} 获取实例，
 * 再通过 {@link #addDataSource(String, ConnectionFactory) addDataSource}
 * 注入实际的 R2DBC 连接工厂。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public abstract class AbstractR2dbcReactorEngine implements ReactorEngine {

    /** R2DBC 连接工厂 */
    protected ConnectionFactory factory;

    /** 方言 */
    protected Dialect dialect;

    /** 内部同步引擎（用于 Lambda 包装器） */
    protected R2dbcEngine delegate;

    /**
     * 无参构造（SPI 使用），需后续通过 {@link #addDataSource} 设置连接工厂。
     */
    protected AbstractR2dbcReactorEngine() {
    }

    /**
     * 指定连接工厂与方言构造。
     *
     * @param factory R2DBC 连接工厂
     * @param dialect 方言，可为 null
     */
    protected AbstractR2dbcReactorEngine(ConnectionFactory factory, Dialect dialect) {
        this.factory = factory;
        this.dialect = dialect;
        this.delegate = factory != null ? new R2dbcEngine(factory, dialect) : null;
    }

    /**
     * 添加 R2DBC 数据源（命名）。
     *
     * @param name      数据源名称
     * @param factory   R2DBC 连接工厂
     * @return this
     */
    public AbstractR2dbcReactorEngine addDataSource(String name, ConnectionFactory factory) {
        this.factory = factory;
        this.delegate = new R2dbcEngine(factory, this.dialect);
        return this;
    }

    /**
     * 添加 R2DBC 数据源并指定方言。
     *
     * @param name      数据源名称
     * @param factory   R2DBC 连接工厂
     * @param dialect   方言
     * @return this
     */
    public AbstractR2dbcReactorEngine addDataSource(String name, ConnectionFactory factory, Dialect dialect) {
        this.factory = factory;
        this.dialect = dialect;
        this.delegate = new R2dbcEngine(factory, dialect);
        return this;
    }

    /**
     * 获取 R2DBC 连接工厂。
     *
     * @return 连接工厂
     */
    public ConnectionFactory getConnectionFactory() {
        return factory;
    }

    /**
     * 获取方言。
     *
     * @return 方言
     */
    public Dialect getDialect() {
        return dialect;
    }

    // ==================== Lambda 链式 API ====================

    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        assertFactory();
        return new ReactorLambdaQueryWrapper<>(delegate, entityClass);
    }

    @Override
    public <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass) {
        assertFactory();
        return new ReactorLambdaUpdateWrapper<>(delegate, entityClass);
    }

    @Override
    public <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        assertFactory();
        return new ReactorLambdaDeleteWrapper<>(delegate, entityClass);
    }

    // ==================== 原生 SQL — 真正响应式 ====================

    /**
     * 从 R2DBC 连接工厂获取单条连接，执行查询并在结束后关闭连接。
     *
     * @param sql    SQL 语句
     * @param params 参数
     * @return 行 Map 列表的 Flux
     */
    @Override
    public Flux<Map<String, Object>> query(String sql, Object... params) {
        assertFactory();
        return Flux.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.from(executeStatement(conn, sql, params))
                        .flatMap(result -> Flux.from(result.map(this::toMap))),
                Connection::close);
    }

    /**
     * R2DBC 类型化查询。
     *
     * @param sql     SQL 语句
     * @param rowType 行类型
     * @param params  参数
     * @param <T>     行类型
     * @return 类型化结果 Flux
     */
    @Override
    public <T> Flux<T> query(String sql, Class<T> rowType, Object... params) {
        assertFactory();
        return Flux.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.from(executeStatement(conn, sql, params))
                        .flatMap(result -> Flux.from(result.map((row, meta) -> toObject(row, rowType)))),
                Connection::close);
    }

    /**
     * R2DBC 更新（INSERT/UPDATE/DELETE），返回受影响行数的 Mono。
     *
     * @param sql    SQL 语句
     * @param params 参数
     * @return 受影响行数 Mono
     */
    @Override
    public Mono<Integer> execute(String sql, Object... params) {
        assertFactory();
        return Mono.usingWhen(
                Mono.from(factory.create()),
                conn -> {
                    Statement stmt = conn.createStatement(sql);
                    bindParams(stmt, params);
                    return Flux.from(stmt.execute())
                            .flatMap(Result::getRowsUpdated)
                            .reduce(0L, Long::sum);
                },
                Connection::close)
                .map(Long::intValue)
                .switchIfEmpty(Mono.just(0));
    }

    /**
     * R2DBC 批量操作。
     *
     * @param sql         SQL 模板
     * @param batchParams 批量参数
     * @return 每批影响行数 Flux
     */
    @Override
    public Flux<Integer> batch(String sql, List<Object[]> batchParams) {
        assertFactory();
        if (batchParams == null || batchParams.isEmpty()) {
            return Flux.empty();
        }
        return Flux.usingWhen(
                Mono.from(factory.create()),
                conn -> Flux.fromIterable(batchParams)
                        .flatMap(paramArray -> {
                            Statement stmt = conn.createStatement(sql);
                            bindParams(stmt, paramArray);
                            return Flux.from(stmt.execute())
                                    .flatMap(Result::getRowsUpdated)
                                    .reduce(0L, Long::sum);
                        })
                        .map(Long::intValue),
                Connection::close);
    }

    // ==================== R2DBC 辅助 ====================

    /**
     * 校验连接工厂已就绪。
     */
    private void assertFactory() {
        if (factory == null) {
            throw new IllegalStateException("R2DBC ConnectionFactory 未初始化，请通过 addDataSource 设置");
        }
    }

    /**
     * 绑定参数并执行语句。
     *
     * @param conn   连接
     * @param sql    SQL 语句
     * @param params 参数
     * @return Result 的发布者
     */
    private static Publisher<Result> executeStatement(Connection conn, String sql, Object[] params) {
        Statement stmt = conn.createStatement(sql);
        bindParams(stmt, params);
        return stmt.execute();
    }

    /**
     * 绑定参数到语句，索引从 0 开始（R2DBC 约定）。
     *
     * @param stmt   语句
     * @param params 参数
     */
    private static void bindParams(Statement stmt, Object[] params) {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            stmt.bind(i, params[i]);
        }
    }

    /**
     * 映射行到 Map。
     *
     * @param row       R2DBC 行
     * @param meta      行元数据
     * @return 列名 → 值的 Map
     */
    private Map<String, Object> toMap(Row row, RowMetadata meta) {
        Map<String, Object> rowMap = new java.util.LinkedHashMap<>();
        for (ColumnMetadata cm : meta.getColumnMetadatas()) {
            String name = cm.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            rowMap.put(name, row.get(name));
        }
        return rowMap;
    }

    /**
     * 映射行到实体对象。
     *
     * @param row       R2DBC 行
     * @param rowType   目标类型
     * @param <T>       实体类型
     * @return 映射后的对象
     */
    @SuppressWarnings("unchecked")
    private <T> T toObject(Row row, Class<T> rowType) {
        try {
            T instance = rowType.getDeclaredConstructor().newInstance();
            row.getMetadata().getColumnMetadatas().forEach(cm -> {
                String name = cm.getName();
                if (name == null || name.isEmpty()) {
                    return;
                }
                Object value = row.get(name);
                if (value != null) {
                    setFieldValue(instance, name, value);
                }
            });
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException("映射行到 " + rowType.getName() + " 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 反射设置对象字段值，兼容原列名与驼峰化列名。
     *
     * @param instance   目标对象
     * @param columnName 列名
     * @param value      列值
     */
    private static void setFieldValue(Object instance, String columnName, Object value) {
        List<String> candidates = List.of(columnName, toCamelCase(columnName));
        for (String candidate : candidates) {
            java.lang.reflect.Field field = findField(instance.getClass(), candidate);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object converted = com.chua.common.support.converter.Converter.convertIfNecessary(value, field.getType());
                if (converted != null) {
                    field.set(instance, converted);
                }
                return;
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}