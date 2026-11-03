package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.ddl.ColumnDef;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcEngine} 行为约定（H2 内存库，真实 JDBC 链路）。
 *
 * <p>这是唯一覆盖「引擎 → DataSource → PreparedStatement → ResultSet → 实体」整条链路的测试。
 * 内存引擎测不到的缺陷都会在这里暴露：</p>
 * <ul>
 *   <li>结果集列标签大写（H2 返回 {@code NAME}）必须能映射到字段 {@code name}</li>
 *   <li>物理分页要真的把 LIMIT/OFFSET 下推，第二页不能为空</li>
 *   <li>{@code store()} 过的实体会回退内存链路，不得混读数据库</li>
 *   <li>无数据源时返回空列表/空结果，而不是抛 NPE</li>
 * </ul>
 */
class JdbcEngineTest {

    private static final String URL = "jdbc:h2:mem:enginetest;DB_CLOSE_DELAY=-1";

    private H2TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        // 建表并写入 5 行；H2 的列标签默认大写，正好验证大小写不敏感的映射
        try (Connection conn = DriverManager.getConnection(URL);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS user_test");
            st.execute("CREATE TABLE user_test ("
                    + "id INT PRIMARY KEY, name VARCHAR(64), age INT, email VARCHAR(128))");
            st.execute("INSERT INTO user_test VALUES "
                    + "(1, '张三', 18, 'zhangsan@example.com'),"
                    + "(2, '李四', 25, 'lisi@example.com'),"
                    + "(3, '王五', NULL, 'wangwu@example.com'),"
                    + "(4, '赵六', 30, 'zhaoliu@example.com'),"
                    + "(5, '李七', 35, NULL)");
        }
        engine = new H2TestEngine();
        engine.addDataSource("h2", URL);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    /**
     * 在测试库上顺序执行若干原生 SQL。
     *
     * <p>集中封装 try-with-resources，避免每个测试重复编写资源关闭样板，
     * 也让测试方法签名保持无受检异常。</p>
     *
     * @param sqls 待执行的 SQL
     */
    private static void exec(String... sqls) {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement st = conn.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("测试库执行 SQL 失败", e);
        }
    }

    @Test
    @DisplayName("Lambda 查询走 JDBC，结果集大写列标签正确映射到实体字段")
    void queryMapsUpperCaseColumns() {
        List<Row> all = engine.query(Row.class).list();
        assertEquals(5, all.size());
        // H2 返回 NAME / EMAIL 这类大写标签，映射失败会表现为字段全 null
        Row one = engine.query(Row.class).eq(Row::getId, 2).one();
        assertNotNull(one);
        assertEquals("李四", one.getName());
        assertEquals(25, one.getAge());
        assertEquals("lisi@example.com", one.getEmail());
    }

    @Test
    @DisplayName("条件、排序、limit 全部下推到数据库")
    void queryPushdown() {
        List<Integer> ids = engine.query(Row.class)
                .gt(Row::getAge, 20)
                .orderByDesc(Row::getAge)
                .limit(2)
                .list()
                .stream().map(Row::getId).toList();
        assertEquals(List.of(5, 4), ids);
    }

    @Test
    @DisplayName("物理分页：COUNT 取总数，LIMIT/OFFSET 取当前页")
    void physicalPaging() {
        assertTrue(engine.supportsNativePagingPublic(Row.class));

        Page<Row> second = engine.query(Row.class).orderByAsc(Row::getId).page(2, 2);
        assertEquals(2, second.getPageNum());
        assertEquals(2, second.getPageSize());
        assertEquals(5, second.getTotal());
        assertEquals(List.of(3, 4), second.getRecords().stream().map(Row::getId).toList());

        // 第二页必须真的有数据：分页参数只在单次查询内注入并随后还原
        Page<Row> first = engine.query(Row.class).orderByAsc(Row::getId).page(1, 2);
        assertEquals(List.of(1, 2), first.getRecords().stream().map(Row::getId).toList());

        Page<Row> beyond = engine.query(Row.class).page(9, 2);
        assertEquals(5, beyond.getTotal());
        assertTrue(beyond.getRecords().isEmpty());
    }

    @Test
    @DisplayName("count() 走物理 COUNT 而不是拉全量")
    void count() {
        assertEquals(5, engine.query(Row.class).count());
        assertEquals(3, engine.query(Row.class).gt(Row::getAge, 20).count());
        assertEquals(0, engine.query(Row.class).eq(Row::getId, 99).count());
    }

    @Test
    @DisplayName("null 字段正常映射为 null，不污染基本类型")
    void nullColumns() {
        Row three = engine.query(Row.class).eq(Row::getId, 3).one();
        assertNotNull(three);
        assertNull(three.getAge());
        assertEquals("王五", three.getName());

        Row five = engine.query(Row.class).eq(Row::getId, 5).one();
        assertNotNull(five);
        assertNull(five.getEmail());
    }

    @Test
    @DisplayName("executeUpdate / executeDelete 真实写库")
    void updateAndDelete() {
        int updated = engine.executeUpdate(new UpdateSql<>(Row.class,
                "name = ?, age = ?", "id = ?", List.of("刘八", 40, 1)));
        assertEquals(1, updated);
        assertEquals("刘八", engine.query(Row.class).eq(Row::getId, 1).one().getName());

        int removed = engine.executeDelete(
                new com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql<>(
                        Row.class, "id = ?", List.of(2)));
        assertEquals(1, removed);
        assertEquals(4, engine.query(Row.class).count());
    }

    @Test
    @DisplayName("原生 SQL 执行器：查询、类型化查询、更新、批量")
    void sqlExecutor() {
        SqlExecutor executor = engine.getExecutor();
        assertNotNull(executor);

        List<Map<String, Object>> rows = executor.query(
                "SELECT * FROM user_test WHERE age >= ? ORDER BY age", 25);
        assertEquals(3, rows.size());
        assertEquals("李四", rows.getFirst().get("NAME"));

        List<Row> typed = executor.query(
                "SELECT * FROM user_test WHERE id = ?", Row.class, 4);
        assertEquals(1, typed.size());
        assertEquals("赵六", typed.getFirst().getName());

        assertEquals(1, executor.execute(
                "UPDATE user_test SET email = ? WHERE id = ?", "new@example.com", 5));
        assertEquals("new@example.com",
                engine.query(Row.class).eq(Row::getId, 5).one().getEmail());

        int[] batch = executor.batch("UPDATE user_test SET age = ? WHERE id = ?",
                List.of(new Object[]{50, 1}, new Object[]{60, 2}));
        assertEquals(2, batch.length);
        assertEquals(50, engine.query(Row.class).eq(Row::getId, 1).one().getAge());
        assertEquals(60, engine.query(Row.class).eq(Row::getId, 2).one().getAge());
    }

    @Test
    @DisplayName("分页查询按方言生成 SQL 并回填 total")
    void executorQueryPage() {
        Pagination pagination = new Pagination().setPageNum(2).setPageSize(2);
        List<Map<String, Object>> rows = engine.getExecutor().queryPage(
                "SELECT * FROM user_test ORDER BY id", pagination);
        assertEquals(5, pagination.getTotal());
        assertEquals(2, rows.size());
        assertEquals(3, ((Number) rows.getFirst().get("ID")).intValue());
    }

    @Test
    @DisplayName("SQL 出错时抛出带根因信息的异常")
    void sqlError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> engine.getExecutor().query("SELECT * FROM not_exists_table"));
        assertTrue(e.getMessage().contains("执行查询失败"));
        // 根因（表不存在）必须出现在消息里，便于直接定位
        assertNotNull(e.getCause());
    }

    @Test
    @DisplayName("store 后查询回退内存链路，且不影响数据库数据")
    void memoryStoreTakesPrecedence() {
        // 内存表键必须是实体解析出的表名（user_test），否则 getData 找不到而回退 JDBC
        engine.store(AbstractEngine.resolveTableName(Row.class),
                List.of(new Row(100, "内存行", 66, "mem@example.com")));

        List<Row> list = engine.query(Row.class).list();
        assertEquals(1, list.size());
        assertEquals("内存行", list.getFirst().getName());
        // 内存数据存在时不应走物理分页
        assertFalse(engine.supportsNativePagingPublic(Row.class));

        // 数据库中的数据保持原样
        assertEquals(5, engine.getExecutor().query("SELECT * FROM user_test").size());
    }

    @Test
    @DisplayName("无数据源时查询返回空列表而不是抛异常")
    void noDataSource() {
        H2TestEngine empty = new H2TestEngine();
        assertTrue(empty.query(Row.class).list().isEmpty());
        assertEquals(0, empty.query(Row.class).count());
        assertNull(empty.getExecutor());
        assertNull(empty.getJdbcDataSourcePublic());
        assertThrows(IllegalStateException.class, empty::getJdbcConnectionPublic);
        empty.close();
    }

    @Test
    @DisplayName("方言随数据源注册，可正常读取")
    void dialect() {
        Dialect dialect = engine.getDialect("h2");
        assertNotNull(dialect);
        assertEquals("h2", dialect.protocol());
        assertNull(engine.getDialect("missing"));
    }

    @Test
    @DisplayName("JOIN 查询强制走 JDBC，内存无法完成跨表关联")
    void joinQuery() {
        exec("DROP TABLE IF EXISTS join_dept",
                "CREATE TABLE join_dept (dept_id INT PRIMARY KEY, dept_name VARCHAR(32))",
                "INSERT INTO join_dept VALUES (1, '研发'), (2, '销售')",
                "ALTER TABLE user_test ADD COLUMN IF NOT EXISTS dept_id INT",
                "UPDATE user_test SET dept_id = 1 WHERE id <= 3",
                "UPDATE user_test SET dept_id = 2 WHERE id > 3");

        // JOIN 路径 buildSelectSql × executeNativeQuery；内存链路无法表达跨表关联
        List<Map<String, Object>> rows = engine.query(Row.class)
                .innerJoin("join_dept", "user_test.dept_id = join_dept.dept_id")
                .eq(Row::getAge, 25)
                .list()
                .stream()
                .map(r -> Map.<String, Object>of("id", r.getId(), "name", r.getName()))
                .toList();
        assertEquals(1, rows.size());
        assertEquals(2, rows.getFirst().get("id"));

        exec("ALTER TABLE user_test DROP COLUMN dept_id", "DROP TABLE join_dept");
    }

    @Test
    @DisplayName("JOIN + count 直连 COUNT(*) 而不是包子查询")
    void joinCount() {
        exec("DROP TABLE IF EXISTS count_dept",
                "CREATE TABLE count_dept (dept_id INT PRIMARY KEY, dept_name VARCHAR(32))",
                "INSERT INTO count_dept VALUES (1, '研发')",
                "ALTER TABLE user_test ADD COLUMN IF NOT EXISTS dept_id INT",
                "UPDATE user_test SET dept_id = CASE WHEN id <= 3 THEN 1 ELSE NULL END");

        assertEquals(3, engine.query(Row.class)
                .innerJoin("count_dept", "user_test.dept_id = count_dept.dept_id")
                .count());

        exec("ALTER TABLE user_test DROP COLUMN dept_id", "DROP TABLE count_dept");
    }

    @Test
    @DisplayName("GROUP BY 无显式投影时只选分组列；HAVING 参数追加在 WHERE 之后")
    void groupByAndHaving() {
        // age 共 5 个不同值（18 / 25 / NULL / 30 / 35），因此应得到 5 个分组
        List<Row> grouped = engine.query(Row.class)
                .select(Row::getAge)
                .groupBy(Row::getAge)
                .orderByAsc(Row::getAge)
                .list();
        assertEquals(5, grouped.size());

        // HAVING 参数必须排在 WHERE 参数之后，否则占位符错位
        List<Row> having = engine.query(Row.class)
                .select(Row::getAge)
                .groupBy(Row::getAge)
                .having("COUNT(*) >= ?", 2)
                .list();
        assertTrue(having.isEmpty(), "每个 age 最多 1 行，HAVING >= 2 应无命中");

        assertEquals(0, engine.query(Row.class)
                .select(Row::getAge)
                .groupBy(Row::getAge)
                .having("COUNT(*) >= ?", 2)
                .count());
    }

    @Test
    @DisplayName("方言不支持 LIMIT 时按页对齐走方言分页，非页对齐报错")
    void dialectPagination() {
        // oracle 方言 supports-limit=false，走 processSql 分页模型
        Dialect oracle = Dialect.require("oracle");
        assertFalse(oracle.supportsLimit());

        String pageSql = oracle.processSql("SELECT * FROM t", new Pagination().setPageNum(1).setPageSize(2));
        assertNotNull(pageSql);

        // 页对齐（offset 是 limit 的整数倍）可转换
        H2TestEngine paged = new H2TestEngine();
        paged.addDataSource("oa", URL, oracle);
        List<Row> aligned = paged.query(Row.class).orderByAsc(Row::getId).limit(2).offset(2).list();
        assertEquals(List.of(3, 4), aligned.stream().map(Row::getId).toList());

        // 非页对齐无法用页码/页大小表达，必须显式拒绝而不是静默返回错误页码
        assertThrows(UnsupportedOperationException.class,
                () -> paged.query(Row.class).limit(2).offset(1).list());
        paged.close();
    }

    @Test
    @DisplayName("建表/建库/删库按方言与 SPI 兜底生成 DDL，并校验标识符")
    void ddl() {
        // 建表：h2 无 DdlProvider 扩展，走内置标准 SQL 兜底
        assertTrue(engine.createTable("ddl_probe", List.of(
                new ColumnDef().setName("id").setType("INT").setPrimaryKey(true),
                new ColumnDef().setName("name").setType("VARCHAR(32)").setNullable(false))));
        assertEquals(0, engine.getExecutor().query("SELECT * FROM ddl_probe").size());
        engine.getExecutor().execute("DROP TABLE ddl_probe");

        // 建表校验：表名/列定义缺失时立即报参数错误
        assertThrows(IllegalArgumentException.class, () -> engine.createTable(" ", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> engine.createTable("t_bad", List.of(new ColumnDef().setName("c"))));

        // 非法标识符必须拒绝，避免 DDL 拼接注入
        assertThrows(IllegalArgumentException.class,
                () -> engine.createTable("t; DROP TABLE user_test", List.of(
                        new ColumnDef().setName("id").setType("INT"))));
        assertThrows(IllegalArgumentException.class, () -> engine.createDatabase("db; drop table x"));

        // H2 下建库/删库走内置兜底语法
        assertThrows(RuntimeException.class, () -> engine.createDatabase("probe_db"));
        assertThrows(RuntimeException.class, () -> engine.dropDatabase("probe_db"));
        assertThrows(IllegalArgumentException.class, () -> engine.createDatabase(" "));
        assertThrows(IllegalArgumentException.class, () -> engine.dropDatabase(null));
    }

    @Test
    @DisplayName("默认值白名单：数字/关键字/配平字符串放行，其余拒绝")
    void defaultValues() {
        assertTrue(engine.createTable("dv_ok", List.of(
                new ColumnDef().setName("id").setType("INT").setPrimaryKey(true),
                new ColumnDef().setName("n").setType("INT").setDefaultValue("-12"),
                new ColumnDef().setName("flag").setType("BOOLEAN").setDefaultValue("FALSE"),
                new ColumnDef().setName("s").setType("VARCHAR(16)").setDefaultValue("'a''b'"))));
        engine.getExecutor().execute("DROP TABLE dv_ok");

        // 引号不配平意味着内容逃出字符串字面量，必须拒绝
        assertThrows(IllegalArgumentException.class, () -> engine.createTable("dv_bad", List.of(
                new ColumnDef().setName("s").setType("VARCHAR(16)")
                        .setDefaultValue("'a'); DROP TABLE user_test; --"))));
        assertThrows(IllegalArgumentException.class, () -> engine.createTable("dv_bad2", List.of(
                new ColumnDef().setName("s").setType("VARCHAR(8)")
                        .setDefaultValue("CURRENT_USER"))));
    }

    @Test
    @DisplayName("触发器 / 存储过程查询：H2 无存储过程模板时明确报不支持")
    void triggerAndProcedure() {
        // H2 的 trigger-list-sql 存在，但没有触发器时返回空列表（不报错）
        assertNotNull(engine.getTriggers(null));
        assertTrue(engine.getTriggers(null).isEmpty());

        // 单个触发器按名字过滤，未命中返回 null
        assertNull(engine.getTrigger("no_such_trigger", null));

        // h2.env 未配置 procedure 模板 → 必须抛出可定位的不支持异常
        assertThrows(UnsupportedOperationException.class, () -> engine.getProcedures(null));
        assertThrows(UnsupportedOperationException.class, () -> engine.getProcedure("p", null));
    }

    @Test
    @DisplayName("数据库存在性检查与库列表走元数据，不会抛 NPE")
    void databaseIntrospection() {
        assertFalse(engine.databaseExists(" "));
        assertFalse(engine.databaseExists(null));
        // H2 的 catalog 名是库名本身，列出后必须包含它
        assertNotNull(engine.listDatabases());
        assertTrue(engine.databaseExists("ENGINETEST")
                || engine.listDatabases().stream().anyMatch(s -> s.equalsIgnoreCase("ENGINETEST")));
    }

    @Test
    @DisplayName("SPI 管理器入口：无扩展时返回 null 而不是抛异常")
    void managers() {
        assertNull(engine.user());
        assertNull(engine.index());
        assertNull(engine.permission());
    }

    @Test
    @DisplayName("执行器按数据源查找：不存在或非 JDBC 数据源返回 null")
    void executorLookup() {
        assertNotNull(engine.getExecutor("h2"));
        assertNull(engine.getExecutor("missing"));
        assertNotNull(engine.getExecutor());
        assertNull(engine.getDialect(null));
        assertTrue(engine.supportsSql());
        assertFalse(engine.supportsMeta() && engine.meta() == null);
        assertNotNull(engine.meta());
    }

    @Test
    @DisplayName("更新/删除无 WHERE 时禁止全表操作")
    void updateDeleteGuards() {
        assertThrows(IllegalStateException.class,
                () -> engine.update(Row.class).set(Row::getName, "x").update());
        assertThrows(IllegalStateException.class,
                () -> engine.delete(Row.class).remove());
        // 显式放行后才允许全表操作
        assertEquals(5, engine.update(Row.class).set(Row::getAge, 1).allowFullTable().update());
    }

    /**
     * 可实例化的 JDBC 引擎：把受保护方法暴露给测试，并提供一个 H2 数据源注册入口。
     */
    static class H2TestEngine extends JdbcEngine {

        void addDataSource(String name, String url) {
            addDataSource(name, url, Dialect.require("h2"));
        }

        void addDataSource(String name, String url, Dialect dialect) {
            EngineDataSource<Object> ds = new EngineDataSource<Object>() {
                private Dialect current = dialect;

                @Override
                public String name() {
                    return name;
                }

                @Override
                public Object getSource() {
                    return new SimpleDataSource(url);
                }

                @Override
                public EngineDataSource<Object> setSource(Object source) {
                    return this;
                }

                @Override
                public Dialect getDialect() {
                    return current;
                }

                @Override
                public EngineDataSource<Object> setDialect(Dialect d) {
                    if (d != null) {
                        this.current = d;
                    }
                    return this;
                }

                @Override
                public String url() {
                    return url;
                }

                @Override
                public String username() {
                    return null;
                }

                @Override
                public String password() {
                    return null;
                }
            };
            super.addDataSource(name, ds);
        }

        boolean supportsNativePagingPublic(Class<?> entityClass) {
            return supportsNativePaging(entityClass);
        }

        javax.sql.DataSource getJdbcDataSourcePublic() {
            return getJdbcDataSource();
        }

        Connection getJdbcConnectionPublic() throws Exception {
            return getJdbcConnection();
        }
    }

    /**
     * 极简 DataSource 实现，避免为测试引入连接池依赖。
     */
    static class SimpleDataSource implements javax.sql.DataSource {
        private final String url;

        SimpleDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password)
                throws java.sql.SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
            throw new java.sql.SQLException("不支持 unwrap: " + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    /**
     * 测试实体（表名由注解绑定到 user_test，避免与 SQL 关键字 ROW 冲突）。
     */
    @com.chua.datasource.support.annotation.TableName("user_test")
    public static class Row {
        private Integer id;
        private String name;
        private Integer age;
        private String email;

        public Row() {
        }

        Row(Integer id, String name, Integer age, String email) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.email = email;
        }

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
