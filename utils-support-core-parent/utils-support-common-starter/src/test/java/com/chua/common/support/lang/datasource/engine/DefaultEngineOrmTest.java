package com.chua.common.support.lang.datasource.engine;

import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.lang.ast.parser.DefaultExpressionParser;
import com.chua.common.support.lang.ast.parser.SqlExpressionParser;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultEngineOrm} 的表达式翻译测试。
 *
 * <p>重点覆盖三处容易出错的地方：IN 列表拆分、BETWEEN 边界拆包、NOT 的德摩根下推。</p>
 */
class DefaultEngineOrmTest {

    private final EngineOrm orm = new DefaultEngineOrm();

    private final ExpressionParser parser = new DefaultExpressionParser();

    private Condition parse(String expression) {
        return orm.parse(expression, parser);
    }

    @Test
    void simpleComparisonBecomesLeafCondition() {
        Condition c = parse("age > 18");
        assertFalse(c.isNested());
        assertEquals("age", c.getColumnName());
        assertEquals(">", c.getOperator());
        assertEquals(18, c.getValue());
    }

    @Test
    void andBecomesNestedCondition() {
        Condition c = parse("age > 18 AND status = 'active'");
        assertTrue(c.isNested());
        assertEquals("AND", c.getNestedOperator());
        List<Condition> nested = c.getNested();
        assertEquals(2, nested.size());
        assertEquals("age", nested.get(0).getColumnName());
        assertEquals("status", nested.get(1).getColumnName());
        assertEquals("active", nested.get(1).getValue());
    }

    @Test
    void orBecomesNestedCondition() {
        Condition c = parse("role = 'admin' OR role = 'super'");
        assertTrue(c.isNested());
        assertEquals("OR", c.getNestedOperator());
        assertEquals(2, c.getNested().size());
    }

    @Test
    void inListIsSplitIntoCollection() {
        Condition c = parse("id IN (1, 2, 3)");
        assertEquals("IN", c.getOperator());
        // 整数字面量定型为 Integer，与解析器对 BETWEEN / 普通比较的产出保持一致
        assertEquals(List.of(1, 2, 3), c.getValue());
    }

    @Test
    void inListAndBetweenShareSameNumericType() {
        Object[] range = (Object[]) parse("age BETWEEN 18 AND 60").getValue();
        assertInstanceOf(Integer.class, range[0]);
        Collection<?> inList = (Collection<?>) parse("id IN (1)").getValue();
        assertInstanceOf(Integer.class, inList.iterator().next());
    }

    @Test
    void inListKeepsDecimalAsDouble() {
        assertEquals(List.of(1.5, 2.5), parse("score IN (1.5, 2.5)").getValue());
    }

    @Test
    void inListKeepsStringElements() {
        Condition c = parse("status IN ('a', 'b')");
        assertEquals(List.of("a", "b"), c.getValue());
    }

    @Test
    void betweenBecomesObjectArray() {
        Condition c = parse("age BETWEEN 18 AND 60");
        assertEquals("BETWEEN", c.getOperator());
        // 边界沿用解析器给出的已定型值（Integer），不重新做字面量推断
        assertArrayEquals(new Object[]{18, 60}, (Object[]) c.getValue());
    }

    @Test
    void isNullCarriesNoValue() {
        assertEquals("IS NULL", parse("deleted_at IS NULL").getOperator());
        assertNull(parse("deleted_at IS NULL").getValue());
        assertEquals("IS NOT NULL", parse("deleted_at IS NOT NULL").getOperator());
    }

    @Test
    void notOnLeafUsesInverseOperator() {
        assertEquals("!=", parse("NOT status = 'active'").getOperator());
        assertEquals("<=", parse("NOT age > 18").getOperator());
        assertEquals("NOT LIKE", parse("NOT name LIKE '%x%'").getOperator());
        assertEquals("NOT IN", parse("NOT id IN (1, 2)").getOperator());
        assertEquals("IS NOT NULL", parse("NOT deleted_at IS NULL").getOperator());
    }

    @Test
    void notOverAndBecomesOrByDeMorgan() {
        // NOT(A AND B) = NOT A OR NOT B
        Condition c = parse("NOT (age > 18 AND status = 'active')");
        assertTrue(c.isNested());
        assertEquals("OR", c.getNestedOperator());
        List<Condition> nested = c.getNested();
        assertEquals(2, nested.size());
        assertEquals("<=", nested.get(0).getOperator());
        assertEquals("!=", nested.get(1).getOperator());
    }

    @Test
    void notOverOrBecomesAndByDeMorgan() {
        // NOT(A OR B) = NOT A AND NOT B
        Condition c = parse("NOT (role = 'admin' OR role = 'super')");
        assertTrue(c.isNested());
        assertEquals("AND", c.getNestedOperator());
        assertEquals(2, c.getNested().size());
    }

    @Test
    void notBetweenIsExpandedToOutsideRange() {
        // NOT BETWEEN 无对应算子，展开为 (col < low OR col > high)
        Condition c = parse("NOT age BETWEEN 18 AND 60");
        assertTrue(c.isNested());
        assertEquals("OR", c.getNestedOperator());
        List<Condition> outside = c.getNested();
        assertEquals(2, outside.size());
        assertEquals("<", outside.get(0).getOperator());
        assertEquals(18, outside.get(0).getValue());
        assertEquals(">", outside.get(1).getOperator());
        assertEquals(60, outside.get(1).getValue());
    }

    @Test
    void doubleNegationCollapses() {
        assertEquals(">", parse("NOT NOT age > 18").getOperator());
    }

    @Test
    void sqlParserWorksAsSource() {
        Condition c = orm.parse("age > 18 AND status = 'active'", new SqlExpressionParser());
        assertTrue(c.isNested());
        assertEquals("AND", c.getNestedOperator());
    }

    @Test
    void injectionThroughColumnNameIsRejected() {
        // 表达式文本不可信：列名必须过白名单
        assertThrows(IllegalArgumentException.class,
                () -> parse("age; DROP TABLE user -- > 18"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("' OR 1=1 --' = 18"));
    }

    @Test
    void unsupportedOperatorIsRejectedExplicitly() {
        // CONTAINS 透传会生成 "col CONTAINS ?"，运行期才炸；宁可编译期失败
        assertThrows(IllegalArgumentException.class, () -> parse("age CONTAINS 18"));
    }

    @Test
    void bareColumnIsRejectedAsPredicate() {
        assertThrows(IllegalArgumentException.class, () -> parse("age"));
    }

    @Test
    void nullParserIsRejected() {
        assertThrows(UnsupportedOperationException.class, () -> orm.parse("age > 18", null));
    }

    @Test
    void blankExpressionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("   "));
    }
}