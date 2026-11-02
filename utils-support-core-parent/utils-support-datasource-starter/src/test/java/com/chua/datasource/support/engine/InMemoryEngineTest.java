package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.datasource.support.wrapper.EngineQueryWrapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryEngine} 行为约定。
 *
 * <p>核心约定：
 * <ul>
 *   <li>索引（{@code index()}）可以安全处理 {@code null} 字段值</li>
 *   <li>{@code LIKE} 使用 SQL 标准通配语义（{@code %} 任意序列 / {@code _} 单字符）</li>
 *   <li>{@code BETWEEN} 闭区间比较，无法比较时不抛异常</li>
 *   <li>{@code UPDATE} 的 SET 子句解析不依赖空格和 comma 间距</li>
 * </ul>
 * </p>
 */
class InMemoryEngineTest {

    private InMemoryEngine engine;

    /**
     * 行为约定：{@code query(User.class)} 按 {@link AbstractEngine#resolveTableName} 定位表名，
     * 存入的数据必须使用同一表名。
     */
    private static String table() {
        return AbstractEngine.resolveTableName(User.class);
    }

    @BeforeEach
    void setUp() {
        engine = new InMemoryEngine();
        storeRows();
    }

    private void storeRows() {
        engine.store(table(), List.of(
                new User(1, "张三", 18, "zhangsan@example.com"),
                new User(2, "李四", 25, "lisi@example.com"),
                new User(3, "王五", null, "wangwu@example.com"),
                new User(4, "赵六", 30, "zhaoliu@example.com"),
                new User(5, "李七", 35, null)));
    }

    @Test
    @DisplayName("无条件查询返回全部行")
    void listAll() {
        List<User> list = engine.query(User.class).list();
        assertEquals(5, list.size());
        assertEquals(List.of(1, 2, 3, 4, 5), idOrderBy(list));
    }

    @Test
    @DisplayName("等值查询走等值条件")
    void eq() {
        List<User> list = engine.query(User.class).eq(User::getName, "李四").list();
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).getId());
    }

    @Test
    @DisplayName("空值条件与 isNull")
    void nullFields() {
        List<User> emailNull = engine.query(User.class).isNull(User::getEmail).list();
        assertEquals(1, emailNull.size());
        assertEquals(5, emailNull.get(0).getId());

        List<User> ageNull = engine.query(User.class).eq(User::getAge, null).list();
        assertEquals(1, ageNull.size());
        assertEquals(3, ageNull.get(0).getId());

        assertEquals(1, engine.query(User.class).isNull(User::getAge).count());
    }

    @Test
    @DisplayName("null 字段值参与建索引不抛 NPE")
    void indexWithNullField() {
        // 断言：buildIndex 遇到 null 字段值时不发生 NPE（之前为必现）
        engine.index();

        User one = engine.query(User.class).eq(User::getId, 3).one();
        assertNull(one.getEmail());
        assertNull(one.getAge());

        // null 字段的等值查询也应有意义
        List<User> ageNull = engine.query(User.class).eq(User::getAge, null).list();
        assertEquals(1, ageNull.size());
        assertEquals(3, ageNull.get(0).getId());
    }

    @Test
    @DisplayName("LIKE 前缀匹配")
    void likePrefix() {
        List<User> list = engine.query(User.class).like(User::getName, "张%").list();
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getId());
    }

    @Test
    @DisplayName("LIKE 后缀匹配")
    void likeSuffix() {
        List<User> list = engine.query(User.class).like(User::getName, "%三").list();
        assertEquals(2, list.size());
        assertEquals(List.of(1, 3), idOrderBy(list));
    }

    @Test
    @DisplayName("LIKE 包含匹配")
    void likeContains() {
        List<User> list = engine.query(User.class).like(User::getAge, "3%").list();
        assertEquals(2, list.size());
        assertEquals(List.of(4, 5), idOrderBy(list));
    }

    @Test
    @DisplayName("LIKE 精确匹配等同于等值")
    void likeExact() {
      List<User> list = engine.query(User.class).like(User::getName, "王五").list();
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).getId());
    }

    @Test
    @DisplayName("LIKE 下划线单字符匹配")
    void likeUnderscore() {
        List<User> list = engine.query(User.class).like(User::getName, "%_五").list();
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).getId());
    }

    @Test
    @DisplayName("LIKE 无匹配")
    void likeNoMatch() {
        List<User> list = engine.query(User.class).like(User::getName, "%黄%").list();
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("BETWEEN 闭区间比较")
    void between() {
        List<User> list = engine.query(User.class).between(User::getAge, 20, 30).list();
        assertEquals(2, list.size());
        assertEquals(List.of(2, 4), idOrderBy(list));
    }

    @Test
    @DisplayName("BETWEEN 不含上界外数据")
    void betweenEdge() {
        List<User> list = engine.query(User.class).between(User::getAge, 18, 25).list();
        assertEquals(2, list.size());
        assertEquals(List.of(1, 2), idOrderBy(list));
    }

    @Test
    @DisplayName("BETWEEN 跨参数不抛 ClassCastException")
    void betweenStringArg() {
        // 字符串参数与数值列之间：转换失败不抛异常，而是空结果（类似 SQL 的隐式转换不匹配）
        List<User> list = engine.query(User.class).between(User::getAge, "18", "20").list();
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getId());
    }

    @Test
    @DisplayName("BETWEEN 数值区间无匹配行")
    void betweenArrayArg() {
        // 转换失败不抛 ClassCastException；区间不在数据范围内，返回空结果
        List<User> list = engine.query(User.class).between(User::getAge, 5, 10).list();
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("NULL BETWEEN 参数不抛异常")
    void betweenNullBounds() {
        List<User> list = engine.query(User.class).between(User::getAge, (Object) null, null).list();
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("OR 组合条件")
    void orConditions() {
        List<User> list = engine.query(User.class).or(u -> u
                .eq(User::getAge, 18)
                .like(User::getName, "%李%")).list();
        assertEquals(3, list.size());
        assertEquals(List.of(1, 2, 5), idOrderBy(list));
    }

    @Test
    @DisplayName("排序")
    void orderBy() {
        List<User> list = engine.query(User.class).orderByAsc(User::getAge).list();
        assertEquals(List.of(3, 1, 2, 4, 5), idOrderBy(list));
    }

    @Test
    @DisplayName("按字符串排序字段值 null 排在最前")
    void orderByNullFirst() {
        List<User> list = engine.query(User.class).orderByAsc(User::getEmail).list();
        assertEquals(List.of(5, 3, 2, 1, 4), idOrderBy(list));
    }

    @Test
    @DisplayName("SET 子句没有空格和空格分隔 comma 也能正确解析")
    void updateSetClause() {
        int affected = engine.executeUpdate(new UpdateSql<>(User.class,
                "age = ?,name = ?","id = ?", List.of(40, "刘八")));
        assertEquals(1, affected);

        User one = engine.query(User.class).eq(User::getId, 4).one();
        assertEquals(40, one.getAge());
        assertEquals("刘八", one.getName());
    }

    @Test
    @DisplayName("SET 字面量与占位符混合")
    void updateSetClauseLiteralAndPlaceholder() {
        int affected = engine.executeUpdate(new UpdateSql<>(User.class,
                "age = 45", "id = ?", List.of(4)));
        assertEquals(1, affected);

        User one = engine.query(User.class).eq(User::getId, 4).one();
        assertEquals(45, one.getAge());
    }

    @Test
    @DisplayName("SET 子句未闭合时抛出")
    void updateSetClauseMissingParams() {
        assertThrows(IllegalStateException.class, () ->
                engine.executeUpdate(new UpdateSql<>(User.class,
                        "age = ?, name = ?", "id = ?", List.of(20))));
    }

    @Test
    @DisplayName("删除")
    void delete() {
        int removed = engine.delete(User.class).eq(User::getId, 2).remove();
        assertEquals(1, removed);
        assertEquals(4, engine.query(User.class).count());
        assertEquals(0, engine.query(User.class).eq(User::getId, 2).count());
    }

    @Test
    @DisplayName("原生 SQL 查询 LIKE")
    void querySqlLike() {
        List<Map<String, Object>> rows = engine.querySql("SELECT * FROM " + table()
                + " WHERE name LIKE ?", "%四%");
        assertEquals(1, rows.size());
        assertEquals("李四", rows.get(0).get("name"));
    }

    @Test
    @DisplayName("原生 SQL 查询 BETWEEN")
    void querySqlBetween() {
        List<Map<String, Object>> rows = engine.querySql(
                "SELECT * FROM " + table() + " WHERE age BETWEEN ? AND ?", 18, 30);
        assertEquals(3, rows.size());
        assertEquals(List.of(1, 2, 4), idsFrom(rows));
    }

    @Test
    @DisplayName("原生 SQL 删除")
    void querySqlDelete() {
        int removed = engine.executeSql("DELETE FROM " + table() + " WHERE id = ?", 3);
        assertEquals(1, removed);
        assertEquals(4, engine.query(User.class).count());
    }

    @Test
    @DisplayName("非 AGE 列的 BETWEEN 返回空")
    void betweenNonComparable() {
        List<User> list = engine.query(User.class).between(User::getName, 18, 20).list();
        assertTrue(list.isEmpty());
    }

    private static List<Integer> idOrderBy(List<User> list) {
        return list.stream().map(User::getId).toList();
    }

    private static List<Integer> idsFrom(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (Integer) r.get("id")).toList();
    }

    /**
     * 测试实体
     */
    static class User {
        private Integer id;
        private String name;
        private Integer age;
        private String email;

        User() {
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

        @Override
        public String toString() {
            return "User{" + id + "," + name + "," + age + "," + email + "}";
        }
    }
}
