// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcReactorEngine} 的 <b>JDBC 兜底 / 多数据源联邦</b> 行为约定。
 *
 * <p>{@code JdbcReactorEngine} 有两条彼此独立的同步执行路径，任何一条被漏测都会让
 * 「有 r2dbc 驱动」的环境通过、而「只有 JDBC 驱动」（如 sqlite）的生产环境静默失效：</p>
 * <ol>
 *   <li><b>纯 JDBC 兜底</b> — 数据源没有 r2dbc 连接工厂，全部语句走
 *       {@code queryViaJdbc / queryTypedViaJdbc / executeViaJdbc / batchViaJdbc}</li>
 *   <li><b>多数据源联邦</b> — {@link DataSourceConversion} SPI 把多个物理数据源合并成一个
 *       逻辑数据源，所有语句与 Lambda 包装器都必须指向这个统一数据源</li>
 * </ol>
 *
 * <p>测试通过「注册数据源后清空 r2dbc 工厂表」构造第一种场景：这等价于运行环境缺少
 * r2dbc 驱动（生产上 sqlite 就是这个状态），但保留了真实可用的 H2 JDBC 连接，
 * 因此断言的是真实读写结果，而不是异常文案。</p>
 * @author CH
 * @since 4.0.0.50
 */
class JdbcReactorEngineJdbcPathTest {

    /**
     * 内存库 URL：{@code DB_CLOSE_DELAY=-1} 让库在连接全部关闭后仍然存活。
     */
    private static final String URL = "jdbc:h2:mem:reactor_jdbc_path;DB_CLOSE_DELAY=-1";

    /**
     * 待关闭的引擎，统一在 {@link #tearDown()} 释放。
     */
    private JdbcReactorEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        // 联邦用例会临时注册 DataSourceConversion 默认实现；清掉 provider 缓存，
        // 让后续用例回到「classpath 上没有联邦实现」的初始状态。
        ServiceProvider.CACHE.remove(DataSourceConversion.class);
    }

    /**
     * 注册一个使用默认库的引擎，并保证表结构存在。
     *
     * @return 已完成建表与数据初始化的引擎
     */
    private JdbcReactorEngine newEngine() {
        JdbcReactorEngine e = new JdbcReactorEngine();
        e.addDataSource("h2", URL, "sa", null);
        e.execute("CREATE TABLE IF NOT EXISTS jp_user ("
                + "id INT PRIMARY KEY, name VARCHAR(64), age INT, email VARCHAR(128))").block();
        e.execute("DELETE FROM jp_user").block();
        e.execute("INSERT INTO jp_user VALUES "
                + "(1, '张三', 18, 'zhangsan@example.com'),"
                + "(2, '李四', 25, 'lisi@example.com'),"
                + "(3, '王五', NULL, 'wangwu@example.com'),"
                + "(4, '赵六', 30, 'zhaoliu@example.com'),"
                + "(5, '李七', 35, NULL)").block();
        return e;
    }

    /**
     * 清空引擎内已注册的 r2dbc 连接工厂，模拟「classpath 上没有对应 r2dbc 驱动」。
     *
     * <p>生产环境（如 sqlite）注册 JDBC 数据源时 {@code buildConnectionFactory} 会失败并静默降级，
     * 结果是工厂表里没有该数据源。测试无法制造「缺驱动」，因此直接在注册后清空工厂表，
     * 得到完全相同的内部状态：{@code jdbcDataSources} 有值、{@code r2dbcFactories} 为空。</p>
     *
     * @param target 目标引擎
     */
    @SuppressWarnings("unchecked")
    private static void dropR2dbcFactories(JdbcReactorEngine target) {
        try {
            Field field = JdbcReactorEngine.class.getDeclaredField("r2dbcFactories");
            field.setAccessible(true);
            ((Map<String, ConnectionFactory>) field.get(target)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法清空 r2dbc 工厂表", e);
        }
    }

    @Test
    @DisplayName("纯 JDBC 路径：query / 类型化 query / execute / batch 全部真实读写")
    void jdbcOnlyRawSql() {
        engine = newEngine();
        dropR2dbcFactories(engine);
        assertNull(engine.getR2dbcFactory("h2"), "工厂表已清空，语句必须走 JDBC 兜底");

        List<Map<String, Object>> rows = engine.query(
                "SELECT * FROM jp_user WHERE age >= ? ORDER BY age", 25).collectList().block();
        assertNotNull(rows);
        assertEquals(3, rows.size());
        assertEquals("李四", rows.getFirst().get("NAME"));

        List<JUser> typed = engine.query(
                "SELECT * FROM jp_user WHERE name LIKE ?", JUser.class, "%王%")
                .collectList().block();
        assertNotNull(typed);
        assertEquals(1, typed.size());
        assertEquals(3, typed.getFirst().getId());
        assertEquals("王五", typed.getFirst().getName());

        assertEquals(1, engine.execute(
                "UPDATE jp_user SET email = ? WHERE id = ?", "a@b.c", 5).block());
        assertEquals(0, engine.execute(
                "UPDATE jp_user SET email = ? WHERE id = ?", "x", 999).block());

        List<Integer> batch = engine.batch("UPDATE jp_user SET age = ? WHERE id = ?",
                List.of(new Object[]{50, 1}, new Object[]{60, 2})).collectList().block();
        assertNotNull(batch);
        assertEquals(List.of(1, 1), batch);

        // 空批次不建连接、不发元素
        assertTrue(engine.batch("UPDATE jp_user SET age = ? WHERE id = ?", List.of())
                .collectList().block().isEmpty());
        assertTrue(engine.batch("UPDATE jp_user SET age = ? WHERE id = ?", null)
                .collectList().block().isEmpty());
    }

    @Test
    @DisplayName("纯 JDBC 路径：Lambda 包装器查询/写库走 JdbcSqlExecutorWrapper")
    void jdbcOnlyLambda() {
        engine = newEngine();
        dropR2dbcFactories(engine);

        SqlExecutor executor = engine.getExecutor();
        assertNotNull(executor, "JDBC 兜底时也必须提供执行器");

        List<JUser> all = engine.query(JUser.class).orderByAsc(JUser::getId).list().collectList().block();
        assertNotNull(all);
        assertEquals(List.of(1, 2, 3, 4, 5), all.stream().map(JUser::getId).toList());

        List<JUser> filtered = engine.query(JUser.class)
                .gt(JUser::getAge, 20)
                .orderByDesc(JUser::getAge)
                .limit(2)
                .list().collectList().block();
        assertNotNull(filtered);
        assertEquals(List.of(5, 4), filtered.stream().map(JUser::getId).toList());

        assertEquals(3, engine.query(JUser.class).gt(JUser::getAge, 20).count().block());

        Page<JUser> second = engine.query(JUser.class)
                .orderByAsc(JUser::getId).page(2, 2).block();
        assertNotNull(second);
        assertEquals(5, second.getTotal());
        assertEquals(List.of(3, 4), second.getRecords().stream().map(JUser::getId).toList());

        assertEquals(1, engine.update(JUser.class)
                .set(JUser::getAge, 45).eq(JUser::getId, 4).update().block());
        assertEquals(45, engine.query(JUser.class).eq(JUser::getId, 4).one().block().getAge());

        assertEquals(1, engine.delete(JUser.class).eq(JUser::getId, 1).remove().block());
        assertEquals(4, engine.query(JUser.class).count().block());
    }

    @Test
    @DisplayName("JdbcSqlExecutorWrapper：分页回填 total，count 失败时 total 记 0 而不是整体失败")
    void jdbcExecutorPage() {
        engine = newEngine();
        dropR2dbcFactories(engine);
        SqlExecutor executor = engine.getExecutor();
        assertNotNull(executor);

        // 分页回填 total：pageNum=2 返回第 3、4 行
        Pagination pagination = new Pagination().setPageNum(2).setPageSize(2);
        List<Map<String, Object>> rows = executor.queryPage(
                "SELECT * FROM jp_user ORDER BY id", pagination);
        assertEquals(5, pagination.getTotal());
        assertEquals(2, rows.size());
        assertEquals(3, ((Number) rows.getFirst().get("ID")).intValue());

        // 计数失败（表不存在）时不应把整个分页查询打断在 total 上，真正的查询错误照常抛出
        Pagination broken = new Pagination().setPageNum(1).setPageSize(2);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> executor.queryPage("SELECT * FROM jp_no_such_table", broken));
        assertTrue(e.getMessage().contains("查询失败"));
        assertEquals(0, broken.getTotal());

        int[] batch = executor.batch("UPDATE jp_user SET age = ? WHERE id = ?",
                List.of(new Object[]{70, 2}, new Object[]{80, 3}));
        assertEquals(2, batch.length);
    }

    @Test
    @DisplayName("createJdbcDataSource：带凭据走 Properties，无凭据直连；元数据方法有定义")
    void jdbcDataSourceFromCredentials() throws Exception {
        // 每个引擎只注册一个数据源：多数据源会触发联邦装配，与本用例要验证的匿名实现无关
        // 用独立的库名：H2 内存库的凭据由首次连接决定，与共享库混用会互相污染
        JdbcReactorEngine withUserEngine = new JdbcReactorEngine();
        withUserEngine.addDataSource("withUser",
                "jdbc:h2:mem:reactor_cred;DB_CLOSE_DELAY=-1", "sa", "sa");
        // 存在 r2dbc 连接工厂时 getDataSource 暴露的是工厂；清空工厂后才会暴露 JDBC DataSource
        dropR2dbcFactories(withUserEngine);
        DataSource withUser = (DataSource) withUserEngine.getDataSource("withUser").getSource();
        assertNotNull(withUser);
        try (var conn = withUser.getConnection()) {
            assertTrue(conn.isValid(2));
        }
        try (var conn = withUser.getConnection("sa", "sa")) {
            assertTrue(conn.isValid(2));
        }
        assertNull(withUser.unwrap(DataSource.class));
        assertFalse(withUser.isWrapperFor(DataSource.class));
        assertNull(withUser.getLogWriter());
        withUser.setLogWriter(new PrintWriter(System.out)); // [P3C E1 豁免]
        withUser.setLoginTimeout(1);
        assertEquals(0, withUser.getLoginTimeout());
        assertNull(withUser.getParentLogger());
        withUserEngine.close();

        // 无用户名时不走 Properties，直接 DriverManager.getConnection(url)
        // 同样是独立库名：H2 内存库的用户名在首次连接时固定，混用会让后续连接被拒
        JdbcReactorEngine noUserEngine = new JdbcReactorEngine();
        noUserEngine.addDataSource("noUser",
                "jdbc:h2:mem:reactor_nocred;DB_CLOSE_DELAY=-1", null, null);
        dropR2dbcFactories(noUserEngine);
        DataSource noUser = (DataSource) noUserEngine.getDataSource("noUser").getSource();
        try (var conn = noUser.getConnection()) {
            assertTrue(conn.isValid(2));
        }
        noUserEngine.close();
    }

    @Test
    @DisplayName("URL 解析：h2 mem 转换、未知协议、空 scheme、mssql 归一化")
    void urlParsing() {
        // jdbc:h2:mem → r2dbc:h2:mem://；方言按 scheme 命中 h2
        JdbcReactorEngine h2Engine = new JdbcReactorEngine();
        h2Engine.addDataSource("h2", URL);
        assertNotNull(h2Engine.getR2dbcFactory("h2"));
        assertEquals("h2", h2Engine.getDialect("h2").protocol());
        h2Engine.close();

        // 空 scheme（jdbc://host/db）无法识别方言：建厂失败后静默降级到纯 JDBC
        JdbcReactorEngine emptyEngine = new JdbcReactorEngine();
        emptyEngine.addDataSource("empty", "jdbc://localhost:1/db", null, null);
        assertNull(emptyEngine.getR2dbcFactory("empty"));
        assertNull(emptyEngine.getDialect("empty"));
        emptyEngine.close();

        // mssql 归一化为 sqlserver 方言
        JdbcReactorEngine mssqlEngine = new JdbcReactorEngine();
        mssqlEngine.addDataSource("mssql", "jdbc:mssql://localhost:1433/db", "u", "p");
        assertNotNull(mssqlEngine.getDialect("mssql"));
        assertEquals("sqlserver", mssqlEngine.getDialect("mssql").protocol());
        mssqlEngine.close();

        // SSL 选项被复制进连接工厂配置：字符串 "false" 必须按布尔语义收敛，
        // 早期直接强转 Boolean 会让「带 ssl 参数的 URL」注册时抛 ClassCastException
        JdbcReactorEngine sslEngine = new JdbcReactorEngine();
        sslEngine.addDataSource("ssl", "r2dbc:h2:mem://ssl_probe?ssl=false");
        assertNotNull(sslEngine.getR2dbcFactory("ssl"));
        sslEngine.close();

        // 参数为 null 的私有辅助方法：契约是「URL 为空立即报参数错误」，而不是 NPE
        assertTrue(invokeConvertNullRejected());
        assertNull(invokeExtractSchemeNull());
    }

    @Test
    @DisplayName("联邦模式：多数据源经 DataSourceConversion 合并后，所有入口指向统一数据源")
    void federatedMultiDataSource() {
        registerFederation();
        engine = newEngine();
        // 第二个数据源指向同一个库：联邦结果等价于单库，断言才能给出确定值
        engine.addDataSource("h2b", URL, "sa", null);
        assertTrue(engine.isMultiDataSource());

        List<Map<String, Object>> rows = engine.query(
                "SELECT * FROM jp_user WHERE id = ?", 1).collectList().block();
        assertNotNull(rows);
        assertEquals(1, rows.size());

        List<JUser> typed = engine.query("SELECT * FROM jp_user WHERE id = ?", JUser.class, 2)
                .collectList().block();
        assertNotNull(typed);
        assertEquals("李四", typed.getFirst().getName());

        assertEquals(1, engine.execute("UPDATE jp_user SET age = ? WHERE id = ?", 41, 3).block());
        assertEquals(List.of(1, 1), engine.batch("UPDATE jp_user SET age = ? WHERE id = ?",
                List.of(new Object[]{51, 1}, new Object[]{61, 2})).collectList().block());

        assertNotNull(engine.getExecutor());
        assertNotNull(engine.getExecutor("h2"));

        // Lambda 包装器在联邦模式下必须指向统一数据源，而不是某个单库
        assertEquals(5, engine.query(JUser.class).count().block());
        assertEquals(List.of(1, 2), engine.query(JUser.class)
                .orderByAsc(JUser::getId).limit(2).list().collectList().block()
                .stream().map(JUser::getId).toList());
        assertEquals(1, engine.update(JUser.class)
                .set(JUser::getEmail, "z@z.z").eq(JUser::getId, 5).update().block());
        assertEquals("z@z.z", engine.query(JUser.class).eq(JUser::getId, 5).one().block().getEmail());
        assertEquals(1, engine.delete(JUser.class).eq(JUser::getId, 4).remove().block());
        assertEquals(4, engine.query(JUser.class).count().block());
    }

    @Test
    @DisplayName("多数据源但没有联邦实现时，给出可定位的装配错误而不是静默只查一个库")
    void multiDataSourceWithoutConversion() {
        JdbcReactorEngine e = new JdbcReactorEngine();
        e.addDataSource("h2", URL, "sa", null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> e.addDataSource("h2b", URL, "sa", null));
        assertTrue(ex.getMessage().contains("DataSourceConversion"));
        e.close();
    }

    @Test
    @DisplayName("内部适配器：不支持的 Engine 操作立即抛出，而不是静默返回空结果")
    void engineAdapters() throws Exception {
        engine = newEngine();
        Engine single = adapterOf(engine.query(JUser.class));
        assertUnsupported(single);
        assertEquals("h2", single.getDefaultDataSourceName());
        assertNotNull(single.getExecutor("h2"));
        assertNotNull(single.getDataSource("h2"));
        assertNotNull(single.getExecutor());
        assertFalse(single.supportsMeta());
        assertEquals("h2", single.getDialect("h2").protocol());
        assertEquals(single, single.setDefaultDataSourceName("h2"));
        single.close();

        engine.close();
        registerFederation();
        engine = newEngine();
        engine.addDataSource("h2b", URL, "sa", null);
        Engine unified = adapterOf(engine.query(JUser.class));
        assertUnsupported(unified);
        assertNotNull(unified.getExecutor());
        assertNotNull(unified.getExecutor("any"));
        assertNull(unified.getDataSource("any"));
        assertNull(unified.getDataSource());
        assertNull(unified.getDefaultDataSourceName());
        assertFalse(unified.supportsMeta());
        assertEquals("h2", unified.getDialect("any").protocol());
        assertEquals(unified, unified.setDefaultDataSourceName("h2b"));
        unified.close();
    }

    @Test
    @DisplayName("资源释放：AutoCloseable 连接工厂/数据源被关闭，委托引擎关闭失败只记日志")
    void closeReleasesResources() {
        AtomicBoolean factoryClosed = new AtomicBoolean();
        AtomicBoolean sourceClosed = new AtomicBoolean();

        engine = new JdbcReactorEngine(new CloseFailingDelegate()) {
        };
        engine.addDataSource("factory", new CloseableFactory(factoryClosed), Dialect.require("h2"));
        injectCloseableDataSource(engine, sourceClosed);

        // 关闭过程不能抛异常：工厂/数据源/委托引擎任一释放失败都只记日志
        engine.close();
        assertTrue(factoryClosed.get(), "AutoCloseable 连接工厂必须被关闭");
        assertTrue(sourceClosed.get(), "AutoCloseable DataSource 必须被关闭");
        engine = null;
    }

    // ==================== 测试辅助 ====================

    /**
     * 把一个实现了 {@link AutoCloseable} 的 DataSource 塞进引擎的 JDBC 数据源表。
     *
     * <p>引擎只会用 {@code createJdbcDataSource} 生成不带 {@code AutoCloseable} 的匿名实现，
     * 因此「可关闭数据源」这一释放分支只能靠注入构造。注入的是真实可连接的 H2 包装，
     * 关闭动作只置标记位，不影响库。</p>
     *
     * @param target 目标引擎
     * @param flag   关闭标记位
     */
    @SuppressWarnings("unchecked")
    private static void injectCloseableDataSource(JdbcReactorEngine target, AtomicBoolean flag) {
        try {
            Field field = JdbcReactorEngine.class.getDeclaredField("jdbcDataSources");
            field.setAccessible(true);
            ((Map<String, DataSource>) field.get(target))
                    .put("closeable", new CloseableDataSource(URL, flag));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法注入可关闭数据源", ex);
        }
    }

    /**
     * 断言适配器的写入口全部拒绝（适配器只用于读，写必须回到真实引擎）。
     *
     * @param adapter 内部适配器
     */
    private static void assertUnsupported(Engine adapter) {
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.addDataSource("x", (EngineDataSource<Object>) null));
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.store("x", List.<Object>of()));
        assertThrows(UnsupportedOperationException.class, () -> adapter.query(JUser.class));
        assertThrows(UnsupportedOperationException.class, () -> adapter.update(JUser.class));
        assertThrows(UnsupportedOperationException.class, () -> adapter.delete(JUser.class));
    }

    /**
     * 从响应式包装器里取出它持有的 Engine 适配器。
     *
     * @param wrapper 查询包装器
     * @return 内部适配器
     */
    private static Engine adapterOf(ReactorLambdaQueryWrapper<?> wrapper) {
        try {
            Field field = ReactorLambdaQueryWrapper.class.getDeclaredField("engine");
            field.setAccessible(true);
            return (Engine) field.get(wrapper);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取包装器持有的引擎", e);
        }
    }

    /**
     * 注册一个「把第一个物理数据源当作统一数据源」的联邦实现。
     *
     * <p>真实实现由 calcite / shardingv5 starter 提供，本模块的测试 classpath 上没有；
     * 这里注册的替身刻意保留真实语义（统一入口必须能执行普通 SQL），
     * 以便断言「联邦模式下所有语句都走统一数据源」而不是「联邦模式下直接报错」。</p>
     */
    private static void registerFederation() {
        ServiceProvider<DataSourceConversion> provider = ServiceProvider.of(DataSourceConversion.class);
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("default");
        definition.setType(DataSourceConversion.class);
        definition.setImplClass(FirstSourceConversion.class);
        definition.setDefault(true);
        provider.register(definition);
        assertNotNull(provider.getDefault(), "联邦替身必须注册为默认实现");
    }

    /**
     * 调用私有静态 {@code convertJdbcToR2dbc(null)}，确认其契约是抛参数异常。
     *
     * @return 是否按契约抛出 {@link IllegalArgumentException}
     */
    private static boolean invokeConvertNullRejected() {
        try {
            Method method = JdbcReactorEngine.class
                    .getDeclaredMethod("convertJdbcToR2dbc", String.class);
            method.setAccessible(true);
            method.invoke(null, (Object) null);
            return false;
        } catch (ReflectiveOperationException e) {
            return e.getCause() instanceof IllegalArgumentException;
        }
    }

    /**
     * 调用私有静态 {@code extractScheme(null, ...)}，确认其契约是返回 空。
     *
     * @return 解析结果
     */
    private static Object invokeExtractSchemeNull() {
        try {
            Method method = JdbcReactorEngine.class
                    .getDeclaredMethod("extractScheme", String.class, String.class);
            method.setAccessible(true);
            return method.invoke(null, null, "r2dbc:");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 联邦替身：直接把首个物理数据源作为统一数据源。
     */
    public static class FirstSourceConversion implements DataSourceConversion {
        @Override
        public DataSource convert(List<DataSource> dataSources, DataSourceEnvironment environment) {
            return dataSources.getFirst();
        }

        @Override
        public String type() {
            return "TEST";
        }
    }

    /**
     * 可关闭的连接工厂：仅用于验证关闭分支，语句执行一律不支持。
     */
    static class CloseableFactory implements ConnectionFactory, AutoCloseable {
        private final AtomicBoolean closed;

        CloseableFactory(AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public Publisher<? extends Connection> create() {
            return subscriber -> subscriber.onError(new UnsupportedOperationException("测试工厂不建连接"));
        }

        @Override
        public io.r2dbc.spi.ConnectionFactoryMetadata getMetadata() {
            return () -> "closeable-test";
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /**
     * 可关闭的 DataSource：关闭分支只有实现 {@link AutoCloseable} 的对象才会进入。
     */
    static class CloseableDataSource extends SimpleJdbcDataSource implements AutoCloseable {
        private final AtomicBoolean closed;

        CloseableDataSource(String url, AtomicBoolean closed) {
            super(url);
            this.closed = closed;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /**
     * 极简 DataSource，避免为测试引入连接池依赖。
     */
    static class SimpleJdbcDataSource implements DataSource {
        private final String url;

        SimpleJdbcDataSource(String url) {
            this.url = url;
        }

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("不支持 unwrap: " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    /**
     * 关闭必然失败的委托引擎，验证异常被吞掉并记日志。
     */
    static class CloseFailingDelegate extends InMemoryEngine {
        @Override
        public void close() {
            throw new IllegalStateException("模拟关闭失败");
        }
    }

    /**
     * 测试实体（表名由注解绑定）。
     */
    @TableName("jp_user")
    public static class JUser {
        private Integer id;
        private String name;
        private Integer age;
        private String email;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        /** setAge 方法 */
        public void setAge(Integer age) {
            this.age = age;
        }

        /** getEmail 方法 */
        public String getEmail() {
            return email;
        }

        /** setEmail 方法 */
        public void setEmail(String email) {
            this.email = email;
        }
    }
}
