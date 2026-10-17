package com.chua.common.support.lang.datasource.engine.wrapper;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SqlQueryBuilder} 的 SQL 渲染、参数绑定、校验与执行终端测试。
 *
 * <p>本类用 {@link RecordingEngine} 捕获构建器真正下发给引擎的语句与参数，
 * 断言的是"拼接即执行"的端到端链路，而非孤立的字符串拼装。</p>
 */
class SqlQueryBuilderTest {

    // ==================== SQL 渲染 ====================

    @Test
    void rendersEveryClauseInSqlOrder() {
        String sql = new SqlQueryBuilder(new RecordingEngine())
                .select("u.id", "u.name", "o.amount")
                .table("t_user", "u")
                .innerJoin("t_order o", "u.id = o.user_id")
                .where("u.age > ?", 18)
                .and("u.status = ?", "active")
                .or("u.vip = ?", 1)
                .groupBy("u.id", "u.name", "o.amount")
                .having("SUM(o.amount) > ?", 100)
                .orderByDesc("o.amount")
                .orderByAsc("u.id")
                .limit(20)
                .offset(5)
                .sql();
        assertEquals("SELECT u.id, u.name, o.amount FROM t_user u"
                        + " INNER JOIN t_order o ON u.id = o.user_id"
                        + " WHERE u.age > ? AND u.status = ? OR u.vip = ?"
                        + " GROUP BY u.id, u.name, o.amount"
                        + " HAVING SUM(o.amount) > ?"
                        + " ORDER BY o.amount DESC, u.id ASC"
                        + " LIMIT 20 OFFSET 5",
                sql);
    }

    @Test
    void selectDefaultsToStar() {
        assertEquals("SELECT * FROM t_user",
                new SqlQueryBuilder(new RecordingEngine()).table("t_user").sql());
    }

    @Test
    void explicitStarIsAllowed() {
        assertEquals("SELECT * FROM t_user",
                new SqlQueryBuilder(new RecordingEngine()).select("*").table("t_user").sql());
    }

    @Test
    void tableAliasAndViewAliasBothRender() {
        RecordingEngine engine = new RecordingEngine();
        assertEquals("SELECT * FROM t_user u", engine.query().table("t_user", "u").sql());
        assertEquals("SELECT * FROM v_user v", engine.query().view("v_user", "v").sql());
    }

    @Test
    void viewWithoutAliasRendersLikeTable() {
        SqlQueryBuilder builder = new SqlQueryBuilder(new RecordingEngine()).view("v_summary");
        assertEquals("SELECT * FROM v_summary", builder.sql());
        assertEquals("VIEW", builder.getSourceKind());
    }

    @Test
    void tableKindIsDefaultSourceKind() {
        assertEquals("TABLE", new SqlQueryBuilder(new RecordingEngine()).table("t").getSourceKind());
    }

    @Test
    void singleLevelQualifiedNamesAreAcceptedAndDeeperIsRejected() {
        RecordingEngine engine = new RecordingEngine();
        // 白名单允许一段 schema.object 限定（如 db.t_user、u.id）
        assertEquals("SELECT * FROM db.t_user",
                engine.query().table("db.t_user").sql());
        assertEquals("SELECT u.id FROM t_user u",
                engine.query().select("u.id").table("t_user", "u").sql());
        // 但只允许一段：两级限定的列名被保守拒绝（文档口径：可带一段 schema 限定）
        assertThrows(IllegalArgumentException.class, () -> engine.query().select("db.t_user.id"));
    }

    @Test
    void leftAndRightJoinRenderTheirType() {
        assertEquals("SELECT * FROM a LEFT JOIN b ON a.id = b.aid",
                new SqlQueryBuilder(new RecordingEngine()).table("a").leftJoin("b", "a.id = b.aid").sql());
        assertEquals("SELECT * FROM a RIGHT JOIN b ON a.id = b.aid",
                new SqlQueryBuilder(new RecordingEngine()).table("a").rightJoin("b", "a.id = b.aid").sql());
    }

    @Test
    void joinWithExplicitAliasRendersAlias() {
        assertEquals("SELECT * FROM a INNER JOIN b x ON a.id = x.aid",
                new SqlQueryBuilder(new RecordingEngine())
                        .table("a").innerJoin("b", "x", "a.id = x.aid").sql());
    }

    @Test
    void orderByDefaultsToAscending() {
        assertEquals("SELECT * FROM t ORDER BY a ASC",
                new SqlQueryBuilder(new RecordingEngine()).table("t").orderBy("a").sql());
    }

    @Test
    void selectFuncAndSelectCountRenderAggregates() {
        assertEquals("SELECT COUNT(*) AS total, SUM(o.amount) AS amount FROM t_order",
                new SqlQueryBuilder(new RecordingEngine())
                        .selectCount("total")
                        .selectFunc("SUM", "o.amount", "amount")
                        .table("t_order")
                        .sql());
    }

    @Test
    void selectFuncWithoutAliasAndWithStarColumn() {
        assertEquals("SELECT COUNT(*), MAX(price) FROM t_order",
                new SqlQueryBuilder(new RecordingEngine())
                        .selectFunc("COUNT", null, null)
                        .selectFunc("MAX", "price", null)
                        .table("t_order")
                        .sql());
    }

    // ==================== 参数绑定顺序 ====================

    @Test
    void whereParamsComeBeforeHavingParams() {
        SqlQueryBuilder builder = new SqlQueryBuilder(new RecordingEngine())
                .table("t")
                .where("a > ?", 1)
                .and("b = ?", 2)
                .groupBy("c")
                .having("COUNT(*) > ?", 3)
                .having("SUM(d) < ?", 4);
        assertArrayEquals(new Object[]{1, 2, 3, 4}, builder.boundParams());
    }

    @Test
    void nullBoundValueIsPreserved() {
        SqlQueryBuilder builder = new SqlQueryBuilder(new RecordingEngine())
                .table("t").where("a = ?", (Object) null);
        Object[] bound = builder.boundParams();
        assertEquals(1, bound.length);
        assertNull(bound[0]);
    }

    @Test
    void noParamsYieldsEmptyArray() {
        assertEquals(0, new SqlQueryBuilder(new RecordingEngine()).table("t").boundParams().length);
    }

    // ==================== 校验 ====================

    @Test
    void sqlWithoutSourceIsRejected() {
        SqlQueryBuilder builder = new SqlQueryBuilder(new RecordingEngine()).select("a");
        assertThrows(IllegalStateException.class, builder::sql);
    }

    @Test
    void engineMustNotBeNull() {
        assertThrows(IllegalArgumentException.class, () -> new SqlQueryBuilder(null));
        assertThrows(IllegalArgumentException.class, () -> SqlQueryBuilder.of(null));
    }

    @Test
    void identifierInjectionIsRejected() {
        RecordingEngine engine = new RecordingEngine();
        // 语句拼接位点：分号、注释、空白、引号、括号都不可能在白名单内
        assertThrows(IllegalArgumentException.class, () -> engine.query().table("t_user; DROP TABLE x"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().table("t_user --"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().select("1=1"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().select("a) OR 1=1 --"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().table("t").orderBy("a; DELETE"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().table("t").groupBy("a'b"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().table("t").innerJoin("b;x", "1=1"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().table("t").table("lead\ning"));
    }

    @Test
    void emptyIdentifierIsRejected() {
        RecordingEngine engine = new RecordingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.query().table(""));
        assertThrows(IllegalArgumentException.class, () -> engine.query().table(null));
        assertThrows(IllegalArgumentException.class, () -> engine.query().select((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> engine.query().select());
    }

    @Test
    void joinRequirementsAreEnforced() {
        RecordingEngine engine = new RecordingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.query().innerJoin(null, "a = b"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().innerJoin("b", "  "));
        // 超过两段的表名写法无法判定别名边界，直接拒绝
        assertThrows(IllegalArgumentException.class, () -> engine.query().innerJoin("a b c", "a = b"));
    }

    @Test
    void whereRequirementsAreEnforced() {
        RecordingEngine engine = new RecordingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.query().where(" "));
        // and/or 必须跟在首个条件之后
        assertThrows(IllegalArgumentException.class, () -> engine.query().and("a = ?"));
        assertThrows(IllegalArgumentException.class, () -> engine.query().or("a = ?"));
    }

    @Test
    void havingAndOrderAndGroupRequirementsAreEnforced() {
        RecordingEngine engine = new RecordingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.query().having(" "));
        assertThrows(IllegalArgumentException.class, () -> engine.query().groupBy());
        assertThrows(IllegalArgumentException.class, () -> engine.query().orderByDesc());
    }

    @Test
    void limitAndOffsetBoundsAreEnforced() {
        RecordingEngine engine = new RecordingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.query().limit(0));
        assertThrows(IllegalArgumentException.class, () -> engine.query().limit(-1));
        assertThrows(IllegalArgumentException.class, () -> engine.query().offset(-1));
        // 边界内合法：offset(0) 不渲染 OFFSET
        assertEquals("SELECT * FROM t LIMIT 1", engine.query().table("t").limit(1).offset(0).sql());
    }

    @Test
    void selectFuncRejectsIllegalFunctionName() {
        RecordingEngine engine = new RecordingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.query().selectFunc("SUM(x)", "a", null));
        assertThrows(IllegalArgumentException.class, () -> engine.query().selectFunc(" ", "a", null));
    }

    // ==================== 终端执行 ====================

    @Test
    void listDelegatesRenderedSqlAndParamsToEngine() {
        RecordingEngine engine = new RecordingEngine();
        engine.rows = List.of(Map.of("id", 1), Map.of("id", 2));
        List<Map<String, Object>> rows = engine.query()
                .select("id").table("t_user").where("id > ?", 1).list();
        assertEquals(2, rows.size());
        assertEquals("SELECT id FROM t_user WHERE id > ?", engine.lastSql);
        assertArrayEquals(new Object[]{1}, engine.lastParams);
    }

    @Test
    void listWithRowTypeUsesTypedChannel() {
        RecordingEngine engine = new RecordingEngine();
        engine.typedRows = List.of("a", "b");
        List<String> rows = engine.query().table("t_user").list(String.class);
        assertEquals(List.of("a", "b"), rows);
        assertEquals(String.class, engine.lastRowType);
        assertEquals("SELECT * FROM t_user", engine.lastSql);
    }

    @Test
    void listWithNullRowTypeIsRejected() {
        RecordingEngine engine = new RecordingEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.query().table("t").list(null));
    }

    @Test
    void mapReturnsFirstRowOrNull() {
        RecordingEngine engine = new RecordingEngine();
        assertNull(engine.query().table("t").map());
        engine.rows = List.of(Map.of("id", 1), Map.of("id", 2));
        assertEquals(Map.of("id", 1), engine.query().table("t").map());
    }

    @Test
    void mapWithRowTypeReturnsFirstOrNull() {
        RecordingEngine engine = new RecordingEngine();
        assertNull(engine.query().table("t").map(String.class));
        engine.typedRows = List.of("only");
        assertEquals("only", engine.query().table("t").map(String.class));
        assertThrows(IllegalArgumentException.class, () -> engine.query().table("t").map(null));
    }

    @Test
    void executeReturnsHitCount() {
        RecordingEngine engine = new RecordingEngine();
        engine.rows = List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3));
        assertEquals(3, engine.query().table("t").execute());
    }

    @Test
    void executeOnEmptyResultIsZero() {
        assertEquals(0, new SqlQueryBuilder(new RecordingEngine()).table("t").execute());
    }

    @Test
    void engineReturningNullIsTolerated() {
        RecordingEngine engine = new RecordingEngine();
        engine.nullRows = true;
        assertNull(engine.query().table("t").list());
        assertNull(engine.query().table("t").map());
        assertEquals(0, engine.query().table("t").execute());
    }

    @Test
    void asyncTerminalsReturnMono() {
        RecordingEngine engine = new RecordingEngine();
        engine.rows = List.of(Map.of("id", 1));
        Mono<List<Map<String, Object>>> listMono = engine.query().table("t").listAsync();
        assertInstanceOf(Mono.class, listMono);
        assertEquals(1, listMono.block().size());

        Mono<Integer> countMono = engine.query().table("t").executeAsync();
        assertInstanceOf(Mono.class, countMono);
        assertEquals(1, countMono.block());
    }

    // ==================== 入口与无状态 ====================

    @Test
    void engineQueryReturnsBuilderBoundToEngine() {
        RecordingEngine engine = new RecordingEngine();
        SqlQueryBuilder builder = engine.query();
        assertNotNull(builder);
        assertEquals("SELECT * FROM t", builder.table("t").sql());
        // 同一引擎可反复取构建器，互不影响
        assertEquals("SELECT * FROM t", engine.query().table("t").sql());
    }

    @Test
    void builderIsReusableAndStatelessTerminal() {
        RecordingEngine engine = new RecordingEngine();
        engine.rows = List.of(Map.of("id", 1));
        SqlQueryBuilder builder = engine.query().table("t").where("id > ?", 0);
        assertEquals("SELECT * FROM t WHERE id > ?", builder.sql());
        builder.list();
        builder.map();
        builder.execute();
        // 终端调用不改变构建状态
        assertEquals("SELECT * FROM t WHERE id > ?", builder.sql());
        assertArrayEquals(new Object[]{0}, builder.boundParams());
    }

    @Test
    void ofIsEquivalentToConstructor() {
        RecordingEngine engine = new RecordingEngine();
        assertEquals(engine.query().table("t").sql(), SqlQueryBuilder.of(engine).table("t").sql());
    }

    @Test
    void supportsSqlFalseIsNotPrechecked() {
        // 构建器只拼接；是否支持原生语句由终端执行时引擎抛 UnsupportedOperationException 决定
        RecordingEngine engine = new RecordingEngine();
        engine.throwOnQuery = true;
        SqlQueryBuilder builder = engine.query().table("t");
        assertFalse(engine.supportsSql());
        assertEquals("SELECT * FROM t", builder.sql());
        assertThrows(UnsupportedOperationException.class, builder::list);
    }

    // ==================== 测试替身 ====================

    /**
     * 记录下发给引擎的语句与参数的测试替身。
     *
     * <p>只覆写 {@code querySql} 两个通道，其余 {@link Engine} 抽象方法一律抛出
     * {@link UnsupportedOperationException}：本测试只走 SQL 文本通道。</p>
     */
    private static final class RecordingEngine implements Engine {

        List<Map<String, Object>> rows = List.of();

        List<Object> typedRows = List.of();

        boolean nullRows;

        boolean throwOnQuery;

        String lastSql;

        Object[] lastParams;

        Class<?> lastRowType;

        @Override
        public List<Map<String, Object>> querySql(String ql, Object... params) {
            record(ql, params);
            if (throwOnQuery) {
                throw new UnsupportedOperationException("模拟不支持原生语句的引擎");
            }
            return nullRows ? null : rows;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> querySql(String ql, Class<T> rowType, Object... params) {
            record(ql, params);
            lastRowType = rowType;
            return (List<T>) typedRows;
        }

        @Override
        public boolean supportsSql() {
            return !throwOnQuery;
        }

        private void record(String ql, Object[] params) {
            lastSql = ql;
            lastParams = params;
        }

        @Override
        public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
            throw unsupported();
        }

        @Override
        public <T> Engine store(String name, List<T> data) {
            throw unsupported();
        }

        @Override
        public Engine setDefaultDataSourceName(String name) {
            throw unsupported();
        }

        @Override
        public SqlExecutor getExecutor(String dataSourceName) {
            throw unsupported();
        }

        @Override
        public SqlExecutor getExecutor() {
            throw unsupported();
        }

        @Override
        public <T> EngineDataSource<T> getDataSource(String name) {
            throw unsupported();
        }

        @Override
        public <T> EngineDataSource<T> getDataSource() {
            throw unsupported();
        }

        @Override
        public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
            throw unsupported();
        }

        @Override
        public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
            throw unsupported();
        }

        @Override
        public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
            throw unsupported();
        }

        @Override
        public Dialect getDialect(String dataSourceName) {
            throw unsupported();
        }

        @Override
        public void close() {
            // 测试替身无需释放资源
        }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("测试替身不实现该能力");
        }
    }
}
