import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.dialect.Pagination;
import com.chua.datasource.support.dialect.ClickHouseDialect;
import com.chua.datasource.support.dialect.Derby1014Dialect;
import com.chua.datasource.support.dialect.DerbyDialect;
import com.chua.datasource.support.dialect.MysqlDialect;
import com.chua.datasource.support.dialect.Oracle11gDialect;
import com.chua.datasource.support.dialect.Oracle12cDialect;
import com.chua.datasource.support.dialect.OracleDialect;
import com.chua.datasource.support.dialect.PostgresqlDialect;
import com.chua.datasource.support.dialect.SqlServer2008Dialect;
import com.chua.datasource.support.dialect.SqlServer2012Dialect;
import com.chua.datasource.support.dialect.SqlServerDialect;
import com.chua.clickhouse.support.engine.ClickHouseEngine;
import com.chua.oracle.support.engine.OracleEngine;
import com.chua.postgresql.support.engine.PostgresqlEngine;
import com.chua.sqlserver.support.engine.SqlServerEngine;
import com.chua.sqlserver.support.engine.SqlServerLegacyEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引擎验收运行器 —— 阶段二十一：方言分页 SQL（postgresql / clickhouse / oracle / sqlserver）。
 *
 * <p>本机没有 PostgreSQL / ClickHouse / Oracle / SQL Server 服务端，也没有任何容器
 * 运行时（无 docker / podman / nerdctl / wsl），因此<b>无法</b>做真实查询语义验收。
 * 能验证且值得验证的是：这四个引擎的方言对象如何渲染分页 SQL。</p>
 *
 * <p>该渲染点是真实执行路径：{@code JdbcEngine.wrapPagination} 在
 * {@code dialect.supportsLimit()} 为 false 时调用
 * {@code dialect.processSql(sql, pagination)}，结果直接下发数据库。若方言谎称
 * 支持 LIMIT，Oracle / SQL Server 会收到不存在的语法。</p>
 *
 * <p>断言口径：方言的公开约定 + {@code META-INF/dialect-env/*.env} 里已声明的
 * {@code pagination-sql} 模板。全部为纯函数调用，不需要任何服务端。</p>
 */
public final class EngineDialectSql {

    private EngineDialectSql() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段二十一：方言分页 SQL（Oracle / SQL Server / PostgreSQL / ClickHouse）");
        EngineFunctional.emit("=".repeat(78));

        supportsLimitSuite();
        limitStyleSuite();
        templateSuite();
        orderBySuite();
        engineWiringSuite();

        EngineFunctional.finishAndExit("EngineDialectSql-report.txt");
    }

    // ==================== 能力声明 ====================

    private static void supportsLimitSuite() {
        EngineFunctional.emit("--- dialect.supportsLimit ---");

        EngineFunctional.check("声明.LIMIT 方言：postgresql / clickhouse / mysql 报 true", () ->
                assertSupportsLimit(new PostgresqlDialect(), true,
                        new ClickHouseDialect(), true, new MysqlDialect(), true));

        EngineFunctional.check("声明.Oracle 三代方言都不得声称支持 LIMIT", () ->
                assertSupportsLimit(new OracleDialect(), false,
                        new Oracle11gDialect(), false, new Oracle12cDialect(), false));

        EngineFunctional.check("声明.SQL Server 三代方言都不得声称支持 LIMIT", () ->
                assertSupportsLimit(new SqlServerDialect(), false,
                        new SqlServer2008Dialect(), false, new SqlServer2012Dialect(), false));

        EngineFunctional.check("声明.Derby 两代方言都不得声称支持 LIMIT", () ->
                assertSupportsLimit(new DerbyDialect(), false, new Derby1014Dialect(), false));

        EngineFunctional.check("声明.通过 Dialect.require 取到的实例与直接 new 一致", () -> {
            Map<String, Boolean> expected = new LinkedHashMap<>();
            expected.put("postgresql", true);
            expected.put("clickhouse", true);
            expected.put("oracle", false);
            expected.put("oracle11g", false);
            expected.put("oracle12c", false);
            expected.put("sqlserver", false);
            expected.put("sqlserver2008", false);
            expected.put("sqlserver2012", false);
            for (Map.Entry<String, Boolean> e : expected.entrySet()) {
                Dialect d = Dialect.require(e.getKey());
                if (d.supportsLimit() != e.getValue()) {
                    throw new EngineFunctional.HarnessFailure(
                            e.getKey() + ".supportsLimit 应为 " + e.getValue());
                }
            }
            return "8 protocols";
        });
    }

    // ==================== LIMIT 方言的渲染 ====================

    private static void limitStyleSuite() {
        EngineFunctional.emit("--- dialect.limit-style ---");

        EngineFunctional.check("渲染.postgresql：LIMIT 3 OFFSET 3", () -> {
            String sql = page(new PostgresqlDialect(), 2, 3);
            if (!"SELECT * FROM t_user LIMIT 3 OFFSET 3".equals(sql)) {
                throw new EngineFunctional.HarnessFailure("实际: " + sql);
            }
            return sql;
        });

        EngineFunctional.check("渲染.clickhouse：LIMIT 3 OFFSET 3", () -> {
            String sql = page(new ClickHouseDialect(), 2, 3);
            if (!"SELECT * FROM t_user LIMIT 3 OFFSET 3".equals(sql)) {
                throw new EngineFunctional.HarnessFailure("实际: " + sql);
            }
            return sql;
        });

        EngineFunctional.check("渲染.mysql：仍为标准 LIMIT/OFFSET（无回归）", () -> {
            String sql = page(new MysqlDialect(), 1, 10);
            if (!"SELECT * FROM t_user LIMIT 10 OFFSET 0".equals(sql)) {
                throw new EngineFunctional.HarnessFailure("实际: " + sql);
            }
            return sql;
        });

        EngineFunctional.check("渲染.偏移量来自 pageNum/pageSize：(3,2) → OFFSET 4", () -> {
            Pagination p = new Pagination().setPageNum(3).setPageSize(2);
            if (p.getOffset() != 4 || p.getLimit() != 2) {
                throw new EngineFunctional.HarnessFailure(
                        "offset=" + p.getOffset() + " limit=" + p.getLimit());
            }
            String sql = new PostgresqlDialect().processSql("SELECT 1", p);
            if (!sql.endsWith("LIMIT 2 OFFSET 4")) {
                throw new EngineFunctional.HarnessFailure("实际: " + sql);
            }
            return sql;
        });
    }

    // ==================== 模板渲染（无 LIMIT 方言） ====================

    private static void templateSuite() {
        EngineFunctional.emit("--- dialect.template ---");

        EngineFunctional.check("模板.oracle 19c：ROWNUM 双层包装，边界 3/6", () -> {
            String sql = page(new OracleDialect(), 2, 3);
            assertContains(sql, "ROWNUM ROW_ID", "oracle");
            assertContains(sql, "ROWNUM <= 3 + 3", "oracle");
            assertContains(sql, "WHERE ROW_ID > 3", "oracle");
            assertNotContains(sql, "LIMIT", "oracle");
            return sql;
        });

        EngineFunctional.check("模板.oracle11g：ROWNUM（不走 OFFSET/FETCH）", () -> {
            String sql = page(new Oracle11gDialect(), 2, 3);
            assertContains(sql, "ROWNUM", "oracle11g");
            assertNotContains(sql, "OFFSET", "oracle11g");
            assertNotContains(sql, "FETCH NEXT", "oracle11g");
            return sql;
        });

        EngineFunctional.check("模板.oracle12c：OFFSET/FETCH NEXT", () -> {
            String sql = page(new Oracle12cDialect(), 2, 3);
            assertContains(sql, "OFFSET 3 ROWS FETCH NEXT 3 ROWS ONLY", "oracle12c");
            assertNotContains(sql, "ROWNUM", "oracle12c");
            assertNotContains(sql, "LIMIT", "oracle12c");
            return sql;
        });

        EngineFunctional.check("模板.sqlserver 2019：OFFSET/FETCH NEXT", () -> {
            String sql = page(new SqlServerDialect(), 2, 3);
            assertContains(sql, "OFFSET 3 ROWS FETCH NEXT 3 ROWS ONLY", "sqlserver");
            assertNotContains(sql, "LIMIT", "sqlserver");
            return sql;
        });

        EngineFunctional.check("模板.sqlserver2012：OFFSET/FETCH NEXT", () -> {
            String sql = page(new SqlServer2012Dialect(), 2, 3);
            assertContains(sql, "OFFSET 3 ROWS FETCH NEXT 3 ROWS ONLY", "sqlserver2012");
            assertNotContains(sql, "LIMIT", "sqlserver2012");
            return sql;
        });

        EngineFunctional.check("模板.sqlserver2008：ROW_NUMBER 双嵌套，边界 3/6", () -> {
            String sql = page(new SqlServer2008Dialect(), 2, 3);
            assertContains(sql, "ROW_NUMBER() OVER (ORDER BY (SELECT NULL))", "sqlserver2008");
            assertContains(sql, "ROW_NUM > 3 AND ROW_NUM <= 3 + 3", "sqlserver2008");
            assertNotContains(sql, "LIMIT", "sqlserver2008");
            return sql;
        });

        EngineFunctional.check("模板.derby：OFFSET/FETCH NEXT", () -> {
            String sql = page(new DerbyDialect(), 2, 3);
            assertContains(sql, "OFFSET 3 ROWS FETCH NEXT 3 ROWS ONLY", "derby");
            assertNotContains(sql, "LIMIT", "derby");
            return sql;
        });

        EngineFunctional.check("模板.derby1014：ROW_NUMBER（无 OVER 排序）", () -> {
            String sql = page(new Derby1014Dialect(), 2, 3);
            assertContains(sql, "ROW_NUMBER() OVER ()", "derby1014");
            assertContains(sql, "ROW_ID > 3 AND ROW_ID <= 3 + 3", "derby1014");
            assertNotContains(sql, "LIMIT", "derby1014");
            return sql;
        });

        EngineFunctional.check("模板.首屏 offset=0 仍渲染模板（不是 LIMIT 0）", () ->
                assertFirstPageIsTemplate());

        EngineFunctional.check("模板.每页大小下限：pageSize<=0 视为 1", () -> {
            Pagination p = new Pagination().setPageNum(1).setPageSize(0);
            if (p.getLimit() != 1) {
                throw new EngineFunctional.HarnessFailure("limit=" + p.getLimit());
            }
            String sql = new SqlServerDialect().processSql("SELECT 1", p);
            if (!sql.contains("FETCH NEXT 1 ROWS ONLY")) {
                throw new EngineFunctional.HarnessFailure("实际: " + sql);
            }
            return sql;
        });
    }

    // ==================== ORDER BY 注入 ====================

    private static void orderBySuite() {
        EngineFunctional.emit("--- dialect.order-by ---");

        EngineFunctional.check("ORDER BY.SQL Server 无排序时补 (SELECT NULL)", () -> {
            String sql = page(new SqlServerDialect(), 2, 3);
            assertContains(sql, "ORDER BY (SELECT NULL)", "sqlserver");
            return sql;
        });

        EngineFunctional.check("ORDER BY.SQL Server 已有排序时不重复追加", () -> {
            String sql = new SqlServerDialect().processSql(
                    "SELECT * FROM t_user ORDER BY age DESC", page(2, 3));
            int first = sql.indexOf("ORDER BY");
            int second = sql.indexOf("ORDER BY", first + 1);
            if (second >= 0) {
                throw new EngineFunctional.HarnessFailure("ORDER BY 重复: " + sql);
            }
            assertContains(sql, "ORDER BY age DESC OFFSET 3 ROWS", "sqlserver");
            return sql;
        });

        EngineFunctional.check("ORDER BY.大小写不敏感（order by 视为已排序）", () -> {
            String sql = new SqlServerDialect().processSql(
                    "select * from t_user order by id", page(2, 3));
            if (sql.contains("ORDER BY (SELECT NULL)")) {
                throw new EngineFunctional.HarnessFailure("小写 ORDER BY 未被识别: " + sql);
            }
            return sql;
        });

        EngineFunctional.check("ORDER BY.Oracle 12c 用方言自己的回退（ORDER BY NULL）", () -> {
            String sql = page(new Oracle12cDialect(), 2, 3);
            assertContains(sql, "ORDER BY NULL", "oracle12c");
            assertNotContains(sql, "(SELECT NULL)", "oracle12c");
            return sql;
        });

        EngineFunctional.check("ORDER BY.Oracle 11g/19c 的 ROWNUM 模板不需要补排序", () -> {
            String sql = page(new Oracle11gDialect(), 2, 3);
            if (sql.contains("ORDER BY")) {
                throw new EngineFunctional.HarnessFailure("11g 模板不应注入排序: " + sql);
            }
            return sql;
        });
    }

    // ==================== 与引擎的接线 ====================

    private static void engineWiringSuite() {
        EngineFunctional.emit("--- dialect.engine-wiring ---");

        EngineFunctional.check("接线.四个引擎注册数据源后默认方言协议正确", () -> {
            Map<String, Boolean> engines = new LinkedHashMap<>();
            engines.put("postgresql", true);
            engines.put("clickhouse", true);
            engines.put("oracle", false);
            engines.put("sqlserver", false);
            for (Map.Entry<String, Boolean> e : engines.entrySet()) {
                Dialect d = dialectOf(e.getKey());
                if (d == null) {
                    throw new EngineFunctional.HarnessFailure(e.getKey() + " 方言为 null");
                }
                if (!e.getKey().equals(d.protocol())) {
                    throw new EngineFunctional.HarnessFailure(
                            e.getKey() + " 方言协议=" + d.protocol());
                }
                // 关键回归点：JdbcEngine.wrapPagination 依据 supportsLimit() 选路径，
                // 若这里为 true，Oracle / SQL Server 会收到它们不认识的 LIMIT 语法。
                if (d.supportsLimit() != e.getValue()) {
                    throw new EngineFunctional.HarnessFailure(
                            e.getKey() + " supportsLimit=" + d.supportsLimit());
                }
                String sql = d.processSql("SELECT 1", page(2, 3));
                if (e.getValue()) {
                    assertContains(sql, "LIMIT 3 OFFSET 3", e.getKey());
                } else {
                    assertNotContains(sql, "LIMIT", e.getKey());
                }
            }
            return "4 engines";
        });

        EngineFunctional.check("接线.sqlserver-legacy 使用 2008 方言（ROW_NUMBER）", () -> {
            Dialect d = dialectOf("sqlserver-legacy");
            if (d == null || !"sqlserver2008".equals(d.protocol())) {
                throw new EngineFunctional.HarnessFailure("协议=" + (d == null ? null : d.protocol()));
            }
            String sql = d.processSql("SELECT 1", page(2, 3));
            assertContains(sql, "ROW_NUMBER()", "sqlserver-legacy");
            assertNotContains(sql, "LIMIT", "sqlserver-legacy");
            return sql;
        });

        EngineFunctional.check("接线.方言的 processSql 不受调用顺序影响（无状态）", () -> {
            SqlServerDialect d = new SqlServerDialect();
            String first = d.processSql("SELECT 1", page(1, 10));
            String second = d.processSql("SELECT 1", page(2, 10));
            if (!first.contains("OFFSET 0 ROWS") || !second.contains("OFFSET 10 ROWS")) {
                throw new EngineFunctional.HarnessFailure(first + " | " + second);
            }
            if (!d.supportsLimit() == d.supportsLimit()) {
                throw new EngineFunctional.HarnessFailure("supportsLimit 不稳定");
            }
            return "stateless";
        });

        EngineFunctional.check("接线.非法方言名抛异常而不是返回 null", () -> {
            try {
                Dialect d = Dialect.require("no-such-dialect");
                if (d != null) {
                    throw new EngineFunctional.HarnessFailure("返回了实例: " + d.protocol());
                }
                throw new EngineFunctional.HarnessFailure("未抛异常也未返回 null");
            } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) {
                return expected.getClass().getSimpleName();
            }
        });
    }

    // ==================== 断言辅助 ====================

    private static Pagination page(int pageNum, int pageSize) {
        return new Pagination().setPageNum(pageNum).setPageSize(pageSize);
    }

    /**
     * 取引擎的默认方言。
     *
     * <p>方言挂在引擎内部的 {@code EngineDataSource} 上，必须先注册数据源才取得到；
     * 这里用不可达地址注册——各引擎的 {@code addDataSource} 只装配 Hikari 配置，
     * 不会在注册阶段建立物理连接，因此不需要任何服务端。</p>
     *
     * @param protocol SPI 键
     * @return 该引擎的默认方言
     */
    private static Dialect dialectOf(String protocol) {
        com.chua.common.support.lang.datasource.engine.Engine engine =
                com.chua.common.support.lang.datasource.engine.Engine.create(protocol);
        switch (protocol) {
            case "postgresql" ->
                    ((PostgresqlEngine) engine).addDataSource("default", "127.0.0.1", 6543, "accept", "u", "p");
            case "oracle" ->
                    ((OracleEngine) engine).addDataSource("default", "127.0.0.1", 6543, "accept", "u", "p");
            case "sqlserver" ->
                    ((SqlServerEngine) engine).addDataSource("default", "127.0.0.1", 6543, "accept", "u", "p");
            case "sqlserver-legacy" ->
                    ((SqlServerLegacyEngine) engine).addDataSource("default", "127.0.0.1", 6543, "accept", "u", "p");
            case "clickhouse" ->
                    ((ClickHouseEngine) engine).addDataSource("default", "127.0.0.1", 6543, "accept", "u", "p");
            default -> throw new EngineFunctional.HarnessFailure("未覆盖的引擎: " + protocol);
        }
        Dialect dialect = ((com.chua.datasource.support.engine.AbstractEngine) engine).getDialect();
        return dialect;
    }

    private static String page(Dialect dialect, int pageNum, int pageSize) {
        return dialect.processSql("SELECT * FROM t_user", page(pageNum, pageSize));
    }

    private static String assertSupportsLimit(Dialect a, boolean av, Dialect b, boolean bv) {
        if (a.supportsLimit() != av || b.supportsLimit() != bv) {
            throw new EngineFunctional.HarnessFailure(
                    a.protocol() + "=" + a.supportsLimit() + ", " + b.protocol() + "=" + b.supportsLimit());
        }
        return a.protocol() + "/" + b.protocol();
    }

    private static String assertSupportsLimit(Dialect a, boolean av, Dialect b, boolean bv,
                                              Dialect c, boolean cv) {
        assertSupportsLimit(a, av, b, bv);
        if (c.supportsLimit() != cv) {
            throw new EngineFunctional.HarnessFailure(c.protocol() + "=" + c.supportsLimit());
        }
        return a.protocol() + "/" + b.protocol() + "/" + c.protocol();
    }

    private static String assertFirstPageIsTemplate() {
        String oracle = new OracleDialect().processSql("SELECT 1", page(1, 10));
        assertContains(oracle, "ROWNUM <= 0 + 10", "oracle");
        assertNotContains(oracle, "LIMIT", "oracle");
        String sqlserver = new SqlServerDialect().processSql("SELECT 1", page(1, 10));
        assertContains(sqlserver, "OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY", "sqlserver");
        return "offset=0 ok";
    }

    private static void assertContains(String text, String fragment, String who) {
        if (text == null || !text.contains(fragment)) {
            throw new EngineFunctional.HarnessFailure(
                    who + " 缺少片段「" + fragment + "」: " + text);
        }
    }

    private static void assertNotContains(String text, String fragment, String who) {
        if (text != null && text.contains(fragment)) {
            throw new EngineFunctional.HarnessFailure(
                    who + " 不应出现「" + fragment + "」: " + text);
        }
    }
}
