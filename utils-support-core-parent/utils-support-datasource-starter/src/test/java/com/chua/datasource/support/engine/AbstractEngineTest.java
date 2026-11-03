// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.network.tunnel.Tunnel;
import com.chua.common.support.network.tunnel.TunnelInfo;
import com.chua.datasource.support.annotation.TableName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AbstractEngine} 模板行为约定。
 *
 * <p>本类是所有 {@code Engine} 实现共享的骨架，承担生命周期、数据源注册、
 * 内存通道 CRUD、分页与表名解析。断言按「契约面」组织，不依赖任何具体子类：</p>
 * <ul>
 *   <li>关闭幂等，且关闭后所有写入口都抛 {@link IllegalStateException}</li>
 *   <li>{@code removeDataSource(null)} 是用法错误必须抛 IAE，摘除默认源后默认名置空</li>
 *   <li>内存通道 UPDATE/DELETE 的 SET 与 WHERE 参数按序切分，引号内逗号不拆分</li>
 *   <li>分页在非物理分页引擎上先全量排序再截取，且不改动调用方 wrapper 的 limit</li>
 * </ul>
 */
class AbstractEngineTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TestEngine();
        engine.store("user", List.of(
                new User(1, "张三", 18, "zhangsan@example.com"),
                new User(2, "李四", 25, "lisi@example.com"),
                new User(3, "王五", null, "wangwu@example.com"),
                new User(4, "赵六", 30, null)));
    }

    @Test
    @DisplayName("store 后可通过 query 取到全部行")
    void storeAndQuery() {
        assertEquals(4, engine.query(User.class).list().size());
        assertEquals(4, engine.query(User.class).count());
    }

    @Test
    @DisplayName("查询按 WHERE 条件过滤并支持排序")
    void queryWithWhereAndOrder() {
        List<User> list = engine.query(User.class)
                .gt(User::getAge, 20)
                .orderByDesc(User::getAge)
                .list();
        assertEquals(List.of(4, 2), ids(list));
    }

    @Test
    @DisplayName("limit / offset 只在内存后处理阶段截一次")
    void limitAndOffset() {
        assertEquals(List.of(2, 3), ids(engine.query(User.class)
                .orderByAsc(User::getId).limit(2).offset(1).list()));
        assertTrue(engine.query(User.class).limit(2).offset(99).list().isEmpty());
    }

    @Test
    @DisplayName("分页返回独立副本且 total 为命中总数")
    void page() {
        Page<User> page = engine.query(User.class).orderByAsc(User::getId).page(2, 2);
        assertEquals(2, page.getPageNum());
        assertEquals(2, page.getPageSize());
        assertEquals(4, page.getTotal());
        assertEquals(List.of(3, 4), ids(page.getRecords()));

        Page<User> beyond = engine.query(User.class).page(9, 2);
        assertEquals(4, beyond.getTotal());
        assertTrue(beyond.getRecords().isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> engine.query(User.class).page(1, 0));
    }

    @Test
    @DisplayName("物理分页后还原调用方 wrapper 的 limit（0 视为不限制）")
    void pageRestoresLimit() {
        engine.nativePaging = true;

        var wrapper = (com.chua.datasource.support.wrapper.EngineQueryWrapper<User>)
                engine.query(User.class);
        wrapper.limit(3);
        Page<User> page = engine.executePage(wrapper, User.class, 1, 2);
        assertEquals(2, page.getRecords().size());
        // 分页期间临时注入的 limit 必须还原，否则后续复用 wrapper 会静默少读
        assertEquals(3, wrapper.getLimit());

        var unlimited = (com.chua.datasource.support.wrapper.EngineQueryWrapper<User>)
                engine.query(User.class);
        engine.executePage(unlimited, User.class, 1, 2);
        assertEquals(0, unlimited.getLimit());
    }

    @Test
    @DisplayName("UPDATE 的字面量与占位符按 SET → WHERE 顺序消费")
    void updateWithLiteralsAndParams() {
        int affected = engine.executeUpdate(new UpdateSql<>(User.class,
                "name = '刘, 八', age = ?", "id = ?", List.of(40, 2)));
        assertEquals(1, affected);

        User one = engine.query(User.class).eq(User::getId, 2).one();
        assertEquals("刘, 八", one.getName());
        assertEquals(40, one.getAge());
        assertEquals(18, engine.query(User.class).eq(User::getId, 1).one().getAge());
    }

    @Test
    @DisplayName("UPDATE 无法求值的 SET 不算命中（不谎报影响行数）")
    void updateScopeAndUnknownLiteral() {
        // now() 无法在内存求值：一条列都没写进去，必须报 0 而不是 4
        assertEquals(0, engine.executeUpdate(new UpdateSql<>(User.class,
                "age = now()", null, null)));
        assertEquals(18, engine.query(User.class).eq(User::getId, 1).one().getAge());

        // 部分可求值时只写可求值的列，影响行数按实际写行的行数
        assertEquals(4, engine.executeUpdate(new UpdateSql<>(User.class,
                "email = 'x@y.z', age = now()", null, null)));
        assertTrue(engine.query(User.class).list().stream()
                .allMatch(u -> "x@y.z".equals(u.getEmail())));
    }

    @Test
    @DisplayName("UPDATE 空 SET 或空表返回 0，SET 占位符超参数时抛异常")
    void updateGuards() {
        assertEquals(0, engine.executeUpdate(new UpdateSql<>(User.class, null, "id = ?",
                List.of(1))));
        assertEquals(0, engine.executeUpdate(new UpdateSql<>(User.class, "  ", "id = ?",
                List.of(1))));

        TestEngine empty = new TestEngine();
        assertEquals(0, empty.executeUpdate(new UpdateSql<>(User.class, "age = 1", null, null)));

        assertThrows(IllegalStateException.class, () -> engine.executeUpdate(
                new UpdateSql<>(User.class, "age = ?, name = ?", "id = ?", List.of(1))));
    }

    @Test
    @DisplayName("DELETE 只移除命中行，无 WHERE 时不删除")
    void delete() {
        assertEquals(0, engine.executeDelete(new DeleteSql<>(User.class, null, null)));
        assertEquals(4, engine.query(User.class).count());

        assertEquals(2, engine.executeDelete(new DeleteSql<>(User.class,
                "age >= ?", List.of(25))));
        assertEquals(List.of(1, 3), ids(engine.query(User.class).list()));

        TestEngine empty = new TestEngine();
        assertEquals(0, empty.executeDelete(new DeleteSql<>(User.class, "id = ?", List.of(1))));
    }

    @Test
    @DisplayName("DELETE 替换存储引用而不是就地修改，保证索引视图同步")
    void deleteReplacesStoreReference() {
        List<?> before = engine.dataStores.get("user");
        engine.executeDelete(new DeleteSql<>(User.class, "id = ?", List.of(1)));
        List<?> after = engine.dataStores.get("user");
        assertEquals(3, after.size());
        assertFalse(before == after);
    }

    @Test
    @DisplayName("insert / insertBatch 走内存通道追加实体")
    void insert() {
        assertEquals(1, engine.insert(User.class, new User(5, "李七", 35, "liqi@example.com")));
        assertEquals(5, engine.query(User.class).count());

        assertEquals(2, engine.insertBatch(User.class, List.of(
                new User(6, "周八", 41, null),
                new User(7, "吴九", 42, null))));
        assertEquals(7, engine.query(User.class).count());

        assertEquals(0, engine.insertBatch(User.class, List.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.insert(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.insert(User.class, null));
        assertThrows(IllegalArgumentException.class, () -> engine.insertBatch(User.class,
                java.util.Arrays.asList(new User(8, "x", 1, null), null)));
    }

    @Test
    @DisplayName("实体表名缺失时回退 default 存储")
    void getDataFallsBackToDefault() {
        TestEngine fallback = new TestEngine();
        fallback.store("default", List.of(new User(1, "张三", 18, null)));
        // User 的表名是 user，未注册 → 回退 default
        assertEquals(1, fallback.query(User.class).count());
    }

    @Test
    @DisplayName("数据源注册、查询、摘除与默认名回落")
    void dataSourceLifecycle() {
        // setUp 里 store("user") 已把默认数据源名设为 "user"；
        // 先清空默认名，才能验证「首次注册的数据源成为默认」这条契约
        engine.setDefaultDataSourceName(null);
        StubDataSource first = new StubDataSource("first");
        StubDataSource second = new StubDataSource("second");

        engine.addDataSource("first", first).addDataSource("second", second);
        assertEquals(java.util.Set.of("first", "second"), engine.dataSourceNames());
        assertEquals("first", engine.getDefaultDataSourceName());
        assertSame(first, engine.getDataSource("first"));
        assertSame(first, engine.getDataSource());

        engine.setDefaultDataSourceName("second");
        assertSame(second, engine.getDataSource());

        // null 名称是用法错误：静默无操作会掩盖拼写错误
        assertThrows(IllegalArgumentException.class, () -> engine.removeDataSource(null));
        assertNull(engine.getDataSource(null));
        assertNull(engine.getDialect(null));

        engine.removeDataSource("second");
        assertNull(engine.getDefaultDataSourceName());
        assertTrue(second.closed);
        // 再摘一次同一名称是幂等的，不抛异常
        engine.removeDataSource("second");
        assertEquals(java.util.Set.of("first"), engine.dataSourceNames());
    }

    @Test
    @DisplayName("摘除数据源时底层 close 异常不影响摘除结果")
    void removeDataSourceSwallowsCloseFailure() {
        StubDataSource failing = new StubDataSource("failing");
        failing.failOnClose = true;
        engine.addDataSource("failing", failing);

        engine.removeDataSource("failing");
        assertTrue(failing.closed);
        assertTrue(engine.dataSourceNames().isEmpty());
    }

    @Test
    @DisplayName("隧道开关：开启写入端口，关闭归零，缺失数据源返回 -1")
    void tunnel() {
        StubDataSource ds = new StubDataSource("ds");
        engine.addDataSource("ds", ds);

        assertEquals(1234, engine.openTunnel("ds", new StubTunnel()));
        assertEquals(1234, ds.tunnelPort());

        engine.setTunnel("ds", new StubTunnel(4321));
        assertEquals(4321, ds.tunnelPort());

        engine.closeTunnel("ds");
        assertEquals(0, ds.tunnelPort());

        assertEquals(-1, engine.openTunnel("missing", new StubTunnel()));
        engine.setTunnel("missing", new StubTunnel());
    }

    @Test
    @DisplayName("close 幂等：清空数据与数据源，关闭后写入口抛 IllegalStateException")
    void closeIsIdempotent() {
        StubDataSource ds = new StubDataSource("ds");
        engine.addDataSource("ds", ds);

        engine.close();
        assertTrue(engine.isClosed());
        assertTrue(ds.closed);
        assertTrue(engine.dataStores.isEmpty());
        assertTrue(engine.dataSourceNames().isEmpty());
        assertNull(engine.getDefaultDataSourceName());

        engine.close();
        assertTrue(engine.isClosed());

        assertThrows(IllegalStateException.class, () -> engine.store("t", List.of()));
        assertThrows(IllegalStateException.class,
                () -> engine.addDataSource("x", new StubDataSource("x")));
        assertThrows(IllegalStateException.class,
                () -> engine.insert(User.class, new User(9, "x", 1, null)));
    }

    @Test
    @DisplayName("表名解析：驼峰转下划线，@TableName 优先")
    void resolveTableName() {
        assertEquals("user", AbstractEngine.resolveTableName(User.class));
        assertEquals("order_item", AbstractEngine.resolveTableName(OrderItem.class));
        assertEquals("t_user_account", AbstractEngine.resolveTableName(Account.class));
        assertEquals("simple", AbstractEngine.resolveTableName(Simple.class));
    }

    @Test
    @DisplayName("拦截器与能力开关的默认值")
    void capabilityDefaults() {
        assertNotNull(engine.interceptors());
        // 默认 meta() 返回 DefaultMetaData，各方法均抛异常 → 报告不支持
        assertFalse(engine.supportsMeta());
        assertThrows(UnsupportedOperationException.class, engine::ddl);
        assertThrows(UnsupportedOperationException.class, engine::user);
        assertNull(engine.getExecutor());
        assertNull(engine.getExecutor("any"));
    }

    private static List<Integer> ids(List<User> list) {
        return list.stream().map(User::getId).toList();
    }

    /**
     * 最小可用引擎：只实现 {@link #executeNewQuery}，其余全部走基类默认实现。
     */
    static class TestEngine extends AbstractEngine {

        private boolean nativePaging;

        @Override
        protected <T> List<T> executeNewQuery(String where, Object[] args, Class<T> clazz,
                                              int limit, int offset) {
            List<T> data = getData(clazz);
            if (where == null || where.isBlank()) {
                return data;
            }
            List<Object> params = args == null ? List.of() : java.util.Arrays.asList(args);
            var predicate = new MemoryWhereParser().parse(where, params);
            return data.stream().filter(predicate).toList();
        }

        @Override
        protected boolean supportsNativePaging(Class<?> entityClass) {
            return nativePaging;
        }

        @Override
        protected <T> long executeCount(com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper<T> wrapper) {
            return executeNewQuery(wrapper.buildSql().whereClause(),
                    wrapper.buildSql().params().toArray(new Object[0]),
                    wrapper.getEntityClass(), 0, 0).size();
        }
    }

    /**
     * 数据源桩：记录关闭状态与隧道端口。
     */
    static class StubDataSource implements EngineDataSource<Object> {
        private final String name;
        private Dialect dialect;
        private int tunnelPort;
        private boolean closed;
        private boolean failOnClose;

        StubDataSource(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Object getSource() {
            return null;
        }

        @Override
        public EngineDataSource<Object> setSource(Object source) {
            return this;
        }

        @Override
        public Dialect getDialect() {
            return dialect;
        }

        @Override
        public EngineDataSource<Object> setDialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        @Override
        public String url() {
            return null;
        }

        @Override
        public String username() {
            return null;
        }

        @Override
        public String password() {
            return null;
        }

        @Override
        public int tunnelPort() {
            return tunnelPort;
        }

        @Override
        public EngineDataSource<Object> setTunnelPort(int tunnelPort) {
            this.tunnelPort = tunnelPort;
            return this;
        }

        @Override
        public void close() {
            this.closed = true;
            if (failOnClose) {
                throw new IllegalStateException("模拟关闭失败");
            }
        }
    }

    /**
     * 隧道桩：{@code open()} 返回固定端口。
     */
    static class StubTunnel implements Tunnel {
        private final int port;

        StubTunnel() {
            this(1234);
        }

        StubTunnel(int port) {
            this.port = port;
        }

        @Override
        public int open() {
            return port;
        }

        @Override
        public void close() {
        }

        @Override
        public TunnelInfo getInfo() {
            return null;
        }

        @Override
        public void onInfo(Consumer<TunnelInfo> callback) {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    /**
     * 测试实体。
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
    }

    /**
     * 驼峰类名 → 下划线表名。
     */
    static class OrderItem {
    }

    /**
     * 注解优先于驼峰转换。
     */
    @TableName("t_user_account")
    static class Account {
    }

    /**
     * 单词类名不插入下划线。
     */
    static class Simple {
    }
}
