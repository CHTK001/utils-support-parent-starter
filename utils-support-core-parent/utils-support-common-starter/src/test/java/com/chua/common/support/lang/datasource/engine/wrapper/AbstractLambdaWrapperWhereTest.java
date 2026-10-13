package com.chua.common.support.lang.datasource.engine.wrapper;

import org.junit.jupiter.api.Test;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.ast.parser.DefaultExpressionParser;
import com.chua.common.support.lang.datasource.engine.DefaultEngineOrm;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AbstractLambdaWrapper#buildWhere} 的 WHERE 片段渲染测试。
 *
 * <p>重点是 {@code OR} 分组与 {@code AND} 条件混排时的括号：SQL 中 {@code AND} 优先级
 * 高于 {@code OR}，不加括号会让链式书写的意图与数据库的实际求值分叉。</p>
 */
class AbstractLambdaWrapperWhereTest {

    private String where(LambdaQueryWrapper<?> wrapper) {
        StringBuilder sb = new StringBuilder();
        wrapper.buildWhere(sb, Wraps.list());
        return sb.toString();
    }

    private List<Object> params(LambdaQueryWrapper<?> wrapper) {
        StringBuilder sb = new StringBuilder();
        List<Object> params = Wraps.list();
        wrapper.buildWhere(sb, params);
        return params;
    }

    private LambdaQueryWrapper<Object> wrapper() {
        return new LambdaQueryWrapper<>(Object.class);
    }

    @Test
    void singleCondition() {
        assertEquals("a = ?", where(wrapper().eq("a", 1)));
    }

    @Test
    void multipleLeafConditionsJoinWithAnd() {
        assertEquals("a = ? AND b = ?", where(wrapper().eq("a", 1).eq("b", 2)));
    }

    @Test
    void trailingOrGroupKeepsJoiningWithOr() {
        // 既有行为不变：OR 分组与前文以 OR 连接
        assertEquals("a = ? OR (b = ?)", where(wrapper().eq("a", 1).or(w -> w.eq("b", 2))));
    }

    @Test
    void orGroupFollowedByAndGroupIsParenthesized() {
        // 修复前渲染为 "a = ? OR (b = ? OR c = ?) AND (d = ?)"，
        // 数据库会按 "a = ? OR ((b OR c) AND d)" 求值，与链式意图不符
        String sql = where(wrapper()
                .eq("a", 1)
                .or(w -> w.eq("b", 2).eq("c", 3))
                .and(w -> w.eq("d", 4)));
        assertEquals("(a = ? OR (b = ? OR c = ?)) AND (d = ?)", sql);
    }

    @Test
    void orGroupFollowedByLeafConditionIsParenthesized() {
        assertEquals("(a = ? OR (b = ?)) AND c = ?",
                where(wrapper().eq("a", 1).or(w -> w.eq("b", 2)).eq("c", 3)));
    }

    @Test
    void leadingOrGroupNeedsNoOuterParentheses() {
        // OR 分组自身已被 renderCondition 括起，前面没有裸条件，无需再加括号
        assertEquals("(a = ?) AND (b = ?)",
                where(wrapper().or(w -> w.eq("a", 1)).and(w -> w.eq("b", 2))));
    }

    @Test
    void andNestingOnlyUsesAnd() {
        assertEquals("a = ? AND (b = ? AND c = ?)",
                where(wrapper().eq("a", 1).and(w -> w.eq("b", 2).eq("c", 3))));
    }

    @Test
    void leafConditionAppendedWithAndJoinsWithAnd() {
        assertEquals("t = ? AND a <= ?",
                where(wrapper().eq("t", 42L).and(Condition.of("a", "<=", 1))));
    }

    @Test
    void leafConditionAppendedWithOrJoinsWithOr() {
        assertEquals("t = ? OR (a <= ?)",
                where(wrapper().eq("t", 42L).or(Condition.of("a", "<=", 1))));
    }

    @Test
    void orGroupAppendedWithAndKeepsAndSemantics() {
        // or(Condition) 产出的是 OR 分组；直接并入条件列表会被 buildWhere
        // 判成 leadingOr 而与前文以 OR 连接，and(...) 必须把它包一层 AND
        Condition orGroup = Condition.or(List.of(
                Condition.of("a", "=", 1), Condition.of("b", "=", 2)));
        assertEquals("t = ? AND ((a = ? OR b = ?))",
                where(wrapper().eq("t", 42L).and(orGroup)));
    }

    @Test
    void parsedConditionAppendedWithAndKeepsAndSemantics() {
        // parse("NOT (age > 18 AND status = 'active')") 走德摩根律产出 OR 分组，
        // and(...) 接入后仍是 AND 语义
        Condition parsed = new DefaultEngineOrm()
                .parse("NOT (age > 18 AND status = 'active')", new DefaultExpressionParser());
        assertEquals("t = ? AND ((age <= ? OR status != ?))",
                where(wrapper().eq("t", 42L).and(parsed)));
    }

    @Test
    void parsedConditionCanBeTheOnlyCondition() {
        // 常见写法：整条筛选就是一个表达式，前面不挂任何固定条件
        Condition parsed = new DefaultEngineOrm()
                .parse("age > 18 AND status = 'active'", new DefaultExpressionParser());
        assertEquals("(age > ? AND status = ?)", where(wrapper().and(parsed)));
    }

    @Test
    void parsedConditionAsOnlyConditionProducesExecutableQuerySql() {
        LambdaQueryWrapper<Object> w = wrapper().and(new DefaultEngineOrm()
                .parse("age > 18 AND status = 'active'", new DefaultExpressionParser()));
        QuerySql<Object> sql = w.buildSql();
        assertEquals("(age > ? AND status = ?)", sql.whereClause());
        assertEquals(List.of(18, "active"), sql.params());
    }

    @Test
    void parsedNotConditionAloneNeedsNoExtraWrapper() {
        // parse 产出 OR 分组；前面没有条件时不存在 OR 连接问题，不必再包一层 AND
        Condition parsed = new DefaultEngineOrm()
                .parse("NOT (age > 18 AND status = 'active')", new DefaultExpressionParser());
        assertEquals("(age <= ? OR status != ?)", where(wrapper().and(parsed)));
    }

    @Test
    void parsedNotConditionFollowedByConditionIsWrapped() {
        // 前面已有条件时必须包一层 AND，否则会被 buildWhere 以 OR 连接
        Condition parsed = new DefaultEngineOrm()
                .parse("NOT (age > 18 AND status = 'active')", new DefaultExpressionParser());
        // and(...) 已把 OR 分组包成 AND 组，buildWhere 的 OR 补括号逻辑不再触发
        assertEquals("t = ? AND ((age <= ? OR status != ?)) AND c = ?",
                where(wrapper().eq("t", 42L).and(parsed).eq("c", 3)));
    }

    @Test
    void withExpressionIsShorthandForAndOfParse() {
        String viaShorthand = where(wrapper().withExpression("age > 18 AND status = 'active'"));
        String viaExplicit = where(wrapper().and(new DefaultEngineOrm()
                .parse("age > 18 AND status = 'active'", new DefaultExpressionParser())));
        assertEquals(viaExplicit, viaShorthand);
        assertEquals("(age > ? AND status = ?)", viaShorthand);
    }

    @Test
    void withExpressionChainsWithOtherConditions() {
        assertEquals("t = ? AND (age > ? AND status = ?) AND c = ?",
                where(wrapper().eq("t", 42L)
                        .withExpression("age > 18 AND status = 'active'")
                        .eq("c", 3)));
    }

    @Test
    void withExpressionWithExplicitParser() {
        // 单个叶子条件不需要括号包裹
        assertEquals("age > ?", where(wrapper().withExpression("age > 18", new DefaultExpressionParser())));
    }

    @Test
    void withExpressionRejectsMalformedExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> wrapper().withExpression("age > 18 AND"));
        assertThrows(IllegalArgumentException.class,
                () -> wrapper().withExpression("   "));
        assertThrows(IllegalArgumentException.class,
                () -> wrapper().withExpression("age CONTAINS 18"));
    }

    @Test
    void andConditionThenTrailingOrGroupNeedsNoParens() {
        // OR 段位于末尾，SQL 的 AND 优先级更高也不会改变求值：
        // "t AND a OR (b)" 即 "(t AND a) OR b"，与链式意图一致
        assertEquals("t = ? AND a = ? OR (b = ?)",
                where(wrapper().eq("t", 42L).and(Condition.of("a", "=", 1))
                        .or(Condition.of("b", "=", 2))));
    }

    @Test
    void orGroupFollowedByAndConditionIsParenthesized() {
        assertEquals("(t = ? OR (a = ?)) AND b = ?",
                where(wrapper().eq("t", 42L).or(Condition.of("a", "=", 1))
                        .and(Condition.of("b", "=", 2))));
    }

    @Test
    void nullConditionIsRejected() {
        assertThrows(NullPointerException.class, () -> wrapper().and((Condition) null));
        assertThrows(NullPointerException.class, () -> wrapper().or((Condition) null));
    }

    @Test
    void andGroupThenOrGroupIsNotRewrapped() {
        // OR 段位于末尾，无需再括：AND 优先级更高也不会改变这里的求值
        assertEquals("(a = ?) OR (b = ?)",
                where(wrapper().and(w -> w.eq("a", 1)).or(w -> w.eq("b", 2))));
    }

    @Test
    void parameterOrderSurvivesParenthesization() {
        List<Object> params = params(wrapper()
                .eq("a", 1)
                .or(w -> w.eq("b", 2).eq("c", 3))
                .and(w -> w.eq("d", 4)));
        assertEquals(List.of(1, 2, 3, 4), params);
    }

    @Test
    void emptyWrapperRendersNothing() {
        assertEquals("", where(wrapper()));
    }
}
