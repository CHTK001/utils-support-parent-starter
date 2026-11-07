// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.page.Page;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryReactorEngine} 行为约定。
 *
 * <p>响应式门面只是同步内存引擎的一层包装，因此断言集中在两件容易出错的事上：</p>
 * <ul>
 *   <li>包装器上的条件、排序、{@code limit}/{@code offset} 必须全部平移到委托引擎，
 *       漏掉任何一项都会让调用方静默拿到错误结果集</li>
 *   <li>{@link reactor.core.publisher.Mono} 的「无结果」必须是真的空信号，
 *       而不是一个 null 元素</li>
 * </ul>
 *
 * <p>终端方法统一用 {@code block()} 断言，不引入 {@code reactor-test} 依赖。</p>
 * @author CH
 * @since 4.0.0.50
 */
class InMemoryReactorEngineTest {

    private InMemoryReactorEngine engine;

    @BeforeEach
    void setUp() {
        engine = new InMemoryReactorEngine();
        engine.store(AbstractEngine.resolveTableName(User.class), List.of(
                new User(1, "张三", 18, "zhangsan@example.com"),
                new User(2, "李四", 25, "lisi@example.com"),
                new User(3, "王五", null, "wangwu@example.com"),
                new User(4, "赵六", 30, "zhaoliu@example.com"),
                new User(5, "李七", 35, null)));
    }

    @Test
    @DisplayName("list 返回全部行，one 返回首条")
    void listAndOne() {
        assertEquals(List.of(1, 2, 3, 4, 5), ids(engine.query(User.class).list().collectList().block()));

        User one = engine.query(User.class).eq(User::getName, "李四").one().block();
        assertNotNull(one);
        assertEquals(2, one.getId());
    }

    @Test
    @DisplayName("one 无命中时发出空信号而不是 null 元素")
    void oneMissing() {
        assertNull(engine.query(User.class).eq(User::getId, 99).one().block());
    }

    @Test
    @DisplayName("条件与排序都平移到委托引擎")
    void conditionsAndOrder() {
        List<Integer> ids = ids(engine.query(User.class)
                .gt(User::getAge, 20)
                .orderByDesc(User::getAge)
                .list().collectList().block());
        assertEquals(List.of(5, 4, 2), ids);
    }

    @Test
    @DisplayName("limit / offset 必须生效（曾整体丢失）")
    void limitAndOffset() {
        assertEquals(List.of(1, 2), ids(engine.query(User.class)
                .orderByAsc(User::getId).limit(2).list().collectList().block()));

        assertEquals(List.of(2, 3), ids(engine.query(User.class)
                .orderByAsc(User::getId).limit(2).offset(1).list().collectList().block()));

        assertTrue(engine.query(User.class).orderByAsc(User::getId)
                .limit(2).offset(99).list().collectList().block().isEmpty());
    }

    @Test
    @DisplayName("分页第二页不受 wrapper limit 影响，total 为命中总数")
    void page() {
        Page<User> second = engine.query(User.class).orderByAsc(User::getId).page(2, 2).block();
        assertNotNull(second);
        assertEquals(2, second.getPageNum());
        assertEquals(2, second.getPageSize());
        assertEquals(5, second.getTotal());
        assertEquals(List.of(3, 4), ids(second.getRecords()));

        Page<User> beyond = engine.query(User.class).page(9, 2).block();
        assertNotNull(beyond);
        assertEquals(5, beyond.getTotal());
        assertTrue(beyond.getRecords().isEmpty());

        // 页码小于 1 归一为第一页；每页条数非法时以错误信号终止
        Page<User> first = engine.query(User.class).orderByAsc(User::getId).page(0, 2).block();
        assertNotNull(first);
        assertEquals(1, first.getPageNum());
        assertEquals(List.of(1, 2), ids(first.getRecords()));

        assertThrows(IllegalArgumentException.class,
                () -> engine.query(User.class).page(1, 0).block());
    }

    @Test
    @DisplayName("update 返回受影响行数并真正写入")
    void update() {
        Integer affected = engine.update(User.class)
                .set(User::getAge, 45)
                .eq(User::getId, 4)
                .update().block();
        assertEquals(1, affected);
        assertEquals(45, engine.query(User.class).eq(User::getId, 4).one().block().getAge());
    }

    @Test
    @DisplayName("delete 返回受影响行数并移除数据")
    void delete() {
        Integer removed = engine.delete(User.class).eq(User::getId, 2).remove().block();
        assertEquals(1, removed);
        assertEquals(4, engine.query(User.class).list().collectList().block().size());
        assertNull(engine.query(User.class).eq(User::getId, 2).one().block());
    }

    @Test
    @DisplayName("原生 SQL 查询返回映射行")
    void querySqlRows() {
        List<Map<String, Object>> rows = engine.query(
                "SELECT * FROM user WHERE age >= ? ORDER BY age", 25)
                .collectList().block();
        assertNotNull(rows);
        assertEquals(3, rows.size());
        assertEquals("李四", rows.getFirst().get("name"));
    }

    @Test
    @DisplayName("原生 SQL 查询按行类型映射")
    void querySqlTyped() {
        List<User> users = engine.query(
                "SELECT * FROM user WHERE name LIKE ?", User.class, "%王%")
                .collectList().block();
        assertNotNull(users);
        assertEquals(1, users.size());
        assertEquals(3, users.getFirst().getId());
        assertEquals("wangwu@example.com", users.getFirst().getEmail());
    }

    @Test
    @DisplayName("execute 执行 DML 并返回影响行数")
    void execute() {
        Integer updated = engine.execute("UPDATE user SET age = 99 WHERE id = ?", 1).block();
        assertEquals(1, updated);
        assertEquals(99, engine.query(User.class).eq(User::getId, 1).one().block().getAge());
    }

    @Test
    @DisplayName("batch 按批次执行并逐批发出影响行数")
    void batch() {
        List<Integer> affected = engine.batch("UPDATE user SET email = ? WHERE id = ?",
                List.of(new Object[]{"a@b.c", 1}, new Object[]{"d@e.f", 2}))
                .collectList().block();
        assertNotNull(affected);
        assertEquals(List.of(1, 1), affected);
        assertEquals("a@b.c", engine.query(User.class).eq(User::getId, 1).one().block().getEmail());
        assertEquals("d@e.f", engine.query(User.class).eq(User::getId, 2).one().block().getEmail());
    }

    @Test
    @DisplayName("close 后再查询返回空结果")
    void close() {
        engine.close();
        assertTrue(engine.query(User.class).list().collectList().block().isEmpty());
    }

    private static List<Integer> ids(List<User> list) {
        assertNotNull(list);
        return list.stream().map(User::getId).toList();
    }

    /**
     * 测试实体（表名 user）。
     */
    public static class User {
        private Integer id;
        private String name;
        private Integer age;
        private String email;

        public User() {
        }

        User(Integer id, String name, Integer age, String email) {
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

        /** setName 方法 */
        public void setName(String name) {
            this.name = name;
        }

        /** getAge 方法 */
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