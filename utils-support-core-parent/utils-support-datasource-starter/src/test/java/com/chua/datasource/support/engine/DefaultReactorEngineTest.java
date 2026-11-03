package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.datasource.support.annotation.TableName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultReactorEngine} 行为约定。
 *
 * <p>它把同步 {@link com.chua.common.support.lang.datasource.engine.Engine} 包成响应式门面，
 * 因此要验证两件事：</p>
 * <ul>
 *   <li><b>委托正确</b> — Lambda 包装器、原生 SQL、执行器都直接作用在同一个底层引擎上，
 *       不能出现「响应式侧写入、同步侧读不到」的割裂</li>
 *   <li><b>能力缺失要给错误信号</b> — 底层引擎没有 {@link SqlExecutor} 时，
 *       返回 {@link reactor.core.publisher.Flux#error} 而不是 {@link NullPointerException}</li>
 * </ul>
 */
class DefaultReactorEngineTest {

    private InMemoryEngine sync;
    private DefaultReactorEngine reactor;

    @BeforeEach
    void setUp() {
        sync = new InMemoryEngine();
        sync.store(AbstractEngine.resolveTableName(RUser.class), List.of(
                new RUser(1, "张三", 18, "zhangsan@example.com"),
                new RUser(2, "李四", 25, "lisi@example.com"),
                new RUser(3, "王五", null, "wangwu@example.com"),
                new RUser(4, "赵六", 30, "zhaoliu@example.com"),
                new RUser(5, "李七", 35, null)));
        reactor = new DefaultReactorEngine(sync);
    }

    @Test
    @DisplayName("构造必须传入同步引擎")
    void requireDelegate() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultReactorEngine(null));
        assertSame(sync, reactor.getDelegate());
    }

    @Test
    @DisplayName("Lambda 查询走同一份内存数据")
    void query() {
        assertEquals(List.of(1, 2, 3, 4, 5),
                ids(reactor.query(RUser.class).list().collectList().block()));

        RUser one = reactor.query(RUser.class).eq(RUser::getName, "李四").one().block();
        assertNotNull(one);
        assertEquals(2, one.getId());

        assertNull(reactor.query(RUser.class).eq(RUser::getId, 99).one().block());
    }

    @Test
    @DisplayName("条件、排序、limit、分页都能下推")
    void pushdown() {
        assertEquals(List.of(5, 4),
                ids(reactor.query(RUser.class).gt(RUser::getAge, 28)
                        .orderByDesc(RUser::getAge).list().collectList().block()));

        assertEquals(List.of(1, 2),
                ids(reactor.query(RUser.class).orderByAsc(RUser::getId)
                        .limit(2).list().collectList().block()));

        Page<RUser> second = reactor.query(RUser.class)
                .orderByAsc(RUser::getId).page(2, 2).block();
        assertNotNull(second);
        assertEquals(5, second.getTotal());
        assertEquals(List.of(3, 4), ids(second.getRecords()));
    }

    @Test
    @DisplayName("count 与底层同步引擎一致")
    void count() {
        assertEquals(5, reactor.query(RUser.class).count().block());
        assertEquals(2, reactor.query(RUser.class).ge(RUser::getAge, 30).count().block());
    }

    @Test
    @DisplayName("响应式侧写入，同步侧立即可见（同一底层引擎）")
    void updateAndDelete() {
        Integer updated = reactor.update(RUser.class)
                .set(RUser::getAge, 45)
                .eq(RUser::getId, 4)
                .update().block();
        assertEquals(1, updated);
        assertEquals(45, sync.query(RUser.class).eq(RUser::getId, 4).one().getAge());

        Integer removed = reactor.delete(RUser.class).eq(RUser::getId, 2).remove().block();
        assertEquals(1, removed);
        assertNull(sync.query(RUser.class).eq(RUser::getId, 2).one());
    }

    @Test
    @DisplayName("原生 SQL 查询在底层引擎无执行器时给出 UnsupportedOperationException")
    void rawSqlWithoutExecutor() {
        assertNull(sync.getExecutor());

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> reactor.query("SELECT * FROM user").collectList().block());
        assertTrue(e.getMessage().contains("不支持 SQL 查询"));

        UnsupportedOperationException e2 = assertThrows(UnsupportedOperationException.class,
                () -> reactor.query("SELECT * FROM user", RUser.class).collectList().block());
        assertTrue(e2.getMessage().contains("不支持 SQL 查询"));

        UnsupportedOperationException e3 = assertThrows(UnsupportedOperationException.class,
                () -> reactor.execute("DELETE FROM user").block());
        assertTrue(e3.getMessage().contains("不支持数据操作"));

        UnsupportedOperationException e4 = assertThrows(UnsupportedOperationException.class,
                () -> reactor.batch("DELETE FROM user", List.of()).collectList().block());
        assertTrue(e4.getMessage().contains("不支持批量操作"));
    }

    @Test
    @DisplayName("原生 SQL 路径在有执行器时正常工作")
    void rawSqlWithExecutor() {
        ScriptedEngine scripted = new ScriptedEngine();
        DefaultReactorEngine e = new DefaultReactorEngine(scripted);

        List<Map<String, Object>> rows = e.query("SELECT 1").collectList().block();
        assertNotNull(rows);
        assertEquals(1, rows.size());
        assertEquals("1", rows.getFirst().get("k"));

        List<RUser> typed = e.query("SELECT 1", RUser.class).collectList().block();
        assertNotNull(typed);
        assertEquals(1, typed.size());

        assertEquals(3, e.execute("UPDATE x").block());

        List<Integer> batch = e.batch("UPDATE x", List.of(new Object[]{1}, new Object[]{2}))
                .collectList().block();
        assertNotNull(batch);
        assertEquals(List.of(1, 2), batch);
    }

    @Test
    @DisplayName("批量结果数组被逐个发出，而不是折叠成一个值")
    void batchEmitsPerRow() {
        ScriptedEngine scripted = new ScriptedEngine();
        DefaultReactorEngine e = new DefaultReactorEngine(scripted);

        List<Integer> result = e.batch("UPDATE x",
                List.of(new Object[]{1}, new Object[]{2}, new Object[]{3}))
                .collectList().block();
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(List.of(1, 2, 3), result);
    }

    private static List<Integer> ids(List<RUser> list) {
        assertNotNull(list);
        return list.stream().map(RUser::getId).toList();
    }

    /**
     * 只实现执行器的引擎，用于验证响应式门面的 SQL 转发。
     */
    static class ScriptedEngine extends InMemoryEngine {
        private final SqlExecutor executor = new SqlExecutor() {
            @Override
            public List<Map<String, Object>> query(String sql, Object... params) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("k", "1");
                return List.of(row);
            }

            @Override
            public <T> List<T> query(String sql, Class<T> rowType, Object... params) {
                return List.of((T) new RUser(9, "脚本", 1, null));
            }

            @Override
            public List<Map<String, Object>> queryPage(
                    String sql, com.chua.common.support.lang.datasource.dialect.Pagination pagination,
                    Object... params) {
                return query(sql, params);
            }

            @Override
            public int execute(String sql, Object... params) {
                return 3;
            }

            @Override
            public int[] batch(String sql, List<Object[]> batchParams) {
                int[] out = new int[batchParams.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = i + 1;
                }
                return out;
            }
        };

        @Override
        public SqlExecutor getExecutor() {
            return executor;
        }

        @Override
        public SqlExecutor getExecutor(String dataSourceName) {
            return executor;
        }
    }

    /**
     * 测试实体（表名 user）。
     */
    @TableName("user")
    public static class RUser {
        private Integer id;
        private String name;
        private Integer age;
        private String email;

        public RUser() {
        }

        RUser(Integer id, String name, Integer age, String email) {
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
    }
}
