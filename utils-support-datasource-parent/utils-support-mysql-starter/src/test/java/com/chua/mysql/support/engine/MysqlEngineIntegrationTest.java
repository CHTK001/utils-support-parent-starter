package com.chua.mysql.support.engine;

import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.page.Page;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 外部数据库引擎集成测试（同步链路）。
 *
 * <p>基于 Testcontainers：自动拉起 {@code mysql:8.0} 容器作为真实 MySQL，
 * 无需外部服务与环境变量，验证以下能力：</p>
 * <ul>
 *   <li>wrapper 查询链路：list / one / page / count</li>
 *   <li>wrapper 更新链路：set + where 真实落库</li>
 *   <li>wrapper 删除链路：eq + remove 真实落库</li>
 *   <li>执行器链路：类型化查询、Map 查询、分页查询、原生 SQL 执行</li>
 * </ul>
 *
 * <p>需要本机 Docker 环境；容器随测试类启动、结束后自动回收。</p>
 *
 * @author CH
 */
class MysqlEngineIntegrationTest {

    /** 真实 MySQL 容器（8.0） */
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    /** 被测引擎 */
    private MysqlEngine engine;

    /**
     * 启动容器。
     */
    @BeforeAll
    static void startContainer() {
        MYSQL.start();
    }

    /**
     * 回收容器。
     */
    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    /**
     * 初始化引擎、建表并准备测试数据。
     */
    @BeforeEach
    void setUp() {
        engine = new MysqlEngine();
        engine.addDataSource("default",
                MYSQL.getHost(), MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT),
                MYSQL.getDatabaseName(), MYSQL.getUsername(), MYSQL.getPassword());
        SqlExecutor executor = engine.getExecutor();
        executor.execute("DROP TABLE IF EXISTS t_mysql_user");
        executor.execute("CREATE TABLE t_mysql_user (" +
                "id BIGINT PRIMARY KEY, " +
                "name VARCHAR(64), " +
                "age INT, " +
                "dept_id BIGINT, " +
                "amount DOUBLE)");
        executor.execute("INSERT INTO t_mysql_user (id, name, age, dept_id, amount) VALUES (1, 'Alice', 18, 101, 100.5)");
        executor.execute("INSERT INTO t_mysql_user (id, name, age, dept_id, amount) VALUES (2, 'Bob', 22, 101, 200.5)");
        executor.execute("INSERT INTO t_mysql_user (id, name, age, dept_id, amount) VALUES (3, 'Carol', 30, 102, 300.5)");
        executor.execute("INSERT INTO t_mysql_user (id, name, age, dept_id, amount) VALUES (4, 'Dave', 25, 102, 400.5)");
    }

    /**
     * 释放连接池。
     */
    @AfterEach
    void tearDown() {
        engine.close();
    }

    /**
     * 验证 list 全量查询。
     */
    @Test
    void testListAll() {
        List<MysqlUser> list = engine.query(MysqlUser.class).list();
        assertEquals(4, list.size());
        // 首行应完整映射驼峰字段（deptId ← dept_id）
        MysqlUser first = list.getFirst();
        assertNotNull(first.getId());
        assertNotNull(first.getName());
        assertNotNull(first.getAge());
        assertNotNull(first.getDeptId());
        assertNotNull(first.getAmount());
    }

    /**
     * 验证带条件查询（普通字段与驼峰字段）。
     */
    @Test
    void testQueryWithCondition() {
        // 普通字段条件
        List<MysqlUser> byName = engine.query(MysqlUser.class)
                .eq(MysqlUser::getName, "Alice")
                .list();
        assertEquals(1, byName.size());
        assertEquals("Alice", byName.getFirst().getName());

        // 驼峰字段条件 → 应解析为 dept_id = ?
        List<MysqlUser> byDept = engine.query(MysqlUser.class)
                .eq(MysqlUser::getDeptId, 101L)
                .list();
        assertEquals(2, byDept.size());

        // 复合条件
        List<MysqlUser> compound = engine.query(MysqlUser.class)
                .eq(MysqlUser::getDeptId, 102L)
                .gt(MysqlUser::getAge, 26)
                .list();
        assertEquals(1, compound.size());
        assertEquals("Carol", compound.getFirst().getName());
    }

    /**
     * 验证 one 查询。
     */
    @Test
    void testOne() {
        MysqlUser user = engine.query(MysqlUser.class)
                .eq(MysqlUser::getId, 3L)
                .one();
        assertNotNull(user);
        assertEquals("Carol", user.getName());
        assertEquals(30, user.getAge());
        assertEquals(102L, user.getDeptId());

        // 无匹配返回 null
        MysqlUser none = engine.query(MysqlUser.class)
                .eq(MysqlUser::getId, 999L)
                .one();
        assertNull(none);
    }

    /**
     * 验证分页查询（物理分页：COUNT + LIMIT/OFFSET）。
     */
    @Test
    void testPage() {
        Page<MysqlUser> page = engine.query(MysqlUser.class).page(1, 2);
        assertEquals(4, page.getTotal());
        assertEquals(2, page.getRecords().size());
        assertEquals(2, page.getPages());

        Page<MysqlUser> page2 = engine.query(MysqlUser.class).page(2, 2);
        assertEquals(2, page2.getRecords().size());

        // 条件分页
        Page<MysqlUser> filtered = engine.query(MysqlUser.class)
                .eq(MysqlUser::getDeptId, 101L)
                .page(1, 10);
        assertEquals(2, filtered.getTotal());
        assertEquals(2, filtered.getRecords().size());
    }

    /**
     * 验证 count 统计。
     */
    @Test
    void testCount() {
        long total = engine.query(MysqlUser.class).count();
        assertEquals(4, total);

        long filtered = engine.query(MysqlUser.class)
                .eq(MysqlUser::getDeptId, 101L)
                .count();
        assertEquals(2, filtered);
    }

    /**
     * 验证更新链路真实落库（SET 使用下划线列名）。
     */
    @Test
    void testUpdate() {
        int updated = engine.update(MysqlUser.class)
                .set(MysqlUser::getAge, 28)
                .set(MysqlUser::getAmount, 888.8)
                .eq(MysqlUser::getId, 1L)
                .update();
        assertEquals(1, updated);

        MysqlUser after = engine.query(MysqlUser.class)
                .eq(MysqlUser::getId, 1L)
                .one();
        assertNotNull(after);
        assertEquals(28, after.getAge());
        assertEquals(888.8, after.getAmount(), 0.001);
    }

    /**
     * 验证删除链路真实落库。
     */
    @Test
    void testDelete() {
        int removed = engine.delete(MysqlUser.class)
                .eq(MysqlUser::getId, 4L)
                .remove();
        assertEquals(1, removed);

        assertEquals(3, engine.query(MysqlUser.class).count());
        assertNull(engine.query(MysqlUser.class).eq(MysqlUser::getId, 4L).one());
    }

    /**
     * 验证执行器类型化查询（原生 SQL + 实体映射）。
     */
    @Test
    void testExecutorTypedQuery() {
        SqlExecutor executor = engine.getExecutor();
        List<MysqlUser> users = executor.query(
                "SELECT * FROM t_mysql_user WHERE dept_id = ?",
                MysqlUser.class,
                101L);
        assertEquals(2, users.size());
        MysqlUser first = users.getFirst();
        assertNotNull(first.getId());
        assertNotNull(first.getDeptId());
    }

    /**
     * 验证执行器 Map 查询。
     */
    @Test
    void testExecutorMapQuery() {
        SqlExecutor executor = engine.getExecutor();
        List<Map<String, Object>> rows = executor.query("SELECT * FROM t_mysql_user ORDER BY id");
        assertEquals(4, rows.size());
        assertFalse(rows.isEmpty());
    }

    /**
     * 验证执行器分页查询（queryPage 填充 total 并返回当页数据）。
     */
    @Test
    void testExecutorQueryPage() {
        SqlExecutor executor = engine.getExecutor();
        Pagination pagination = new Pagination().setPageNum(2).setPageSize(2);
        List<Map<String, Object>> rows = executor.queryPage(
                "SELECT * FROM t_mysql_user ORDER BY id",
                pagination);
        assertEquals(2, rows.size());
        assertEquals(4, pagination.getTotal());
    }

    /**
     * 验证执行器原生更新（参数化 INSERT）。
     */
    @Test
    void testExecutorExecute() {
        SqlExecutor executor = engine.getExecutor();
        int inserted = executor.execute(
                "INSERT INTO t_mysql_user (id, name, age, dept_id, amount) VALUES (?, ?, ?, ?, ?)",
                5L, "Eve", 40, 103L, 500.5);
        assertEquals(1, inserted);
        assertEquals(5, engine.query(MysqlUser.class).count());
    }

    /**
     * 验证 list 结果按条件无匹配时为空列表。
     */
    @Test
    void testListEmptyWhenNoMatch() {
        List<MysqlUser> list = engine.query(MysqlUser.class)
                .eq(MysqlUser::getName, "NoSuchName")
                .list();
        assertTrue(list.isEmpty());
    }
}
