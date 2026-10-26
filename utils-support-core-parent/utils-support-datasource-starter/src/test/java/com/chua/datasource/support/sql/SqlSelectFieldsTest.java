package com.chua.datasource.support.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlSelectFields} 的行为约定。
 *
 * <p>核心约定只有一条：<b>拿不准就返回不可解析</b>。列名猜错会让下游按名取值全部落空，
 * 比多连一次库严重得多。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
class SqlSelectFieldsTest {

    @Test
    @DisplayName("普通列名按书写顺序返回")
    void plainColumns() {
        SqlSelectFields.Result result = SqlSelectFields.parse("select id, name, age from t_user");
        assertTrue(result.resolvable());
        assertEquals(List.of("id", "name", "age"), result.names());
    }

    @Test
    @DisplayName("AS 别名优先于列名")
    void aliasWins() {
        SqlSelectFields.Result result = SqlSelectFields.parse(
                "SELECT u.id AS user_id, u.name AS user_name FROM t_user u");
        assertTrue(result.resolvable());
        assertEquals(List.of("user_id", "user_name"), result.names());
    }

    @Test
    @DisplayName("省略 AS 的尾部别名")
    void implicitAlias() {
        SqlSelectFields.Result result = SqlSelectFields.parse(
                "select count(*) total, max(age) max_age from t_user");
        assertTrue(result.resolvable());
        assertEquals(List.of("total", "max_age"), result.names());
    }

    @Test
    @DisplayName("函数里的逗号不当作投影分隔符")
    void commaInsideFunction() {
        SqlSelectFields.Result result = SqlSelectFields.parse(
                "select concat(a, b) AS ab, ifnull(c, 0) AS c2 from t");
        assertTrue(result.resolvable());
        assertEquals(List.of("ab", "c2"), result.names());
    }

    @Test
    @DisplayName("子查询里的 FROM 不当作投影结束")
    void fromInsideSubquery() {
        SqlSelectFields.Result result = SqlSelectFields.parse(
                "select a, (select max(x) from t2) as mx from t1");
        assertTrue(result.resolvable());
        assertEquals(List.of("a", "mx"), result.names());
    }

    @Test
    @DisplayName("SELECT * 不可解析（字段要回库才知道）")
    void starIsNotResolvable() {
        assertFalse(SqlSelectFields.parse("select * from t").resolvable());
        assertFalse(SqlSelectFields.parse("select t.*, a from t").resolvable());
    }

    @Test
    @DisplayName("无别名的表达式不可解析（各家驱动给的名字不同）")
    void expressionWithoutAlias() {
        assertFalse(SqlSelectFields.parse("select count(*) from t").resolvable());
        assertFalse(SqlSelectFields.parse("select a + b from t").resolvable());
    }

    @Test
    @DisplayName("DISTINCT / TOP / 注释 / 引用符")
    void modifiersAndQuotes() {
        SqlSelectFields.Result result = SqlSelectFields.parse(
                "/* c1 */ SELECT DISTINCT `t`.`id`, \"name\", [age] FROM `t` -- tail\n");
        assertTrue(result.resolvable());
        assertEquals(List.of("id", "name", "age"), result.names());

        SqlSelectFields.Result top = SqlSelectFields.parse("select top 10 id, name from t");
        assertTrue(top.resolvable());
        assertEquals(List.of("id", "name"), top.names());
    }

    @Test
    @DisplayName("字符串字面量里的逗号和关键字不干扰切分")
    void stringLiteral() {
        SqlSelectFields.Result result = SqlSelectFields.parse(
                "select 'a,b' as s, name from t where x = 'from'");
        assertTrue(result.resolvable());
        assertEquals(List.of("s", "name"), result.names());
    }

    @Test
    @DisplayName("非 SELECT 语句不可解析")
    void notSelect() {
        assertFalse(SqlSelectFields.parse("update t set a = 1").resolvable());
        assertFalse(SqlSelectFields.parse("").resolvable());
        assertFalse(SqlSelectFields.parse(null).resolvable());
    }

    @Test
    @DisplayName("无 FROM 的 SELECT")
    void selectWithoutFrom() {
        SqlSelectFields.Result result = SqlSelectFields.parse("select 1 as one");
        assertTrue(result.resolvable());
        assertEquals(List.of("one"), result.names());
    }
}
