// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.datasource.support.annotation.TableName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.PROTOCOL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcReactorEngine} 行为约定（H2 内存库，真实 R2DBC 驱动）。
 *
 * <p>重点覆盖三条彼此独立的执行路径，任何一条走错调用方都拿不到数据：</p>
 * <ol>
 *   <li><b>R2DBC 路径</b> — 只有连接工厂时走真非阻塞驱动</li>
 *   <li><b>JDBC 兜底路径</b> — 无 r2dbc 驱动（如 sqlite）时静默降级</li>
 *   <li><b>Lambda 包装器</b> — 条件 / 排序 / limit / 分页 / count 是否正确下推</li>
 * </ol>
 *
 * <p>H2 内存库必须带 {@code DB_CLOSE_DELAY=-1}：r2dbc-h2 每条语句都新建连接，
 * 默认配置下最后一个连接关闭即销毁库，第二句 SQL 就会报表不存在。</p>
 */
class JdbcReactorEngineTest {

    /**
     * 内存库 URL：DB_CLOSE_DELAY=-1 让库在所有连接关闭后仍然存活。
     */
    private static final String URL = "jdbc:h2:mem:reactor_test;DB_CLOSE_DELAY=-1";

    private JdbcReactorEngine engine;

    @BeforeEach
    void setUp() {
        engine = new JdbcReactorEngine();
        engine.addDataSource("h2", URL, "sa", null);
        engine.execute("CREATE TABLE IF NOT EXISTS reactor_user ("
                + "id INT PRIMARY KEY, name VARCHAR(64), age INT, email VARCHAR(128))").block();
        engine.execute("DELETE FROM reactor_user").block();
        engine.execute("INSERT INTO reactor_user VALUES "
                + "(1, '张三', 18, 'zhangsan@example.com'),"
                + "(2, '李四', 25, 'lisi@example.com'),"
                + "(3, '王五', NULL, 'wangwu@example.com'),"
                + "(4, '赵六', 30, 'zhaoliu@example.com'),"
                + "(5, '李七', 35, NULL)").block();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    @DisplayName("JDBC URL 自动转 R2DBC 并建立连接工厂与方言")
    void registerDataSource() {
        assertEquals("h2", engine.getDefaultDataSourceName());
        assertNotNull(engine.getR2dbcFactory("h2"));
        assertNotNull(engine.getDialect("h2"));
        assertEquals("h2", engine.getDialect("h2").protocol());
        assertFalse(engine.isMultiDataSource());
        assertNull(engine.getR2dbcFactory("missing"));
        assertNull(engine.getDialect("missing"));
        assertNull(engine.getDataSource("missing"));
    }

    @Test
    @DisplayName("Lambda 查询走 R2DBC，大写列标签正确映射到实体字段")
    void queryMapsUpperCaseColumns() {
        List<RUser> all = engine.query(RUser.class).list().collectList().block();
        assertNotNull(all);
        assertEquals(5, all.size());
        // H2 返回 ID/NAME/AGE 大写标签；映射失败会表现为字段全 null
        RUser one = engine.query(RUser.class).eq(RUser::getId, 2).one().block();
        assertNotNull(one);
        assertEquals("李四", one.getName());
        assertEquals(25, one.getAge());
        assertEquals("lisi@example.com", one.getEmail());
    }

    @Test
    @DisplayName("null 列映射为 null，且不影响同行其它字段")
    void nullColumns() {
        RUser three = engine.query(RUser.class).eq(RUser::getId, 3).one().block();
        assertNotNull(three);
        assertNull(three.getAge());
        assertEquals("王五", three.getName());

        RUser five = engine.query(RUser.class).eq(RUser::getId, 5).one().block();
        assertNotNull(five);
        assertNull(five.getEmail());
        assertEquals(35, five.getAge());
    }

    @Test
    @DisplayName("条件、排序、limit、offset 全部下推")
    void queryPushdown() {
        List<Integer> ids = engine.query(RUser.class)
                .gt(RUser::getAge, 20)
                .orderByDesc(RUser::getAge)
                .limit(2)
                .list().collectList().block()
                .stream().map(RUser::getId).toList();
        assertEquals(List.of(5, 4), ids);

        List<Integer> offsetIds = engine.query(RUser.class)
                .orderByAsc(RUser::getId)
                .limit(2).offset(1)
                .list().collectList().block()
                .stream().map(RUser::getId).toList();
        assertEquals(List.of(2, 3), offsetIds);
    }

    @Test
    @DisplayName("count 走数据库 COUNT，不拉全量")
    void count() {
        assertEquals(5, engine.query(RUser.class).count().block());
        assertEquals(3, engine.query(RUser.class).gt(RUser::getAge, 20).count().block());
        assertEquals(0, engine.query(RUser.class).eq(RUser::getId, 99).count().block());
    }

    @Test
    @DisplayName("分页：total 为命中总数，第二页有数据")
    void page() {
        Page<RUser> second = engine.query(RUser.class)
                .orderByAsc(RUser::getId).page(2, 2).block();
        assertNotNull(second);
        assertEquals(2, second.getPageNum());
        assertEquals(2, second.getPageSize());
        // 总数是全表命中数，不是当前页行数
        assertEquals(5, second.getTotal());
        assertEquals(List.of(3, 4), second.getRecords().stream().map(RUser::getId).toList());

        Page<RUser> first = engine.query(RUser.class)
                .orderByAsc(RUser::getId).page(1, 2).block();
        assertNotNull(first);
        assertEquals(List.of(1, 2), first.getRecords().stream().map(RUser::getId).toList());

        Page<RUser> beyond = engine.query(RUser.class).page(9, 2).block();
        assertNotNull(beyond);
        assertTrue(beyond.getRecords().isEmpty());
    }

    @Test
    @DisplayName("update / delete 经 lambda 包装器真实写库")
    void updateAndDelete() {
        Integer updated = engine.update(RUser.class)
                .set(RUser::getAge, 45)
                .eq(RUser::getId, 4)
                .update().block();
        assertEquals(1, updated);
        assertEquals(45, engine.query(RUser.class).eq(RUser::getId, 4).one().block().getAge());

        Integer removed = engine.delete(RUser.class).eq(RUser::getId, 1).remove().block();
        assertEquals(1, removed);
        assertEquals(4, engine.query(RUser.class).count().block());
    }

    @Test
    @DisplayName("原生 SQL：查询、类型化查询、execute、batch")
    void rawSql() {
        List<Map<String, Object>> rows = engine.query(
                "SELECT * FROM reactor_user WHERE age >= ? ORDER BY age", 25)
                .collectList().block();
        assertNotNull(rows);
        assertEquals(3, rows.size());
        assertEquals("李四", rows.getFirst().get("NAME"));

        List<RUser> typed = engine.query(
                "SELECT * FROM reactor_user WHERE name LIKE ?", RUser.class, "%王%")
                .collectList().block();
        assertNotNull(typed);
        assertEquals(1, typed.size());
        assertEquals(3, typed.getFirst().getId());

        Integer affected = engine.execute(
                "UPDATE reactor_user SET email = ? WHERE id = ?", "a@b.c", 5).block();
        assertEquals(1, affected);
        assertEquals("a@b.c", engine.query(RUser.class).eq(RUser::getId, 5).one().block().getEmail());

        List<Integer> batch = engine.batch("UPDATE reactor_user SET age = ? WHERE id = ?",
                List.of(new Object[]{50, 1}, new Object[]{60, 2}))
                .collectList().block();
        assertNotNull(batch);
        assertEquals(List.of(1, 1), batch);
        assertEquals(50, engine.query(RUser.class).eq(RUser::getId, 1).one().block().getAge());
        assertEquals(60, engine.query(RUser.class).eq(RUser::getId, 2).one().block().getAge());
    }

    @Test
    @DisplayName("空批次返回空流，不建立连接")
    void emptyBatch() {
        assertTrue(engine.batch("UPDATE reactor_user SET age = ? WHERE id = ?", List.of())
                .collectList().block().isEmpty());
        assertNotNull(engine.batch("UPDATE reactor_user SET age = ? WHERE id = ?", null)
                .collectList().block());
    }

    @Test
    @DisplayName("未配置数据源时以错误信号终止，而不是 NPE")
    void noDataSource() {
        JdbcReactorEngine empty = new JdbcReactorEngine();
        assertNull(empty.getDefaultDataSourceName());

        IllegalStateException e1 = assertThrows(IllegalStateException.class,
                () -> empty.query("SELECT 1").collectList().block());
        assertTrue(e1.getMessage().contains("未配置数据源"));

        IllegalStateException e2 = assertThrows(IllegalStateException.class,
                () -> empty.query("SELECT 1", RUser.class).collectList().block());
        assertTrue(e2.getMessage().contains("未配置数据源"));

        IllegalStateException e3 = assertThrows(IllegalStateException.class,
                () -> empty.execute("DELETE FROM x").block());
        assertTrue(e3.getMessage().contains("未配置数据源"));

        // batch 在订阅前直接抛出（返回 Flux 前完成校验）
        assertThrows(IllegalStateException.class,
                () -> empty.batch("DELETE FROM x", List.of()));
        empty.close();
    }

    @Test
    @DisplayName("JDBC URL 为空时立即报参数错误")
    void nullUrlRejected() {
        JdbcReactorEngine e = new JdbcReactorEngine();
        assertThrows(IllegalArgumentException.class, () -> e.addDataSource("x", (String) null));
        assertThrows(IllegalArgumentException.class,
                () -> e.addDataSource("x", null, "u", "p"));
        assertThrows(IllegalArgumentException.class,
                () -> e.addDataSource("x", (ConnectionFactory) null, null));
        e.close();
    }

    @Test
    @DisplayName("直接注册 R2DBC 连接工厂，走真非阻塞路径")
    void directConnectionFactory() {
        ConnectionFactory factory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(DRIVER, "h2")
                .option(PROTOCOL, "mem")
                .option(DATABASE, "reactor_factory;DB_CLOSE_DELAY=-1")
                .build());
        JdbcReactorEngine direct = new JdbcReactorEngine();
        direct.addDataSource("h2", factory, com.chua.common.support.lang.datasource.dialect.Dialect.require("h2"));

        assertEquals("h2", direct.getDefaultDataSourceName());
        assertNotNull(direct.getR2dbcFactory("h2"));
        assertEquals(0, direct.execute("CREATE TABLE IF NOT EXISTS direct_t (id INT PRIMARY KEY, v VARCHAR(8))")
                .block());
        assertEquals(1, direct.execute("INSERT INTO direct_t VALUES (1, 'x')").block());

        List<Map<String, Object>> rows = direct.query("SELECT * FROM direct_t").collectList().block();
        assertNotNull(rows);
        assertEquals(1, rows.size());

        // 清理：内存库带 DB_CLOSE_DELAY=-1，必须显式 DROP 才能不污染同名后续用例
        direct.execute("DROP TABLE direct_t").block();
        direct.close();
    }

    @Test
    @DisplayName("传入 JDBC URL 时自动转成 R2DBC URL 并注册方言")
    void jdbcUrlOnR2dbcOverload() {
        JdbcReactorEngine e = new JdbcReactorEngine();
        e.addDataSource("h2", "jdbc:h2:mem:reactor_conv;DB_CLOSE_DELAY=-1");
        assertNotNull(e.getR2dbcFactory("h2"));
        assertNotNull(e.getDialect("h2"));
        assertEquals(0, e.execute("CREATE TABLE IF NOT EXISTS conv_t (id INT)").block());
        e.execute("DROP TABLE conv_t").block();
        e.close();
    }

    @Test
    @DisplayName("getDataSource 暴露连接工厂与方言，且支持方言替换")
    void engineDataSource() {
        EngineDataSource<Object> ds = engine.getDataSource("h2");
        assertNotNull(ds);
        assertEquals("h2", ds.name());
        assertNotNull(ds.getSource());
        assertNotNull(ds.getDialect());
        assertThrows(UnsupportedOperationException.class, () -> ds.setSource(new Object()));

        // 方言替换：null 表示摘除
        assertNotNull(ds.setDialect(null));
        assertNull(engine.getDialect("h2"));
        assertNotNull(ds.setDialect(com.chua.common.support.lang.datasource.dialect.Dialect.require("h2")));
        assertNotNull(engine.getDialect("h2"));

        assertEquals(0, ds.tunnelPort());
        assertNotNull(ds.setTunnelPort(0));
        assertNotNull(engine.getDataSource());
    }

    @Test
    @DisplayName("execute 返回受影响行数；无匹配行时为 0")
    void executeAffectedRows() {
        assertEquals(0, engine.execute(
                "UPDATE reactor_user SET age = 1 WHERE id = ?", 999).block());
        assertEquals(2, engine.execute(
                "UPDATE reactor_user SET age = 99 WHERE age >= ?", 30).block());
    }

    @Test
    @DisplayName("关闭后清空全部注册信息")
    void close() {
        engine.close();
        assertNull(engine.getDefaultDataSourceName());
        assertNull(engine.getR2dbcFactory("h2"));
        assertNull(engine.getDialect("h2"));
        assertFalse(engine.isMultiDataSource());
        assertNull(engine.getDataSource());
    }

    @Test
    @DisplayName("R2dbcSqlExecutorWrapper：查询 / 类型化 / 分页 / 执行 / 批量")
    void r2dbcExecutor() {
        SqlExecutor executor = engine.getExecutor();
        assertNotNull(executor);

        List<Map<String, Object>> rows = executor.query(
                "SELECT * FROM reactor_user WHERE age >= ? ORDER BY age", 25);
        assertEquals(3, rows.size());

        List<RUser> typed = executor.query(
                "SELECT * FROM reactor_user WHERE id = ?", RUser.class, 4);
        assertEquals(1, typed.size());
        assertEquals("赵六", typed.getFirst().getName());

        assertEquals(1, executor.execute(
                "UPDATE reactor_user SET email = ? WHERE id = ?", "z@z.z", 5));
        assertEquals("z@z.z", engine.query(RUser.class).eq(RUser::getId, 5).one().block().getEmail());

        int[] batch = executor.batch("UPDATE reactor_user SET age = ? WHERE id = ?",
                List.of(new Object[]{70, 1}, new Object[]{80, 2}));
        assertEquals(2, batch.length);
        assertEquals(70, engine.query(RUser.class).eq(RUser::getId, 1).one().block().getAge());
        assertEquals(80, engine.query(RUser.class).eq(RUser::getId, 2).one().block().getAge());

        com.chua.common.support.lang.datasource.dialect.Pagination pagination =
                new com.chua.common.support.lang.datasource.dialect.Pagination().setPageNum(2).setPageSize(2);
        List<Map<String, Object>> pageRows = executor.queryPage(
                "SELECT * FROM reactor_user ORDER BY id", pagination);
        assertEquals(5, pagination.getTotal());
        assertEquals(2, pageRows.size());
        assertEquals(3, ((Number) pageRows.getFirst().get("ID")).intValue());
    }

    @Test
    @DisplayName("无对应 R2DBC 驱动时降级到 JDBC 路径，而不是抛「连接工厂未配置」")
    void jdbcFallbackPath() {
        JdbcReactorEngine e = new JdbcReactorEngine();
        // 没有任何 r2dbc 驱动认识 nosuchdb：必须静默降级到 JDBC 路径
        e.addDataSource("x", "jdbc:nosuchdb://localhost:1/test", "u", "p");
        assertNull(e.getR2dbcFactory("x"), "无驱动时不应创建出连接工厂");
        assertNotNull(e.getExecutor(), "降级后仍应提供 JDBC 执行器");

        // 没有真实服务端，连接必然失败；关键是失败来自 JDBC 执行器而非「连接工厂未配置」，
        // 且 Lambda 查询同样走这条路径而不是报错
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> e.getExecutor().query("SELECT 1"));
        assertTrue(ex.getMessage().contains("查询失败"));
        assertFalse(ex.getMessage().contains("连接工厂未配置"));

        IllegalStateException lambda = assertThrows(IllegalStateException.class,
                () -> e.query(RUser.class).list().collectList().block());
        assertFalse(lambda.getMessage().contains("连接工厂未配置"));
        e.close();
    }

    @Test
    @DisplayName("执行器在缺少方言时给出可定位的异常")
    void executorGuards() {
        ConnectionFactory factory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(DRIVER, "h2")
                .option(PROTOCOL, "mem")
                .option(DATABASE, "no_dialect;DB_CLOSE_DELAY=-1")
                .build());
        // 方言传 null：分页依赖方言，必须显式报错而不是生成错误 SQL
        JdbcReactorEngine noDialect = new JdbcReactorEngine();
        noDialect.addDataSource("h2", factory, null);
        assertNull(noDialect.getDialect("h2"));

        SqlExecutor executor = noDialect.getExecutor();
        assertNotNull(executor);
        executor.execute("CREATE TABLE IF NOT EXISTS nod_t (id INT)");

        com.chua.common.support.lang.datasource.dialect.Pagination pagination =
                new com.chua.common.support.lang.datasource.dialect.Pagination().setPageNum(1).setPageSize(1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> executor.queryPage("SELECT * FROM nod_t", pagination));
        assertTrue(e.getMessage().contains("缺少方言注册"));

        executor.execute("DROP TABLE nod_t");
        noDialect.close();
    }

    @Test
    @DisplayName("数据源不存在时执行器为 null，而不是抛异常")
    void executorForMissingSource() {
        assertNull(engine.getExecutor("missing"));
        assertNotNull(engine.getExecutor("h2"));
        assertNotNull(engine.getExecutor());

        JdbcReactorEngine empty = new JdbcReactorEngine();
        assertNull(empty.getExecutor());
        empty.close();
    }

    /**
     * 测试实体（表名由注解绑定，避免依赖驼峰转下划线的默认规则）。
     */
    @TableName("reactor_user")
    public static class RUser {
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

        public void setAge(Integer age) {
            this.age = age;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }
}