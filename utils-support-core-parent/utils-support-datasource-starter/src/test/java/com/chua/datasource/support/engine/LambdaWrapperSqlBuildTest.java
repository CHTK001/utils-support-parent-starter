package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.datasource.support.engine.fixture.FixtureUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lambda 查询包装器 SQL 构建单元测试（纯离线，不依赖数据库）。
 * <p>验证 WHERE 渲染、参数顺序、投影、JOIN、GROUP BY/HAVING、ORDER BY 与分页部件。</p>
 *
 * @author CH
 */
@DisplayName("LambdaQueryWrapper SQL 构建测试")
class LambdaWrapperSqlBuildTest {

    /**
     * 构建一个全新的查询包装器。
     *
     * @return 包装器实例
     */
    private LambdaQueryWrapper<FixtureUser> wrapper() {
        return new LambdaQueryWrapper<>(FixtureUser.class);
    }

    @Test
    @DisplayName("比较运算符渲染:列名蛇形 + ? 占位符 + 参数顺序")
    void testComparisonOperators() {
        QuerySql<FixtureUser> sql = wrapper()
                .eq(FixtureUser::getName, "张三")
                .ne(FixtureUser::getAge, 18)
                .gt(FixtureUser::getAge, 20)
                .ge(FixtureUser::getAge, 21)
                .lt(FixtureUser::getAge, 60)
                .le(FixtureUser::getAge, 59)
                .buildSql();

        assertEquals("name = ? AND age != ? AND age > ? AND age >= ? AND age < ? AND age <= ?",
                sql.whereClause());
        assertEquals(List.of("张三", 18, 20, 21, 60, 59), sql.params());
        assertTrue(sql.hasWhere());
    }

    @Test
    @DisplayName("LIKE 系列:%值% / %值 / 值%")
    void testLikeVariants() {
        QuerySql<FixtureUser> full = wrapper().like(FixtureUser::getName, "张").buildSql();
        assertEquals("name LIKE ?", full.whereClause());
        assertEquals("%张%", full.params().get(0));

        QuerySql<FixtureUser> left = wrapper().likeLeft(FixtureUser::getName, "三").buildSql();
        assertEquals("%三", left.params().get(0));

        QuerySql<FixtureUser> right = wrapper().likeRight(FixtureUser::getName, "张").buildSql();
        assertEquals("张%", right.params().get(0));
    }

    @Test
    @DisplayName("IN / NOT IN:占位符展开与参数收集")
    void testInAndNotIn() {
        QuerySql<FixtureUser> in = wrapper()
                .in(FixtureUser::getAge, List.of(18, 20, 22))
                .buildSql();
        assertEquals("age IN (?, ?, ?)", in.whereClause());
        assertEquals(List.of(18, 20, 22), in.params());

        QuerySql<FixtureUser> notIn = wrapper()
                .notIn(FixtureUser::getDeptId, List.of(1L, 2L))
                .buildSql();
        assertEquals("dept_id NOT IN (?, ?)", notIn.whereClause());
        assertEquals(List.of(1L, 2L), notIn.params());
    }

    @Test
    @DisplayName("BETWEEN:两个占位符,参数先小后大")
    void testBetween() {
        QuerySql<FixtureUser> sql = wrapper()
                .between(FixtureUser::getAge, 18, 30)
                .buildSql();
        assertEquals("age BETWEEN ? AND ?", sql.whereClause());
        assertEquals(List.of(18, 30), sql.params());
    }

    @Test
    @DisplayName("IS NULL / IS NOT NULL:不产生参数")
    void testNullPredicates() {
        QuerySql<FixtureUser> isNull = wrapper().isNull(FixtureUser::getName).buildSql();
        assertEquals("name IS NULL", isNull.whereClause());
        assertTrue(isNull.params().isEmpty());

        QuerySql<FixtureUser> notNull = wrapper().isNotNull(FixtureUser::getName).buildSql();
        assertEquals("name IS NOT NULL", notNull.whereClause());
        assertTrue(notNull.params().isEmpty());
    }

    @Test
    @DisplayName("AND/OR 嵌套分组:括号包裹")
    void testNestedGroups() {
        QuerySql<FixtureUser> sql = wrapper()
                .eq(FixtureUser::getDeptId, 1L)
                .and(g -> g.gt(FixtureUser::getAge, 30).lt(FixtureUser::getAge, 50))
                .or(g -> g.eq(FixtureUser::getName, "管理员"))
                .buildSql();

        assertEquals("dept_id = ? AND (age > ? AND age < ?) OR (name = ?)", sql.whereClause());
        assertEquals(List.of(1L, 30, 50, "管理员"), sql.params());
    }

    @Test
    @DisplayName("ORDER BY:升降序渲染")
    void testOrderBy() {
        QuerySql<FixtureUser> sql = wrapper()
                .orderByAsc(FixtureUser::getAge)
                .orderByDesc(FixtureUser::getName)
                .buildSql();
        assertEquals(List.of("age ASC", "name DESC"), sql.orderBys());
        assertTrue(sql.hasOrderBy());
    }

    @Test
    @DisplayName("投影:Lambda 列 + 聚合函数 AS 别名")
    void testSelectProjection() {
        QuerySql<FixtureUser> sql = wrapper()
                .select(FixtureUser::getName)
                .selectSum("amount", "totalAmount")
                .selectCount("cnt")
                .groupBy(FixtureUser::getName)
                .buildSql();
        assertEquals(List.of("name", "SUM(amount) AS totalAmount", "COUNT(*) AS cnt"),
                sql.selectColumns());
        assertEquals("name", sql.groupByColumn());
        assertTrue(sql.hasGroupBy());
    }

    @Test
    @DisplayName("GROUP BY + HAVING:参数与 WHERE 参数分离")
    void testGroupByHaving() {
        QuerySql<FixtureUser> sql = wrapper()
                .gt(FixtureUser::getAge, 18)
                .groupBy(FixtureUser::getDeptId)
                .having("SUM(amount) > ?", 100.0)
                .buildSql();
        assertEquals("dept_id", sql.groupByColumn());
        assertEquals("SUM(amount) > ?", sql.havingClause());
        assertEquals(List.of(100.0), sql.havingParams());
        assertEquals(List.of(18), sql.params());
        assertTrue(sql.hasHaving());
    }

    @Test
    @DisplayName("JOIN:INNER/LEFT/RIGHT 类型、别名与 ON 条件保留")
    void testJoins() {
        QuerySql<FixtureUser> sql = wrapper()
                .innerJoin("fixture_order o", "fixture_user.id = o.user_id")
                .leftJoin("fixture_dept", "d", "fixture_user.dept_id = d.id")
                .buildSql();
        assertEquals(2, sql.joins().size());
        assertEquals("INNER", sql.joins().get(0).joinType());
        assertEquals("fixture_order o", sql.joins().get(0).renderTable());
        assertEquals("fixture_user.id = o.user_id", sql.joins().get(0).onCondition());
        assertEquals("LEFT", sql.joins().get(1).joinType());
        assertEquals("d", sql.joins().get(1).alias());
        assertTrue(sql.hasJoins());
    }

    @Test
    @DisplayName("LIMIT / OFFSET:部件记录与非法参数校验")
    void testLimitOffset() {
        QuerySql<FixtureUser> sql = wrapper().limit(10).offset(20).buildSql();
        assertEquals(10, sql.limit());
        assertEquals(20, sql.offset());
        assertTrue(sql.hasLimit());
        assertTrue(sql.hasOffset());

        QuerySql<FixtureUser> plain = wrapper().buildSql();
        assertFalse(plain.hasLimit());
        assertFalse(plain.hasOffset());
    }

    @Test
    @DisplayName("非法 limit/offset 抛出 IllegalArgumentException")
    void testInvalidPagingArgs() {
        assertThrows(IllegalArgumentException.class, () -> wrapper().limit(0));
        assertThrows(IllegalArgumentException.class, () -> wrapper().offset(-1));
    }

    @Test
    @DisplayName("空条件:WHERE 为空串,参数为空")
    void testEmptyCondition() {
        QuerySql<FixtureUser> sql = wrapper().buildSql();
        assertEquals("", sql.whereClause());
        assertTrue(sql.params().isEmpty());
        assertFalse(sql.hasWhere());
    }

    /**
     * JUnit5 断言别名封装（避免静态导入冲突时的兜底入口）。
     *
     * @param expected 期望异常类型
     * @param runnable 可能抛出异常的动作
     */
    private static void assertThrows(Class<? extends Throwable> expected, Runnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(expected, runnable::run);
    }
}
