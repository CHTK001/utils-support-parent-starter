package com.chua.solr.support.engine;

import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SolrEngine 查询构建语义单元测试（无需 Solr 服务）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class SolrQueryBuildTest {

    @Test
    void eqEscapesSpecialChars() {
        assertEquals("name:a\\:b",
                SolrEngine.buildConditionQuery(Condition.of("name", "=", "a:b")));
    }

    @Test
    void quotedPhraseForSpaces() {
        assertEquals("name:\"hello world\"",
                SolrEngine.buildConditionQuery(Condition.of("name", "=", "hello world")));
    }

    @Test
    void likeConvertsSqlWildcardsWithoutPadding() {
        assertEquals("foo*bar", SolrEngine.likePattern("foo%bar"));
        assertEquals("ab?cd", SolrEngine.likePattern("ab_cd"));
        // 字面量 '*' 必须转义，避免被当作通配符
        assertEquals("a\\*b", SolrEngine.likePattern("a*b"));
        // 空格转义而非引号包裹，保持通配符可用
        assertEquals("hello\\ world", SolrEngine.likePattern("hello world"));
        // 包装器已带 % 前后缀，保持原样不再补 '*'
        assertEquals("name:*foo*",
                SolrEngine.buildConditionQuery(Condition.of("name", "LIKE", "%foo%")));
    }

    @Test
    void notLikeNegatesPattern() {
        assertEquals("-name:*x",
                SolrEngine.buildConditionQuery(Condition.of("name", "NOT LIKE", "%x")));
    }

    @Test
    void emptyInMatchesNothingAndEmptyNotInMatchesAll() {
        assertEquals("-*:*",
                SolrEngine.buildConditionQuery(Condition.of("id", "IN", Collections.emptyList())));
        assertEquals("*:*",
                SolrEngine.buildConditionQuery(Condition.of("id", "NOT IN", Collections.emptyList())));
    }

    @Test
    void inBuildsOrGroup() {
        assertEquals("id:(1 2 3)",
                SolrEngine.buildConditionQuery(Condition.of("id", "IN", Arrays.asList(1, 2, 3))));
    }

    @Test
    void nullColumnAndUnknownOperatorThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> SolrEngine.buildConditionQuery(Condition.of(null, "=", "x")));
        assertThrows(UnsupportedOperationException.class,
                () -> SolrEngine.buildConditionQuery(Condition.of("id", "REGEXP", "x")));
    }

    @Test
    void nestedOrJoinsWithOperator() {
        List<Condition> nested = Arrays.asList(
                Condition.of("a", "=", "1"),
                Condition.of("b", "=", "2"));
        assertEquals("(a:1 OR b:2)", SolrEngine.buildConditionQuery(Condition.or(nested)));
    }

    @Test
    void buildSolrQueryJoinsWithAnd() {
        assertEquals("*:*", SolrEngine.buildSolrQuery(null));
        assertEquals("a:1 AND b:2", SolrEngine.buildSolrQuery(Arrays.asList(
                Condition.of("a", "=", 1),
                Condition.of("b", "=", 2))));
    }
}
