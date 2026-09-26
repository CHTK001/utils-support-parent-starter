package com.chua.common.support.lang.datasource.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.ddl.DslManager;
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
import java.util.Set;
import java.util.function.Supplier;

/**
 * 引擎接口，是数据源管理和 Lambda 链式操作的核心入口。
 * <p>
 * Engine 负责以下职责：
 * <ul>
 *   <li><b>数据源管理</b> — 通过 {@link #addDataSource(String, EngineDataSource)} 注册多种类型的数据源，
 *       通过 {@link #dataSourceNames()} / {@link #hasDataSource(String)} / {@link #removeDataSource(String)}
 *       盘点与摘除</li>
 *   <li><b>Lambda 链式查询</b> — 通过 {@link #query(Class)} 创建 LambdaQueryWrapper，流式构建查询条件</li>
 *   <li><b>Lambda 链式更新</b> — 通过 {@link #update(Class)} 创建 LambdaUpdateWrapper</li>
 *   <li><b>Lambda 链式删除</b> — 通过 {@link #delete(Class)} 创建 LambdaDeleteWrapper</li>
 *   <li><b>原生语句执行</b> — 通过 {@link #querySql(String, Object...)} / {@link #executeSql(String, Object...)}
 *       执行 SQL 或对应方言语句，并派生 {@link #queryOne}、{@link #queryValue}、{@link #count(String, Object...)}、
 *       {@link #exists(String, Object...)}、{@link #queryPage(String, int, int, Object...)}、{@link #batchSql} 便捷方法</li>
 *   <li><b>插入写入</b> — 通过 {@link #insert(Class, Object)} / {@link #insertBatch(Class, List)} 落库实体</li>
 *   <li><b>SQL 执行</b> — 通过 {@link #getExecutor()} / {@link #getExecutor(String)} 获取 JDBC 执行器</li>
 *   <li><b>数据库迁移</b> — 通过 {@link #flyway()} 获取 SQL 脚本版本化迁移工具</li>
 *   <li><b>元数据与隧道</b> — 通过 {@link #meta()} 操作表、视图、索引等，通过
 *       {@link #openTunnel(String, com.chua.common.support.network.tunnel.Tunnel)} 开启 SSH 隧道</li>
 * </ul>
 * </p>
 * <p>
 * <b>能力探测</b>：并非所有引擎都支持全部能力。使用前先问
 * {@link #supportsSql()}（是否支持原生语句）与 {@link #supportsMeta()}（是否支持元数据操作），
 * 不要用 {@code getExecutor() == null} 之类的实现细节代替判断。
 * </p>
 * <p>
 * <b>拦截器</b>：所有原生语句与 Lambda 终端执行都会回调 {@link #interceptors()} 返回的
 * {@link EngineInterceptor}（{@code before*} → 执行 → {@code after*}/{@code onError}），
 * 业务方无需在引擎里织入埋点。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // SPI 创建引擎（键为 META-INF/extensions 中登记的方言名，如 mysql / h2 / postgresql）
 * Engine engine = Engine.create("mysql");
 *
 * // 或者创建并一次配置完成（扩展键未注册时立即抛出可定位异常）
 * Engine memory = Engine.create("memory", e -> e.store("city", List.of(city(1, "Beijing"))));
 *
 * // 添加 JDBC 数据源（Dialect 由数据源封装携带，方言为 null 时回落为仅 SQL 能力）
 * HikariDataSource ds = new HikariDataSource();
 * ds.setJdbcUrl("jdbc:mysql://localhost:3306/mydb");
 * ds.setUsername("root");
 * ds.setPassword("123456");
 * engine.addDataSource("default", new MyEngineDataSource("default", ds, Dialect.getExtension("mysql")));
 *
 * // 添加非 SQL 数据源（Redis / MongoDB / ES 等，方言传 null）
 * engine.addDataSource("cache", new RedisEngineDataSource("cache", new RedisClient("localhost", 6379)));
 *
 * // 数据源盘点
 * boolean hasDefault = engine.hasDataSource("default");
 * Dialect defaultDialect = engine.getDialect();
 *
 * // 插入
 * int inserted = engine.insert(User.class, new User(1L, "张三", 18));
 * int batch = engine.insertBatch(User.class, List.of(u1, u2, u3));
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
 * // Lambda 条件计数（SQL 引擎下推 COUNT(*)，不回载明细行）
 * long total = engine.count(engine.query(User.class).eq(User::getAge, 18));
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
 * // 原生语句执行
 * List<Map<String, Object>> rows = engine.querySql("select * from user where age > ?", 18);
 * User one = engine.queryOne("select * from user where id = ?", User.class, 1L);
 * long n = engine.count("select count(*) from user where age > ?", 18);
 * boolean any = engine.exists("select id from user where age > ?", 18);
 * List<Map<String, Object>> pageRows = engine.queryPage("select * from user order by id", 2, 10);
 * int[] affected = engine.batchSql("insert into user(id, name) values(?, ?)",
 *         List.of(new Object[]{4L, "赵六"}, new Object[]{5L, "孙七"}));
 *
 * // 迁移与元数据
 * engine.flyway().location("classpath:db/init").migrateInit();
 * engine.meta();
 *
 * // 关闭（幂等）
 * engine.close();
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
     *
     * <p>本方法是所有原生写入的<b>规范入口</b>：参数名为 ql（查询语言），
     * SQL 引擎执行 SQL，NoSQL 引擎执行对应方言；{@link #execute(String, Object...)}
     * 只是它的别名。</p>
     *
     * <p>默认实现通过 {@link #getExecutor()} 代理，因此只覆盖
     * {@link #getExecutor()} 即可获得完整能力；具备自有语句通道但没有执行器的引擎
     * （内存引擎、文件引擎、文档/搜索引擎等）必须覆盖本方法，
     * 否则 {@link #supportsSql()} 与实际能力将出现偏差。</p>
     *
     * <p>执行前后依次回调 {@link #interceptors()} 扩展的
     * {@code beforeUpdate / afterUpdate / onError}。</p>
     *
     * @param ql     数据操作语句
     * @param params 参数
     * @return 受影响行数
     * @throws UnsupportedOperationException 引擎不支持原生写入时抛出
     */
    default int executeSql(String ql, Object... params) {
        SqlExecutor e = getExecutor();
        if (e == null) {
            UnsupportedOperationException ex = new UnsupportedOperationException("当前引擎不支持数据操作: " + getClass().getName());
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.onError(ql, params, ex);
            }
            throw ex;
        }
        return interceptUpdate(ql, params, () -> e.execute(ql, params));
    }

    /**
     * 执行数据操作语句（INSERT / UPDATE / DELETE / CREATE 等）。
     *
     * @param ql     数据操作语句
     * @param params 参数
     * @return 受影响行数
     * @see #executeSql(String, Object...)
     */
    default int execute(String ql, Object... params) {
        return executeSql(ql, params);
    }

    /**
     * 执行原生查询语句，返回 Map 行列表。
     *
     * <p>本方法是所有原生查询的<b>规范入口</b>；参数名为 ql（查询语言），
     * SQL 引擎执行 SQL，NoSQL 引擎执行对应方言；
     * {@link #query(String, Object...)} 只是它的别名。</p>
     *
     * <p>默认实现通过 {@link #getExecutor()} 代理，因此只覆盖
     * {@link #getExecutor()} 即可获得完整能力；具备自有语句通道但没有执行器的引擎
     * （内存引擎、文件引擎、文档/搜索引擎等）必须覆盖本方法，
     * 否则 {@link #supportsSql()} 与实际能力将出现偏差。</p>
     *
     * <p>执行前后依次回调 {@link #interceptors()} 扩展的
     * {@code beforeQuery / afterQuery / onError}。</p>
     *
     * @param ql     查询语句
     * @param params 参数
     * @return 查询结果行列表
     * @throws UnsupportedOperationException 引擎不支持原生查询时抛出
     */
    default List<Map<String, Object>> querySql(String ql, Object... params) {
        SqlExecutor e = getExecutor();
        if (e == null) {
            UnsupportedOperationException ex = new UnsupportedOperationException("当前引擎不支持 SQL 查询: " + getClass().getName());
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.onError(ql, params, ex);
            }
            throw ex;
        }
        return interceptQuery(ql, params, () -> e.query(ql, params));
    }

    /**
     * 执行原生查询语句，返回 Map 行列表。
     *
     * @param ql     查询语句
     * @param params 参数
     * @return 查询结果行列表
     * @see #querySql(String, Object...)
     */
    default List<Map<String, Object>> query(String ql, Object... params) {
        return querySql(ql, params);
    }

    /**
     * 执行原生查询语句，自动映射为指定类型的对象列表。
     *
     * <p>本方法是所有类型化原生查询的<b>规范入口</b>；
     * {@link #query(String, Class, Object...)} 只是它的别名。</p>
     *
     * <p>默认实现通过 {@link #getExecutor()} 代理，非 SQL 引擎应覆盖本方法。
     * 执行前后依次回调 {@link #interceptors()} 扩展的
     * {@code beforeQuery / afterQuery / onError}。</p>
     *
     * @param ql      查询语句
     * @param rowType 行类型
     * @param params  参数
     * @param <T>     行类型参数
     * @return 类型化结果列表
     * @throws UnsupportedOperationException 引擎不支持原生查询时抛出
     */
    default <T> List<T> querySql(String ql, Class<T> rowType, Object... params) {
        SqlExecutor e = getExecutor();
        if (e == null) {
            UnsupportedOperationException ex = new UnsupportedOperationException("当前引擎不支持 SQL 查询: " + getClass().getName());
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.onError(ql, params, ex);
            }
            throw ex;
        }
        return interceptQuery(ql, params, () -> e.query(ql, rowType, params));
    }

    /**
     * 执行原生查询语句，自动映射为指定类型的对象列表。
     *
     * @param ql      查询语句
     * @param rowType 行类型
     * @param params  参数
     * @param <T>     行类型参数
     * @return 类型化结果列表
     * @see #querySql(String, Class, Object...)
     */
    default <T> List<T> query(String ql, Class<T> rowType, Object... params) {
        return querySql(ql, rowType, params);
    }

    /**
     * 查询单行并映射为指定类型。
     *
     * <p>命中多行时返回第一行，无命中时返回 null。本方法不自行追加 {@code LIMIT 1}，
     * 截断方式由各引擎决定；需要严格单行语义时请在 ql 中自行限定。</p>
     *
     * @param ql      查询语句
     * @param rowType 行类型
     * @param params  参数
     * @param <T>     行类型参数
     * @return 映射后的对象，无命中返回 null
     */
    default <T> T queryOne(String ql, Class<T> rowType, Object... params) {
        List<T> rows = querySql(ql, rowType, params);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.getFirst();
    }

    /**
     * 查询单行单列的原始值，等价于取结果集第一行第一列。
     *
     * <p>适用于 {@code SELECT count(*)}、{@code SELECT max(id)} 这类标量查询。
     * 列标签的映射规则由各引擎决定，调用方不应依赖具体标签名。</p>
     *
     * @param ql     查询语句
     * @param params 参数
     * @return 首行首列值，无结果或首行为空时返回 null
     */
    default Object queryValue(String ql, Object... params) {
        List<Map<String, Object>> rows = querySql(ql, params);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, Object> first = rows.getFirst();
        if (first == null || first.isEmpty()) {
            return null;
        }
        return first.values().iterator().next();
    }

    /**
     * 统计原生查询结果行数。
     *
     * <p>要求 ql 返回单行单列的数值（如 {@code SELECT count(*) FROM t WHERE ...}）。
     * 需要"对 Lambda 条件计数"时请用 Lambda 包装器的 {@code count()}，
     * 那种写法能由 SQL 引擎下推 {@code COUNT(*)}，不必把明细行拉回内存。</p>
     *
     * @param ql     计数查询语句
     * @param params 参数
     * @return 首行首列数值，无结果时返回 0
     * @throws NumberFormatException 首列不是数值时抛出
     */
    default long count(String ql, Object... params) {
        Object value = queryValue(ql, params);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(text);
    }

    /**
     * 判断原生查询是否至少命中一行。
     *
     * @param ql     查询语句
     * @param params 参数
     * @return 命中至少一行返回 true
     */
    default boolean exists(String ql, Object... params) {
        List<Map<String, Object>> rows = querySql(ql, params);
        return rows != null && !rows.isEmpty();
    }

    /**
     * 批量执行同一条语句，仅参数不同。
     *
     * <p>默认实现逐条调用 {@link #executeSql(String, Object...)}；
     * 有批量通道的引擎（JDBC 引擎）应覆盖为一次提交，避免逐条网络往返。</p>
     *
     * @param ql          语句模板
     * @param batchParams 每组参数，null 或空列表返回空数组
     * @return 每组参数对应的受影响行数
     * @throws UnsupportedOperationException 引擎不支持原生写入时抛出
     */
    default int[] batchSql(String ql, List<Object[]> batchParams) {
        if (batchParams == null || batchParams.isEmpty()) {
            return new int[0];
        }
        int[] affected = new int[batchParams.size()];
        for (int i = 0; i < batchParams.size(); i++) {
            Object[] params = batchParams.get(i);
            affected[i] = executeSql(ql, params == null ? new Object[0] : params);
        }
        return affected;
    }

    /**
     * 分页执行原生查询，返回 Map 行列表。
     *
     * <p>SQL 引擎走数据库物理分页（由方言渲染 LIMIT / OFFSET）；
     * 无执行器的引擎回退为全量查询后内存截取。</p>
     *
     * @param ql       查询语句
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param params   参数
     * @return 当前页数据行列表
     * @throws IllegalArgumentException 页码或每页条数不大于 0 时抛出
     */
    default List<Map<String, Object>> queryPage(String ql, int pageNum, int pageSize, Object... params) {
        if (pageNum < 1) {
            throw new IllegalArgumentException("页码必须大于 0");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("每页条数必须大于 0");
        }
        SqlExecutor e = getExecutor();
        if (e != null) {
            com.chua.common.support.lang.datasource.dialect.Pagination pagination =
                    new com.chua.common.support.lang.datasource.dialect.Pagination()
                            .setPageNum(pageNum).setPageSize(pageSize);
            return interceptQuery(ql, params, () -> e.queryPage(ql, pagination, params));
        }
        List<Map<String, Object>> all = querySql(ql, params);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int from = (pageNum - 1) * pageSize;
        if (from >= all.size()) {
            return List.of();
        }
        int to = Math.min(from + pageSize, all.size());
        // 独立副本，避免 subList 视图持有底层存储引用
        return new java.util.ArrayList<>(all.subList(from, to));
    }

    /**
     * 判断当前引擎是否支持原生 SQL/方言语句执行。
     *
     * <p>本方法只回答"能否执行 {@link #querySql(String, Object...)} /
     * {@link #executeSql(String, Object...)}"，不回答"是否 JDBC"：
     * 内存引擎、文件引擎没有 {@link SqlExecutor}，但完整支持 SQL 语法，
     * 因此必须由这些引擎显式覆盖返回 true，否则调用方会误判能力缺失。</p>
     *
     * @return true 表示支持原生语句执行
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
     * <p>本方法在每条语句的 before/after/error 三个环节都会被调用，基类实现应覆盖为
     * 首次加载后缓存，避免每次执行都重扫 SPI 注册表。</p>
     *
     * @return 拦截器列表（非 null）
     */
    default List<EngineInterceptor> interceptors() {
        List<EngineInterceptor> interceptors = ServiceProvider.of(EngineInterceptor.class)
                .getNewExtensions(EngineInterceptor.SPI_NAME, this);
        return interceptors == null ? List.of() : interceptors;
    }

    /**
     * 在拦截器回调包裹下执行一次查询操作。
     *
     * <p>统一封装 {@code beforeQuery / afterQuery / onError} 三段回调，供本接口的
     * 默认查询方法与各引擎实现的内存查询路径共用，避免同一套回调逻辑在多处重复实现。
     * 回调顺序为：{@code beforeQuery} → 执行 → {@code afterQuery}；
     * 执行过程抛出 {@link RuntimeException} 时改为回调 {@code onError} 并原样抛出。</p>
     *
     * @param ql    查询语句或 WHERE 条件子句，不能为 null
     * @param params 绑定参数，不能为 null
     * @param action 实际查询动作，不能为 null
     * @param <T>    结果元素类型
     * @return {@code action} 的执行结果（非 null 由 action 保证）
     */
    default <T> List<T> interceptQuery(String ql, Object[] params, Supplier<List<T>> action) {
        for (EngineInterceptor interceptor : interceptors()) {
            interceptor.beforeQuery(ql, params);
        }
        try {
            List<T> result = action.get();
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.afterQuery(ql, params, result);
            }
            return result;
        } catch (RuntimeException re) {
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.onError(ql, params, re);
            }
            throw re;
        }
    }

    /**
     * 在拦截器回调包裹下执行一次数据更新操作（含删除与原生数据操作语句）。
     *
     * <p>统一封装 {@code beforeUpdate / afterUpdate / onError} 三段回调，供本接口的
     * 默认数据操作方法与各引擎实现的内存更新路径共用，避免同一套回调逻辑在多处重复实现。
     * 回调顺序为：{@code beforeUpdate} → 执行 → {@code afterUpdate}；
     * 执行过程抛出 {@link RuntimeException} 时改为回调 {@code onError} 并原样抛出。</p>
     *
     * @param ql     更新语句或 WHERE 条件子句，不能为 null
     * @param params 绑定参数，不能为 null
     * @param action 实际更新动作，不能为 null
     * @return {@code action} 返回的受影响行数
     */
    default int interceptUpdate(String ql, Object[] params, Supplier<Integer> action) {
        for (EngineInterceptor interceptor : interceptors()) {
            interceptor.beforeUpdate(ql, params);
        }
        try {
            int affected = action.get();
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.afterUpdate(ql, params, affected);
            }
            return affected;
        } catch (RuntimeException re) {
            for (EngineInterceptor interceptor : interceptors()) {
                interceptor.onError(ql, params, re);
            }
            throw re;
        }
    }

    /**
     * 获取数据库迁移工具，提供类似 Flyway 的 SQL 脚本版本化管理。
     * <p>获取逻辑：通过 {@code ServiceProvider.of(Flyway.class).getNewExtensions(SPI_NAME, this)}
     * 获取 {@code flyway} 扩展点实现（结果列表按 SPI order 降序排列，首个即最高优先级）；
     * 构造上下文为本引擎，故只有能以 {@code Engine} 为参数构造的实现会被选中：默认实现
     * {@link DefaultFlyway}，或业务方注册的更高 order 扩展。SPI 无可用实例时兜底返回
     * {@link DefaultFlyway}。</p>
     *
     * <p>需要以 {@code DataSource} 为上下文时使用 {@code utils-support-flyway-starter} 的增强实现：
     * {@code ServiceProvider.of(Flyway.class).getNewExtensions(Flyway.SPI_NAME, dataSource)}，
     * 该上下文下它排序最前。</p>
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
     * 执行 Lambda 查询包装器并返回结果列表。
     *
     * <p>与包装器自身的 {@code list()} 等价，供只持有 {@code Engine} 引用
     * （而非包装器引用）的调用方使用，避免向下转型。</p>
     *
     * @param wrapper 查询包装器
     * @param <T>     实体类型
     * @return 查询结果列表
     * @throws IllegalArgumentException 包装器为 null 时抛出
     */
    default <T> List<T> executeQuery(LambdaQueryWrapper<T> wrapper) {
        if (wrapper == null) {
            throw new IllegalArgumentException("查询包装器不能为空");
        }
        return wrapper.list();
    }

    /**
     * 分页执行 Lambda 查询包装器。
     *
     * @param wrapper  查询包装器
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param <T>      实体类型
     * @return 分页结果
     * @throws IllegalArgumentException 包装器为 null 时抛出
     */
    default <T> com.chua.common.support.lang.datasource.page.Page<T> executePage(
            LambdaQueryWrapper<T> wrapper, int pageNum, int pageSize) {
        if (wrapper == null) {
            throw new IllegalArgumentException("查询包装器不能为空");
        }
        return wrapper.page(pageNum, pageSize);
    }

    /**
     * 统计 Lambda 查询条件命中的总行数（不含分页参数）。
     *
     * <p>SQL 引擎下推 {@code SELECT COUNT(*)}；内存引擎回退为执行查询后计数。</p>
     *
     * @param wrapper 查询包装器
     * @param <T>     实体类型
     * @return 总行数
     * @throws IllegalArgumentException 包装器为 null 时抛出
     */
    default <T> long count(LambdaQueryWrapper<T> wrapper) {
        if (wrapper == null) {
            throw new IllegalArgumentException("查询包装器不能为空");
        }
        return wrapper.count();
    }

    /**
     * 执行 Lambda 更新包装器。
     *
     * @param wrapper 更新包装器
     * @param <T>     实体类型
     * @return 受影响行数
     * @throws IllegalArgumentException 包装器为 null 时抛出
     */
    default <T> int executeUpdate(LambdaUpdateWrapper<T> wrapper) {
        if (wrapper == null) {
            throw new IllegalArgumentException("更新包装器不能为空");
        }
        return wrapper.update();
    }

    /**
     * 执行 Lambda 删除包装器。
     *
     * @param wrapper 删除包装器
     * @param <T>     实体类型
     * @return 受影响行数
     * @throws IllegalArgumentException 包装器为 null 时抛出
     */
    default <T> int executeDelete(LambdaDeleteWrapper<T> wrapper) {
        if (wrapper == null) {
            throw new IllegalArgumentException("删除包装器不能为空");
        }
        return wrapper.remove();
    }

    /**
     * 获取指定数据源的方言。
     *
     * @param dataSourceName 数据源名称
     * @return 方言实例，非 SQL 数据源返回 null
     */
    Dialect getDialect(String dataSourceName);

    /**
     * 获取默认数据源的方言。
     *
     * @return 方言实例，默认数据源未设置或为非 SQL 数据源时返回 null
     */
    default Dialect getDialect() {
        return getDialect(getDefaultDataSourceName());
    }

    /**
     * 判断指定名称的数据源是否已注册。
     *
     * @param name 数据源名称，null 返回 false
     * @return 已注册返回 true
     */
    default boolean hasDataSource(String name) {
        return name != null && getDataSource(name) != null;
    }

    /**
     * 获取默认数据源名称。
     *
     * @return 默认数据源名称
     */
    default String getDefaultDataSourceName() {
        return null;
    }

    /**
     * 列出全部已注册的数据源名称。
     *
     * <p>能力入口：多数据源引擎应覆盖返回不可变集合；单数据源引擎无需覆盖。</p>
     *
     * @return 数据源名称集合（不可变），默认抛出不支持异常
     * @throws UnsupportedOperationException 当前引擎不支持多数据源注册时抛出
     */
    default Set<String> dataSourceNames() {
        throw new UnsupportedOperationException("当前引擎不支持多数据源注册: " + getClass().getName());
    }

    /**
     * 摘除并关闭指定数据源。
     *
     * <p>能力入口：多数据源引擎应覆盖实现；被摘除的数据源需关闭底层连接。
     * 不存在的名称视为无操作，不抛异常。</p>
     *
     * @param name 数据源名称，不允许为 null
     * @return 引擎自身，便于链式调用
     * @throws IllegalArgumentException 名称为 null 时抛出
     * @throws UnsupportedOperationException 当前引擎不支持多数据源注册时抛出
     */
    default Engine removeDataSource(String name) {
        throw new UnsupportedOperationException("当前引擎不支持多数据源注册: " + getClass().getName());
    }

    /**
     * 判断引擎是否已关闭。
     *
     * <p>能力入口：可重复调用的引擎应覆盖返回真实状态；
     * 未覆盖时按 {@code false}（未关闭）处理。</p>
     *
     * @return true 表示已调用 {@link #close()}
     */
    default boolean isClosed() {
        return false;
    }

    /**
     * 插入单个实体。
     *
     * <p>能力入口：由引擎渲染 INSERT 语句并走自身语句通道执行，
     * 表名与列名须先经标识符白名单校验，值以占位符绑定。</p>
     *
     * @param entityClass 实体类类型，不允许为 null
     * @param entity      待插入实体，不允许为 null
     * @param <T>         实体类型
     * @return 受影响行数
     * @throws IllegalArgumentException       参数为 null 时抛出
     * @throws UnsupportedOperationException 当前引擎不支持插入时抛出
     */
    default <T> int insert(Class<T> entityClass, T entity) {
        throw new UnsupportedOperationException("当前引擎不支持插入: " + getClass().getName());
    }

    /**
     * 批量插入实体。
     *
     * <p>能力入口：默认逐条委托 {@link #insert(Class, Object)}；
     * 支持多行 INSERT 的引擎应覆盖为一次提交，减少网络往返。</p>
     *
     * @param entityClass 实体类类型，不允许为 null
     * @param entities    待插入实体列表，不允许为 null；为空时返回 0
     * @param <T>         实体类型
     * @return 受影响行数合计
     * @throws IllegalArgumentException       参数为 null 时抛出
     * @throws UnsupportedOperationException 当前引擎不支持插入时抛出
     */
    default <T> int insertBatch(Class<T> entityClass, List<T> entities) {
        if (entityClass == null || entities == null) {
            throw new IllegalArgumentException("实体类与实体列表不能为空");
        }
        int affected = 0;
        for (T entity : entities) {
            affected += insert(entityClass, entity);
        }
        return affected;
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
     * 获取 DDL / DSL 管理器入口。
     *
     * <p>通过 {@link DslManager} SPI 扩展点解析，调用方无需手工 {@code new} 实现类。
     * 解析顺序：先以默认数据源的 {@link Dialect#protocol()} 作为扩展键查找
     * 方言专属实现，未命中时回退到通用别名 {@link DslManager#DEFAULT_ALIAS}。
     * 解析成功后框架自动注入上下文参数（JDBC {@code DataSource} 与当前方言），
     * 实现方按需实现对应的感知接口。</p>
     *
     * <p>实现类必须支持无参构造：框架以无参方式实例化 SPI 实现，
     * 上下文通过上面的感知接口注入。</p>
     *
     * @return DDL 管理器实例
     * @throws UnsupportedOperationException 未注册任何 {@link DslManager} SPI 实现时抛出
     */
    default DslManager ddl() {
        throw new UnsupportedOperationException("该引擎不支持 DDL 管理");
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
     * 通过 SPI 创建引擎实例并完成初始化配置。
     *
     * <p>相比 {@link #create(String)} 后再手工装配，省去中间变量；
     * 且在扩展键未注册时立即抛出可定位的异常，而不是把 null 传给配置回调后以 NPE 收场。</p>
     *
     * @param type        SPI 扩展键（如 "jdbc"、"mysql"、"memory"）
     * @param initializer 初始化回调，接收新建的引擎；可为 null
     * @return 已完成初始化的引擎实例（非 null）
     * @throws IllegalStateException 扩展键未注册时抛出
     */
    static Engine create(String type, java.util.function.Consumer<Engine> initializer) {
        Engine engine = create(type);
        if (engine == null) {
            throw new IllegalStateException("未注册的引擎 SPI 扩展: " + type
                    + "，请检查 META-INF/extensions/" + SPI_NAME + " 登记文件");
        }
        if (initializer != null) {
            initializer.accept(engine);
        }
        return engine;
    }

    /**
     * 关闭引擎，释放所有已注册数据源的资源。
     */
    @Override
    void close();
}
