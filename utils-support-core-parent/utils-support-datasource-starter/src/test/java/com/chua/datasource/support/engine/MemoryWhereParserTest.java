package com.chua.datasource.support.engine;

import com.chua.datasource.support.engine.fixture.FixtureUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存 WHERE 条件解析器单元测试。
 * <p>覆盖比较、LIKE、IN、BETWEEN、NULL 判断、括号分组与蛇形列名兼容。</p>
 *
 * @author CH
 */
@DisplayName("MemoryWhereParser 内存条件解析测试")
class MemoryWhereParserTest {

    /**
     * 解析 WHERE 子句为谓词。
     *
     * @param where  WHERE 子句
     * @param params 参数列表
     * @return 谓词
     */
    private Predicate<FixtureUser> parse(String where, Object... params) {
        return new MemoryWhereParser().parse(where, List.of(params));
    }

    /**
     * 测试：EmptyClause。
     */
    @Test
    @DisplayName("空条件恒为 true")
    void testEmptyClause() {
        FixtureUser user = new FixtureUser(1L, "张三", 20, 1L, 10.0);
        assertTrue(new MemoryWhereParser().parse(null, List.of()).test(user));
        assertTrue(new MemoryWhereParser().parse("  ", List.of()).test(user));
    }

    /**
     * 测试：Equals。
     */
    @Test
    @DisplayName("等值匹配(支持数字参数自动转型)")
    void testEquals() {
        FixtureUser zhangsan = new FixtureUser(1L, "张三", 20, 1L, 10.0);
        FixtureUser lisi = new FixtureUser(2L, "李四", 30, 2L, 20.0);
        Predicate<FixtureUser> byName = parse("name = ?", "张三");
        Predicate<FixtureUser> byAge = parse("age = ?", "20");
        assertTrue(byName.test(zhangsan));
        assertFalse(byName.test(lisi));
        assertTrue(byAge.test(zhangsan));
        assertFalse(byAge.test(lisi));
    }

    /**
     * 测试：Comparison。
     */
    @Test
    @DisplayName("不等与大小比较")
    void testComparison() {
        FixtureUser user = new FixtureUser(1L, "张三", 25, 1L, 10.0);
        assertTrue(parse("age != ?", 18).test(user));
        assertTrue(parse("age > ?", 24).test(user));
        assertTrue(parse("age >= ?", 25).test(user));
        assertTrue(parse("age < ?", 26).test(user));
        assertTrue(parse("age <= ?", 25).test(user));
        assertFalse(parse("age > ?", 25).test(user));
    }

    /**
     * 测试：SnakeCase列。
     */
    @Test
    @DisplayName("蛇形列名(dept_id)可正确解析到 deptId 属性")
    void testSnakeCaseColumn() {
        FixtureUser user = new FixtureUser(1L, "张三", 25, 7L, 10.0);
        assertTrue(parse("dept_id = ?", 7L).test(user));
        assertFalse(parse("dept_id = ?", 8L).test(user));
    }

    /**
     * 测试：Like。
     */
    @Test
    @DisplayName("LIKE 按包含关系匹配")
    void testLike() {
        FixtureUser user = new FixtureUser(1L, "张三丰", 25, 1L, 10.0);
        assertTrue(parse("name LIKE ?", "%张三%").test(user));
        assertFalse(parse("name LIKE ?", "%李四%").test(user));
    }

    /**
     * 测试：In。
     */
    @Test
    @DisplayName("IN / NOT IN")
    void testIn() {
        FixtureUser user = new FixtureUser(1L, "张三", 25, 1L, 10.0);
        assertTrue(parse("age IN (?, ?)", 25, 30).test(user));
        assertFalse(parse("age IN (?, ?)", 26, 30).test(user));
        assertTrue(parse("age NOT IN (?, ?)", 26, 30).test(user));
    }

    /**
     * 测试：Between。
     */
    @Test
    @DisplayName("BETWEEN 闭区间")
    void testBetween() {
        FixtureUser user = new FixtureUser(1L, "张三", 25, 1L, 10.0);
        assertTrue(parse("age BETWEEN ? AND ?", 25, 30).test(user));
        assertTrue(parse("age BETWEEN ? AND ?", 20, 25).test(user));
        assertFalse(parse("age BETWEEN ? AND ?", 26, 30).test(user));
    }

    /**
     * 测试：NullChecks。
     */
    @Test
    @DisplayName("IS NULL / IS NOT NULL")
    void testNullChecks() {
        FixtureUser user = new FixtureUser(1L, null, 25, 1L, 10.0);
        assertTrue(parse("name IS NULL").test(user));
        assertTrue(parse("age IS NOT NULL").test(user));
        assertFalse(parse("name IS NOT NULL").test(user));
    }

    /**
     * 测试：AndCombination。
     */
    @Test
    @DisplayName("AND 组合:多条件同时满足")
    void testAndCombination() {
        FixtureUser match = new FixtureUser(1L, "张三", 25, 1L, 10.0);
        FixtureUser mismatch = new FixtureUser(2L, "张三", 17, 1L, 10.0);
        Predicate<FixtureUser> p = parse("name = ? AND age >= ?", "张三", 18);
        assertTrue(p.test(match));
        assertFalse(p.test(mismatch));
    }

    /**
     * 测试：GroupedOr。
     */
    @Test
    @DisplayName("括号分组 + OR 优先级")
    void testGroupedOr() {
        FixtureUser young = new FixtureUser(1L, "张三", 17, 1L, 10.0);
        FixtureUser oldVip = new FixtureUser(2L, "李四", 40, 9L, 10.0);
        FixtureUser normal = new FixtureUser(3L, "王五", 30, 1L, 10.0);
        // (age < 18 OR dept_id = 9) 的用户
        Predicate<FixtureUser> p = parse("(age < ?) OR (dept_id = ?)", 18, 9L);
        assertTrue(p.test(young));
        assertTrue(p.test(oldVip));
        assertFalse(p.test(normal));
    }
}
