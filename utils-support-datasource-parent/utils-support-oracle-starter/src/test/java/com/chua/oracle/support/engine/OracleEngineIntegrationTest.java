package com.chua.oracle.support.engine;

import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.page.Page;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle 外部数据库引擎集成测试（同步链路）。
 *
 * <p>基于 Testcontainers：自动拉起 {@code gvenzl/oracle-xe:21.3.0} 容器作为真实 Oracle，
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
class OracleEngineIntegrationTest {

    /** 真实 Oracle 容器（21.3.0 XE） */
    static final OracleContainer ORACLE = new OracleContainer(
            DockerImageName.parse("gvenzl/oracle-xe:21.3.0-slim-faststart"));

    /** 被测引擎 */
    private OracleEngine engine;

    /**
     * 启动容器（Oracle 冷启动较慢，wait strategy 内部处理）。
     */
    @BeforeAll
    static void startContainer() {
        ORACLE.start();
    }

    /**
     * 回收容器。
     */
    @AfterAll
    static void stopContainer() {
        ORACLE.stop();
    }

    /**
     * 初始化引擎、建表并准备测试数据。
     * <p>Oracle 无 {@code DROP TABLE IF EXISTS}，建表前尝试 DROP 并忽略失败。</p>
     */
    @BeforeEach
    void setUp() {
        engine = new OracleEngine();
        engine.addDataSource("default",
                ORACLE.getHost(), ORACLE.getMappedPort(1521),
                ORACLE.getDatabaseName(), ORACLE.getUsername(), ORACLE.getPassword());
        SqlExecutor executor = engine.getExecutor();
        try {
            executor.execute("DROP TABLE T_ORACLE_USER PURGE");
        } catch (Exception ignored) {
            // 表不存在属正常情况
        }
        executor.execute("CREATE TABLE T_ORACLE_USER (" +
                "ID NUMBER(19) PRIMARY KEY, " +
                "NAME VARCHAR2(64), " +
                "AGE NUMBER(10), " +
                "DEPT_ID NUMBER(19), " +
                "AMOUNT NUMBER(12,2))");
        executor.execute("INSERT INTO T_ORACLE_USER (ID, NAME, AGE, DEPT_ID, AMOUNT) VALUES (1, 'Alice', 18, 101, 100.5)");
        executor.execute("INSERT INTO T_ORACLE_USER (ID, NAME, AGE, DEPT_ID, AMOUNT) VALUES (2, 'Bob', 22, 101, 200.5)");
        executor.execute("INSERT INTO T_ORACLE_USER (ID, NAME, AGE, DEPT_ID, AMOUNT) VALUES (3, 'Carol', 30, 102, 300.5)");
        executor.execute("INSERT INTO T_ORACLE_USER (ID, NAME, AGE, DEPT_ID, AMOUNT) VALUES (4, 'Dave', 25, 102, 400.5)");
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
        List<OracleUser> list = engine.query(OracleUser.class).list();
        assertEquals(4, list.size());
        // 首行应完整映射驼峰字段（deptId ← DEPT_ID，Oracle 未加引号标识符统一大写）
        OracleUser first = list.get(0);
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
        List<OracleUser> byName = engine.query(OracleUser.class)
                .eq(OracleUser::getName, "Alice")
                .list();
        assertEquals(1, byName.size());
        assertEquals("Alice", byName.get(0).getName());

        // 驼峰字段条件 → 应解析为 dept_id = ?
        List<OracleUser> byDept = engine.query(OracleUser.class)
                .eq(OracleUser::getDeptId, 101L)
                .list();
        assertEquals(2, byDept.size());

        // 复合条件
        List<OracleUser> compound = engine.query(OracleUser.class)
                .eq(OracleUser::getDeptId, 102L)
                .gt(OracleUser::getAge, 26)
                .list();
        assertEquals(1, compound.size());
        assertEquals("Carol", compound.get(0).getName());
    }

    /**
     * 验证 one 查询。
     */
    @Test
    void testOne() {
        OracleUser user = engine.query(OracleUser.class)
                .eq(OracleUser::getId, 3L)
                .one();
        assertNotNull(user);
        assertEquals("Carol", user.getName());
        assertEquals(30, user.getAge());
        assertEquals(102L, user.getDeptId());

        // 无匹配返回 null
        OracleUser none = engine.query(OracleUser.class)
                .eq(OracleUser::getId, 999L)
                .one();
        assertNull(none);
    }

    /**
     * 验证分页查询（物理分页：COUNT + 分页 SQL）。
     */
    @Test
    void testPage() {
        Page<OracleUser> page = engine.query(OracleUser.class).page(1, 2);
        assertEquals(4, page.getTotal());
        assertEquals(2, page.getRecords().size());
        assertEquals(2, page.getPages());

        Page<OracleUser> page2 = engine.query(OracleUser.class).page(2, 2);
        assertEquals(2, page2.getRecords().size());

        // 条件分页
        Page<OracleUser> filtered = engine.query(OracleUser.class)
                .eq(OracleUser::getDeptId, 101L)
                .page(1, 10);
        assertEquals(2, filtered.getTotal());
        assertEquals(2, filtered.getRecords().size());
    }

    /**
     * 验证 count 统计。
     */
    @Test
    void testCount() {
        long total = engine.query(OracleUser.class).count();
        assertEquals(4, total);

        long filtered = engine.query(OracleUser.class)
                .eq(OracleUser::getDeptId, 101L)
                .count();
        assertEquals(2, filtered);
    }

    /**
     * 验证更新链路真实落库（SET 使用下划线列名）。
     */
    @Test
    void testUpdate() {
        int updated = engine.update(OracleUser.class)
                .set(OracleUser::getAge, 28)
                .set(OracleUser::getAmount, 888.8)
                .eq(OracleUser::getId, 1L)
                .update();
        assertEquals(1, updated);

        OracleUser after = engine.query(OracleUser.class)
                .eq(OracleUser::getId, 1L)
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
        int removed = engine.delete(OracleUser.class)
                .eq(OracleUser::getId, 4L)
                .remove();
        assertEquals(1, removed);

        assertEquals(3, engine.query(OracleUser.class).count());
        assertNull(engine.query(OracleUser.class).eq(OracleUser::getId, 4L).one());
    }

    /**
     * 验证执行器类型化查询（原生 SQL + 实体映射）。
     */
    @Test
    void testExecutorTypedQuery() {
        SqlExecutor executor = engine.getExecutor();
        List<OracleUser> users = executor.query(
                "SELECT * FROM T_ORACLE_USER WHERE DEPT_ID = ?",
                OracleUser.class,
                101L);
        assertEquals(2, users.size());
        OracleUser first = users.get(0);
        assertNotNull(first.getId());
        assertNotNull(first.getDeptId());
    }

    /**
     * 验证执行器 Map 查询。
     */
    @Test
    void testExecutorMapQuery() {
        SqlExecutor executor = engine.getExecutor();
        List<Map<String, Object>> rows = executor.query("SELECT * FROM T_ORACLE_USER ORDER BY ID");
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
                "SELECT * FROM T_ORACLE_USER ORDER BY ID",
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
                "INSERT INTO T_ORACLE_USER (ID, NAME, AGE, DEPT_ID, AMOUNT) VALUES (?, ?, ?, ?, ?)",
                5L, "Eve", 40, 103L, 500.5);
        assertEquals(1, inserted);
        assertEquals(5, engine.query(OracleUser.class).count());
    }

    /**
     * 验证 list 结果按条件无匹配时为空列表。
     */
    @Test
    void testListEmptyWhenNoMatch() {
        List<OracleUser> list = engine.query(OracleUser.class)
                .eq(OracleUser::getName, "NoSuchName")
                .list();
        assertTrue(list.isEmpty());
    }
}