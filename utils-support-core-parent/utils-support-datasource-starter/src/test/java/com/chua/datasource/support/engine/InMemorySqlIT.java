package com.chua.datasource.support.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存引擎原生 SQL 端到端测试：SQL 编译为 AST 后作用于 List 行引用，
 * 同步与响应式双路径均做值级断言。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InMemorySqlIT {

    static InMemoryEngine engine;
    static InMemoryReactorEngine reactor;

    /** 测试实体（bean 形态行） */
    public static class Emp {
        private int id;
        private String name;
        private int age;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    @BeforeAll
    static void setup() {
        engine = new InMemoryEngine();
        Emp e1 = new Emp(); e1.setId(1); e1.setName("Alice"); e1.setAge(20);
        Emp e2 = new Emp(); e2.setId(2); e2.setName("Bob");   e2.setAge(30);
        Emp e3 = new Emp(); e3.setId(3); e3.setName("Cathy"); e3.setAge(25);
        engine.store("emp", List.of(e1, e2, e3));

        reactor = new InMemoryReactorEngine();
        reactor.store("r_emp", List.of(
                rowOf(1, "Alice", 20), rowOf(2, "Bob", 30), rowOf(3, "Cathy", 25)));
    }

    private static Map<String, Object> rowOf(int id, String name, int age) {
        return new java.util.LinkedHashMap<>(Map.of("id", id, "name", name, "age", age));
    }

    @Test @Order(1)
    void select_all_projection_and_count() {
        assertEquals(3, engine.querySql("SELECT COUNT(*) FROM emp").get(0).get("cnt"));
        assertEquals(3, engine.querySql("SELECT * FROM emp").size());
        List<Map<String, Object>> proj = engine.querySql("SELECT name, age FROM emp");
        assertEquals(3, proj.size());
        assertFalse(proj.get(0).containsKey("id"), "投影不应包含未选列");
        assertEquals("Alice", proj.get(0).get("name"));
    }

    @Test @Order(2)
    void where_operator_matrix_value_check() {
        assertEquals("Alice", engine.querySql("SELECT name FROM emp WHERE age = 20").get(0).get("name"));
        assertEquals(2, engine.querySql("SELECT name FROM emp WHERE age > 20").size());
        assertEquals(2, engine.querySql("SELECT name FROM emp WHERE age >= 25").size());
        assertEquals(1, engine.querySql("SELECT name FROM emp WHERE age < 25").size());
        assertEquals("Cathy", engine.querySql("SELECT name FROM emp WHERE age <> 20 AND age < 30").get(0).get("name"));
        assertEquals("Alice", engine.querySql("SELECT name FROM emp WHERE name LIKE 'A%'").get(0).get("name"));
        assertEquals(2, engine.querySql("SELECT name FROM emp WHERE id IN (1, 3)").size());
        assertEquals(2, engine.querySql("SELECT name FROM emp WHERE age BETWEEN 20 AND 25").size());
        /* 参数绑定与括号优先级 */
        assertEquals("Bob", engine.querySql(
                "SELECT name FROM emp WHERE (age > ? OR age < ?) AND id = 2", 28, 21).get(0).get("name"));
        assertEquals("Alice", engine.querySql(
                "SELECT name FROM emp WHERE id = ?", 1).get(0).get("name"));
    }

    @Test @Order(3)
    void order_by_limit_offset_values() {
        assertEquals("Bob",
                engine.querySql("SELECT name FROM emp ORDER BY age DESC LIMIT 1").get(0).get("name"));
        assertEquals("Alice",
                engine.querySql("SELECT name FROM emp ORDER BY age ASC LIMIT 1").get(0).get("name"));
        assertEquals("Bob",
                engine.querySql("SELECT name FROM emp ORDER BY age ASC LIMIT 1 OFFSET 2").get(0).get("name"));
    }

    @Test @Order(4)
    void insert_update_delete_roundtrip_on_same_list() {
        /* INSERT 带列清单 + 多值，直接追加到 emp 的 list 引用 */
        int ins = engine.executeSql(
                "INSERT INTO emp (id, name, age) VALUES (?, ?, ?), (5, 'Eve', 40)", 4, "Dave", 35);
        assertEquals(2, ins);
        assertEquals(5, engine.querySql("SELECT COUNT(*) FROM emp").get(0).get("cnt"));

        /* UPDATE 命中行并读回新值 */
        int upd = engine.executeSql("UPDATE emp SET age = ? WHERE name = 'Alice'", 99);
        assertEquals(1, upd);
        assertEquals(99, engine.querySql("SELECT age FROM emp WHERE name = 'Alice'").get(0).get("age"));

        /* DELETE 移除后计数下降 */
        int del = engine.executeSql("DELETE FROM emp WHERE id IN (?, ?)", 4, 5);
        assertEquals(2, del);
        assertEquals(3, engine.querySql("SELECT COUNT(*) FROM emp").get(0).get("cnt"));
    }

    @Test @Order(5)
    void dml_visible_to_lambda_query_same_reference() {
        engine.executeSql("INSERT INTO emp (id, name, age) VALUES (77, 'Temp', 50)");
        /* Lambda 路径与 SQL 路径共享 dataStores —— 但 Lambda 读 bean，SQL 插入的是 Map，
         * 因此这里仅验证 SQL 自身可见性；跨形态一致性由 RowAccessor 兜底 */
        Map<String, Object> temp = engine.querySql("SELECT name, age FROM emp WHERE id = 77").get(0);
        assertEquals("Temp", temp.get("name"));
        engine.executeSql("DELETE FROM emp WHERE id = 77");
    }

    @Test @Order(6)
    void reactive_query_execute_batch_value_check() {
        StepVerifier.create(reactor.query("SELECT name FROM r_emp WHERE age > ? ORDER BY age DESC", 20))
                .assertNext(m -> assertEquals("Bob", m.get("name")))
                .assertNext(m -> assertEquals("Cathy", m.get("name")))
                .verifyComplete();

        StepVerifier.create(reactor.execute(
                        "INSERT INTO r_emp (id, name, age) VALUES (?, ?, ?)", 9, "Zoe", 45))
                .expectNext(1).verifyComplete();

        StepVerifier.create(reactor.batch(
                        "INSERT INTO r_emp (id, name, age) VALUES (?, ?, ?)",
                        List.of(new Object[]{10, "R1", 11}, new Object[]{11, "R2", 12})))
                .expectNext(1, 1).verifyComplete();

        StepVerifier.create(reactor.query("SELECT COUNT(*) FROM r_emp"))
                .assertNext(m -> assertEquals(6, ((Number) m.get("cnt")).intValue()))
                .verifyComplete();

        /* typed 查询：Map 行转 bean */
        StepVerifier.create(reactor.query("SELECT id, name, age FROM r_emp WHERE id = 9", Emp.class))
                .assertNext(e -> {
                    assertEquals("Zoe", e.getName());
                    assertEquals(45, e.getAge());
                })
                .verifyComplete();

        reactor.execute("DELETE FROM r_emp WHERE id IN (9, 10, 11)").block();
    }
}
