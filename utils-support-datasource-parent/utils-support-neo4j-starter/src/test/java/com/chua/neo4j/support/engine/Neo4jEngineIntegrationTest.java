package com.chua.neo4j.support.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Neo4j 引擎集成测试。
 *
 * <p>基于 Testcontainers：自动拉起 {@code neo4j:5-community} 容器，
 * 无需外部服务与环境变量。</p>
 *
 * <p>测试覆盖：原生 Cypher 执行、Lambda 链式查询（eq/gt/like/in），
 * 以及分页、更新、删除操作。{@code getExecutor()} 返回 {@code null}，
 * 故不测试 SqlExecutor 路径。</p>
 *
 * <p>需要本机 Docker 环境；容器随测试类启动、结束后自动回收。</p>
 *
 * @author CH
 */
public class Neo4jEngineIntegrationTest {

    /** 真实 Neo4j 5 Community 容器（固定密码，限制 JVM 内存避免 CI 上 OOM 导致 exit 70） */
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>(DockerImageName.parse("neo4j:5-community"))
            .withAdminPassword("test")
            .withEnv("NEO4J_server_memory_heap_initial__size", "256m")
            .withEnv("NEO4J_server_memory_heap_max__size", "512m")
            .withEnv("NEO4J_server_memory_pagecache_size", "256m")
            .withLogConsumer(outputFrame -> System.out.print("[NEO4J] " + outputFrame.getUtf8String()));

    private Neo4jEngine engine;

    @BeforeAll
    static void startContainer() {
        NEO4J.start();
    }

    @AfterAll
    static void stopContainer() {
        NEO4J.stop();
    }

    @BeforeEach
    void setUp() {
        engine = new Neo4jEngine();
        engine.connect(NEO4J.getBoltUrl(), "neo4j", "test");

        // 清理旧数据
        engine.execute("MATCH (n:Person) DETACH DELETE n");

        // 插入 4 条测试数据
        List<Person> persons = List.of(
                new Person(1L, "Alice", 25),
                new Person(2L, "Bob", 30),
                new Person(3L, "Carol", 35),
                new Person(4L, "Dave", 28)
        );
        engine.store("default", persons);
    }

    // ==================== getExecutor 返回 null ====================

    @Test
    void testGetExecutorReturnsNull() {
        assertNull(engine.getExecutor(), "Neo4jEngine.getExecutor() 应返回 null");
    }

    // ==================== list / query ====================

    @Test
    void testListAll() {
        List<Person> all = engine.query(Person.class).list();
        assertEquals(4, all.size(), "应有 4 条 Person");
        assertTrue(all.stream().allMatch(p -> p.getId() != null), "id 非空");
        assertTrue(all.stream().allMatch(p -> p.getName() != null), "name 非空");
        assertTrue(all.stream().allMatch(p -> p.getAge() != null), "age 非空");
    }

    @Test
    void testQueryWithEqCondition() {
        List<Person> list = engine.query(Person.class)
                .eq(Person::getName, "Bob")
                .list();
        assertEquals(1, list.size());
        assertEquals("Bob", list.get(0).getName());
        assertEquals(2L, list.get(0).getId());
    }

    @Test
    void testQueryWithGtCondition() {
        List<Person> list = engine.query(Person.class)
                .gt(Person::getAge, 28)
                .list();
        assertEquals(2, list.size(), "age > 28 应有 Bob(30) 和 Carol(35)");
    }

    @Test
    void testQueryWithLikeCondition() {
        // Neo4jEngine LIKE → CONTAINS 语义，去掉 %
        List<Person> list = engine.query(Person.class)
                .like(Person::getName, "%li%")
                .list();
        assertEquals(1, list.size());
        assertEquals("Alice", list.get(0).getName());
    }

    @Test
    void testQueryWithInCondition() {
        List<Person> list = engine.query(Person.class)
                .in(Person::getName, List.of("Alice", "Carol"))
                .list();
        assertEquals(2, list.size());
        List<String> names = list.stream().map(Person::getName).toList();
        assertTrue(names.contains("Alice"));
        assertTrue(names.contains("Carol"));
    }

    // ==================== one ====================

    @Test
    void testOne() {
        Person p = engine.query(Person.class)
                .eq(Person::getId, 3L)
                .one();
        assertNotNull(p);
        assertEquals("Carol", p.getName());
        assertEquals(35, p.getAge());
    }

    @Test
    void testOneReturnsNullWhenNoMatch() {
        Person p = engine.query(Person.class)
                .eq(Person::getId, 999L)
                .one();
        assertNull(p);
    }

    // ==================== page ====================

    @Test
    void testPage() {
        var page = engine.query(Person.class)
                .eq(Person::getAge, 30)
                .page(1, 10);
        // Native paging: total = all.size() for the result set
        assertNotNull(page);
        assertEquals(1, page.getRecords().size(), "分页应返回 1 条 Bob");
        assertEquals("Bob", page.getRecords().get(0).getName());
    }

    // ==================== count（通过 list().size() 验证）====================

    @Test
    void testCountViaListSize() {
        // Neo4jEngine 未覆写 count()，用 list().size() 间接验证
        long count = engine.query(Person.class).list().size();
        assertEquals(4L, count, "总记录数应为 4");
    }

    // ==================== update ====================

    @Test
    void testUpdate() {
        int affected = engine.update(Person.class)
                .set(Person::getAge, 50)
                .eq(Person::getName, "Alice")
                .update();
        assertEquals(1, affected, "更新 1 条记录");

        Person p = engine.query(Person.class)
                .eq(Person::getName, "Alice")
                .one();
        assertEquals(50, p.getAge(), "Alice 年龄应更新为 50");
    }

    // ==================== delete ====================

    @Test
    void testDelete() {
        int affected = engine.delete(Person.class)
                .eq(Person::getName, "Dave")
                .remove();
        assertEquals(1, affected, "删除 1 条记录");

        List<Person> remaining = engine.query(Person.class).list();
        assertEquals(3, remaining.size());
        assertTrue(remaining.stream().noneMatch(p -> "Dave".equals(p.getName())));
    }

    // ==================== 原生 Cypher 参数化 ====================

    @Test
    void testNativeCypherWithParams() {
        // 使用 $p0 占位符
        List<Person> list = engine.query(Person.class)
                .eq(Person::getName, "Carol")
                .list();
        assertEquals(1, list.size());
        assertEquals("Carol", list.get(0).getName());

        // execute 返回 counters 总和，对于查询不产生写操作
        int result = engine.execute("MATCH (n:Person {name: $p0}) RETURN n", "Carol");
        assertTrue(result >= 0, "查询操作返回 counters 总和应 >= 0");
    }

    // ==================== 空结果 ====================

    @Test
    void testEmptyResult() {
        List<Person> list = engine.query(Person.class)
                .eq(Person::getName, "不存在的人")
                .list();
        assertTrue(list.isEmpty(), "无匹配时应返回空列表");
    }

    // ==================== close ====================

    @Test
    void testClose() {
        Neo4jEngine temp = new Neo4jEngine();
        temp.connect(NEO4J.getBoltUrl(), "neo4j", "test");
        assertDoesNotThrow(temp::close);
    }
}
