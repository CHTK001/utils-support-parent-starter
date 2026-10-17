import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;

import java.util.List;
import java.util.Map;

/**
 * 引擎验收运行器 —— 阶段六：非 SPI 注册的 Engine 实现者。
 *
 * <p>阶段一覆盖的是 {@code META-INF/extensions/...engine.Engine} 登记文件里的
 * 24 个键（已逐文件核对，与 harness 的 KEYS 完全一致）。但 {@code Engine} 体系里
 * 还有两个**公开但未注册**的实现者，它们不在 SPI 表里，因此阶段一到五都碰不到：</p>
 * <ul>
 *   <li>{@code HttpDatalakeQueryEngine}（utils-support-datalake-query-starter）——
 *       通过 HTTP 调用数据湖查询服务，只读。本机无该服务，故只验**只读契约面**：
 *       写入口一律 {@link UnsupportedOperationException}、方言/数据源为 null、
 *       关闭幂等；所有断言均**不触网**（baseUrl 指向保留不可达端口即可，
 *       构造与这些方法都不发起请求）。</li>
 *   <li>{@code ReactorFlywayBridge}（utils-support-datasource-starter）——
 *       把响应式 {@code JdbcReactorEngine} 桥接成同步 {@code Engine}，
 *       供 Flyway 复用。这里用一个**真实 SQLite 文件**驱动它做真实读写，
 *       同时验其"只暴露 execute/query 两通道"的契约（其余方法一律 UOE）。</li>
 * </ul>
 *
 * <p><b>仍不可直接触达者（如实声明，未纳入断言）</b>：{@code JdbcReactorEngine} 的
 * 两个内部适配器 {@code R2dbcEngineAdapter} / {@code UnifiedEngineAdapter} 是
 * private 内部类，只能由其宿主实例在内部创建，无法从外部引用；抽象基类
 * {@code AbstractEngine} / {@code JdbcEngine} 不可实例化，其行为已由全部 24 个子类
 * 间接覆盖。</p>
 */
public final class EngineRemaining {

    private EngineRemaining() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段六：非注册 Engine 实现者");
        EngineFunctional.emit("=".repeat(78));

        runQuietly("datalake-query", EngineRemaining::datalakeQuerySuite);
        runQuietly("reactor-flyway", EngineRemaining::reactorFlywaySuite);

        EngineFunctional.finishAndExit("EngineRemaining-report.txt");
    }

    /**
     * HttpDatalakeQueryEngine：本机无数据湖查询服务，只验只读契约面，不触网。
     */
    static void datalakeQuerySuite() {
        EngineFunctional.emit("--- engine:datalake-query (HttpDatalakeQueryEngine, 只读契约面，不触网) ---");
        String p = "datalake.";

        // 127.0.0.1:1 是保留的不可达端口：构造只装配 HTTP 客户端，不建立连接。
        Engine e;
        try {
            e = new com.chua.datalake.support.engine.HttpDatalakeQueryEngine("http://127.0.0.1:1/datalake");
        } catch (Throwable t) {
            EngineFunctional.recordFail(p + "构造异常: " + EngineFunctional.chain(t));
            return;
        }

        EngineFunctional.check(p + "getDefaultDataSourceName()==datalake", () -> {
            String n = e.getDefaultDataSourceName();
            if (!"datalake".equals(n)) {
                throw new EngineFunctional.HarnessFailure("期望 datalake，实际 " + n);
            }
            return n;
        });

        EngineFunctional.check(p + "supportsMeta()==false", () -> {
            if (e.supportsMeta()) {
                throw new EngineFunctional.HarnessFailure("HTTP 查询端点不提供元数据，契约要求 false");
            }
            return "false";
        });

        EngineFunctional.check(p + "meta() → UOE", () -> expectUoe(() -> e.meta()));

        EngineFunctional.check(p + "getDialect(name)==null（非 SQL 引擎）", () -> {
            if (e.getDialect("datalake") != null) {
                throw new EngineFunctional.HarnessFailure("无方言却返回非 null");
            }
            return "null";
        });

        EngineFunctional.check(p + "getDataSource(name)==null 且 hasDataSource==false", () -> {
            if (e.getDataSource("datalake") != null) {
                throw new EngineFunctional.HarnessFailure("未注册数据源却返回非 null");
            }
            if (e.hasDataSource("datalake")) {
                throw new EngineFunctional.HarnessFailure("hasDataSource 应随 getDataSource==null 返回 false");
            }
            return "null/false";
        });

        EngineFunctional.check(p + "addDataSource → UOE（只读，拒绝本地注册）",
                () -> expectUoe(() -> e.addDataSource("x", null)));
        EngineFunctional.check(p + "store → UOE（只读，拒绝写入）",
                () -> expectUoe(() -> e.store("x", List.of())));
        EngineFunctional.check(p + "query(Class) → UOE（无 Lambda 通道）",
                () -> expectUoe(() -> e.query(EngineFunctional.AccUser.class)));
        EngineFunctional.check(p + "update(Class) → UOE",
                () -> expectUoe(() -> e.update(EngineFunctional.AccUser.class)));
        EngineFunctional.check(p + "delete(Class) → UOE",
                () -> expectUoe(() -> e.delete(EngineFunctional.AccUser.class)));

        EngineFunctional.check(p + "getExecutor() 非 null", () -> {
            SqlExecutor ex = e.getExecutor();
            if (ex == null) {
                throw new EngineFunctional.HarnessFailure("getExecutor() 不得为 null");
            }
            return ex.getClass().getSimpleName();
        });

        EngineFunctional.check(p + "supportsSql()==true（有执行器）", () -> {
            if (!e.supportsSql()) {
                throw new EngineFunctional.HarnessFailure("getExecutor() 非 null，supportsSql() 应为 true");
            }
            return "true";
        });

        EngineFunctional.check(p + "getExecutor().batch → UOE（端点无参数通道）", () -> {
            SqlExecutor ex = e.getExecutor();
            return expectUoe(() -> ex.batch("select 1", List.<Object[]>of(new Object[]{1})));
        });

        EngineFunctional.check(p + "dataSourceNames() → UOE（不支持多数据源注册）",
                () -> expectUoe(e::dataSourceNames));

        EngineFunctional.check(p + "setDefaultDataSourceName 返回 this", () -> {
            Engine r = e.setDefaultDataSourceName("datalake");
            if (r != e) {
                throw new EngineFunctional.HarnessFailure("契约要求返回 this");
            }
            return "this";
        });

        EngineFunctional.check(p + "close 幂等且 isClosed() 置位", () -> {
            if (e.isClosed()) {
                throw new EngineFunctional.HarnessFailure("close 前 isClosed() 应为 false");
            }
            e.close();
            if (!e.isClosed()) {
                throw new EngineFunctional.HarnessFailure("close 后 isClosed() 应为 true");
            }
            e.close();
            if (!e.isClosed()) {
                throw new EngineFunctional.HarnessFailure("重复 close 后 isClosed() 仍应为 true");
            }
            return "idempotent";
        });
    }

    /**
     * ReactorFlywayBridge：以真实 SQLite 文件驱动响应式引擎，做真实读写；
     * 同时验桥接"只暴露 execute/query"的契约。
     */
    static void reactorFlywaySuite() {
        EngineFunctional.emit("--- engine:reactor-flyway (ReactorFlywayBridge over SqliteReactorEngine) ---");
        String p = "bridge.";

        Engine bridge;
        try {
            var sqlite = new com.chua.sqlite.support.engine.SqliteReactorEngine();
            // 走 JDBC 路径（SQLite 无 r2dbc 驱动，JdbcReactorEngine 会自动降级）
            sqlite.addDataSource("default", "target/acc-remaining.sqlite");
            bridge = new com.chua.datasource.support.flyway.ReactorFlywayBridge(sqlite);
        } catch (Throwable t) {
            EngineFunctional.recordFail(p + "装配异常: " + EngineFunctional.chain(t));
            return;
        }

        // ---------------- 契约面：桥接只暴露 execute/query ----------------
        EngineFunctional.check(p + "supportsMeta()==false", () -> {
            if (bridge.supportsMeta()) {
                throw new EngineFunctional.HarnessFailure("桥接不提供元数据，契约要求 false");
            }
            return "false";
        });

        EngineFunctional.check(p + "isClosed()==false（close 不受支持故永不置位）", () -> {
            if (bridge.isClosed()) {
                throw new EngineFunctional.HarnessFailure("未 close 却报告已关闭");
            }
            return "false";
        });

        EngineFunctional.check(p + "addDataSource → UOE",
                () -> expectUoe(() -> bridge.addDataSource("x", null)));
        EngineFunctional.check(p + "store → UOE",
                () -> expectUoe(() -> bridge.store("x", List.of())));
        EngineFunctional.check(p + "setDefaultDataSourceName → UOE",
                () -> expectUoe(() -> bridge.setDefaultDataSourceName("x")));
        EngineFunctional.check(p + "getExecutor(name) → UOE",
                () -> expectUoe(() -> bridge.getExecutor("x")));
        EngineFunctional.check(p + "getDataSource(name) → UOE",
                () -> expectUoe(() -> bridge.getDataSource("x")));
        EngineFunctional.check(p + "getDataSource() → UOE",
                () -> expectUoe(bridge::getDataSource));
        EngineFunctional.check(p + "query(Class) → UOE",
                () -> expectUoe(() -> bridge.query(EngineFunctional.AccUser.class)));
        EngineFunctional.check(p + "update(Class) → UOE",
                () -> expectUoe(() -> bridge.update(EngineFunctional.AccUser.class)));
        EngineFunctional.check(p + "delete(Class) → UOE",
                () -> expectUoe(() -> bridge.delete(EngineFunctional.AccUser.class)));
        EngineFunctional.check(p + "getDialect(name) → UOE",
                () -> expectUoe(() -> bridge.getDialect("default")));
        EngineFunctional.check(p + "meta() → UOE",
                () -> expectUoe(() -> bridge.meta()));
        EngineFunctional.check(p + "close() → UOE（资源归委托方所有）",
                () -> expectUoe(bridge::close));

        EngineFunctional.check(p + "getExecutor() 非 null", () -> {
            SqlExecutor ex = bridge.getExecutor();
            if (ex == null) {
                throw new EngineFunctional.HarnessFailure("getExecutor() 不得为 null");
            }
            return ex.getClass().getSimpleName();
        });

        EngineFunctional.check(p + "supportsSql()==true（有执行器）", () -> {
            if (!bridge.supportsSql()) {
                throw new EngineFunctional.HarnessFailure("getExecutor() 非 null，supportsSql() 应为 true");
            }
            return "true";
        });

        EngineFunctional.check(p + "getExecutor().queryPage → UOE（桥接明示不支持分页）", () -> {
            SqlExecutor ex = bridge.getExecutor();
            var pagination = new com.chua.common.support.lang.datasource.dialect.Pagination()
                    .setPageNum(1).setPageSize(10);
            return expectUoe(() -> ex.queryPage("select 1", pagination));
        });

        EngineFunctional.check(p + "getExecutor().batch → UOE（桥接明示不支持批量）", () -> {
            SqlExecutor ex = bridge.getExecutor();
            return expectUoe(() -> ex.batch("select 1", List.<Object[]>of(new Object[]{1})));
        });

        // ---------------- 功能面：真实 SQLite 读写 ----------------
        String table = "t_acc_bridge";

        EngineFunctional.check(p + "execute(DROP/CREATE TABLE) 成功", () -> {
            bridge.execute("DROP TABLE IF EXISTS " + table);
            int r = bridge.execute("CREATE TABLE " + table
                    + "(id INTEGER PRIMARY KEY, name TEXT, age INTEGER)");
            if (r < 0) {
                throw new EngineFunctional.HarnessFailure("受影响行数不得为负，实际 " + r);
            }
            return "affected=" + r;
        });

        EngineFunctional.check(p + "execute(INSERT×3) 各返回 1", () -> {
            int a = bridge.execute("INSERT INTO " + table + "(id, name, age) VALUES(?, ?, ?)", 1, "alice", 20);
            int b = bridge.execute("INSERT INTO " + table + "(id, name, age) VALUES(?, ?, ?)", 2, "bob", 30);
            int c = bridge.execute("INSERT INTO " + table + "(id, name, age) VALUES(?, ?, ?)", 3, "carol", 40);
            if (a != 1 || b != 1 || c != 1) {
                throw new EngineFunctional.HarnessFailure("INSERT 期望各 1，实际 " + a + "/" + b + "/" + c);
            }
            return "1/1/1";
        });

        EngineFunctional.check(p + "querySql(SELECT *) 取回 3 行", () -> {
            List<Map<String, Object>> rows = bridge.querySql("SELECT id, name, age FROM " + table + " ORDER BY id");
            if (rows == null || rows.size() != 3) {
                throw new EngineFunctional.HarnessFailure("期望 3 行，实际 " + (rows == null ? "null" : rows.size()));
            }
            return "rows=" + rows.size();
        });

        EngineFunctional.check(p + "querySql(WHERE age>?) 参数绑定生效", () -> {
            List<Map<String, Object>> rows = bridge.querySql(
                    "SELECT name FROM " + table + " WHERE age > ? ORDER BY id", 25);
            if (rows == null || rows.size() != 2) {
                throw new EngineFunctional.HarnessFailure("期望 2 行，实际 " + (rows == null ? "null" : rows.size()));
            }
            return "rows=" + rows.size();
        });

        EngineFunctional.check(p + "querySql(sql, Class) 类型化映射", () -> {
            List<EngineFunctional.AccUser> list = bridge.querySql(
                    "SELECT id, name, age FROM " + table + " ORDER BY id", EngineFunctional.AccUser.class);
            if (list == null || list.size() != 3) {
                throw new EngineFunctional.HarnessFailure("期望 3 个实体，实际 " + (list == null ? "null" : list.size()));
            }
            EngineFunctional.AccUser first = list.getFirst();
            if (!Long.valueOf(1L).equals(first.getId()) || !"alice".equals(first.getName())
                    || !Integer.valueOf(20).equals(first.getAge())) {
                throw new EngineFunctional.HarnessFailure("首行映射错误: " + first);
            }
            return first.toString();
        });

        EngineFunctional.check(p + "queryOne(sql, Class) 命中单行", () -> {
            EngineFunctional.AccUser u = bridge.queryOne(
                    "SELECT id, name, age FROM " + table + " WHERE id = ?", EngineFunctional.AccUser.class, 2);
            if (u == null || !"bob".equals(u.getName())) {
                throw new EngineFunctional.HarnessFailure("期望 bob，实际 " + u);
            }
            return u.getName();
        });

        EngineFunctional.check(p + "queryOne 无命中返回 null", () -> {
            EngineFunctional.AccUser u = bridge.queryOne(
                    "SELECT id, name, age FROM " + table + " WHERE id = ?", EngineFunctional.AccUser.class, 999);
            if (u != null) {
                throw new EngineFunctional.HarnessFailure("期望 null，实际 " + u);
            }
            return "null";
        });

        EngineFunctional.check(p + "count(COUNT(*)) 标量计数", () -> {
            long n = bridge.count("SELECT COUNT(*) FROM " + table);
            if (n != 3L) {
                throw new EngineFunctional.HarnessFailure("期望 3，实际 " + n);
            }
            return "3";
        });

        EngineFunctional.check(p + "exists() 真/假", () -> {
            boolean yes = bridge.exists("SELECT 1 FROM " + table + " WHERE name = ?", "alice");
            boolean no = bridge.exists("SELECT 1 FROM " + table + " WHERE name = ?", "nobody");
            if (!yes || no) {
                throw new EngineFunctional.HarnessFailure("期望 true/false，实际 " + yes + "/" + no);
            }
            return "true/false";
        });

        EngineFunctional.check(p + "queryValue 首行首列", () -> {
            Object v = bridge.queryValue("SELECT name FROM " + table + " WHERE id = ?", 3);
            if (!"carol".equals(v)) {
                throw new EngineFunctional.HarnessFailure("期望 carol，实际 " + v);
            }
            return String.valueOf(v);
        });

        EngineFunctional.check(p + "execute(DELETE) 返回受影响行数", () -> {
            int r = bridge.execute("DELETE FROM " + table + " WHERE id = ?", 3);
            if (r != 1) {
                throw new EngineFunctional.HarnessFailure("期望 1，实际 " + r);
            }
            long left = bridge.count("SELECT COUNT(*) FROM " + table);
            if (left != 2L) {
                throw new EngineFunctional.HarnessFailure("删除后期望剩 2 行，实际 " + left);
            }
            return "deleted=1,left=" + left;
        });
    }

    /** 期望抛出 UnsupportedOperationException。 */
    private static String expectUoe(Runnable action) {
        try {
            action.run();
            throw new EngineFunctional.HarnessFailure("未抛 UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            return "UnsupportedOperationException";
        }
    }

    /** 隔离执行一个套件：构造或套件本身抛异常时记为一条失败，不影响其余套件。 */
    private static void runQuietly(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            EngineFunctional.recordFail(name + " 套件异常退出: " + EngineFunctional.chain(t));
        }
    }
}
