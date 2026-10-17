import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.postgresql.support.engine.PostgresqlEngine;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

/**
 * 引擎验收运行器 —— 阶段二十二：PostgreSQL 真实实例读写与方言语义。
 *
 * <p>本机没有容器运行时（`docker` / `podman` / `nerdctl` / `wsl` 均不存在），
 * 因此本阶段不通过容器，而是使用 <b>官方免安装二进制</b> 在本机临时目录里
 * `initdb` 出一个独立实例（端口 {@code 55432}，`trust` 认证，不注册 Windows 服务、
 * 不写系统目录），跑完即 `pg_ctl stop`。</p>
 *
 * <p>与阶段十四（只验配置层）和阶段二十一（只验方言 SQL 文本）不同，本阶段
 * <b>真的连库、真的建表、真的读写</b>，因此能覆盖：DDL/DML、分页真实边界、
 * 类型映射、Unicode 往返、PostgreSQL 专有语法（`JSONB` / `->>` / `::` /
 * `BIGSERIAL` / `TIMESTAMPTZ`）、约束失败语义。</p>
 *
 * <p><b>跳过语义</b>：实例未启动时打印 {@code [SKIP]} 与 {@code STAGE22_SKIP=1}
 * 并以退出码 0 结束——其他机器不装 PostgreSQL 也能跑完整个套件；
 * 日志里能看到 SKIP 标记，不会被误当成「已通过」。</p>
 */
public final class EnginePostgres {

    /** 临时实例地址（非 5432，避免撞上用户自己的 PostgreSQL）。 */
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 55432;
    private static final String DATABASE = "engine_accept";
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    private EnginePostgres() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段二十二：PostgreSQL 真实实例（127.0.0.1:" + PORT + "/" + DATABASE + "）");
        EngineFunctional.emit("=".repeat(78));

        if (!reachable()) {
            EngineFunctional.emit("[SKIP] 未检测到 PostgreSQL 实例 " + HOST + ":" + PORT
                    + "（本阶段需要 run-postgres.bat 先启动临时实例）");
            EngineFunctional.emit("STAGE22_SKIP=1");
            EngineFunctional.emit("  结果: PASS=0  FAIL=0  SKIP=1");
            EngineFunctional.emit("=".repeat(78));
            System.exit(0);
        }

        PostgresqlEngine engine = (PostgresqlEngine) Engine.create("postgresql");
        engine.addDataSource("default", HOST, PORT, DATABASE, USER, PASSWORD);

        // 1. PostgreSQL 专有语义。必须在通用套件之前：sqlSuite 结束时会 close()
        //    引擎（其 finally 块），关闭后再调用只会拿到「引擎已关闭」类异常。
        pgSemantics(engine);

        // 2. 复用通用 JDBC 套件：建表 / 插入 / 查询 / 分页 / count / Lambda / batch /
        //    全表更新删除拒绝 / 非法入参等，全部打真实服务端。
        EngineFunctional.sqlSuite("postgresql", () -> engine, EngineFunctional.Profile.JDBC);

        // 3. 关闭后的错误语义：sqlSuite 的 finally 已 close() 引擎。
        //    这里的异常类型仍是 UOE（调用方按「不可用」捕获），但消息必须指明
        //    「已关闭」——此前一律报「当前引擎不支持 SQL 查询」，把用法状态
        //    说成了能力缺失（与缺陷 20/24/25/26 同源的「错误语义」问题）。
        EngineFunctional.check("pg.关闭后调用：报「引擎已关闭」而不是「不支持 SQL」", () -> {
            try {
                engine.querySql("SELECT 1");
                throw new EngineFunctional.HarnessFailure("close() 后仍可查询");
            } catch (EngineFunctional.HarnessFailure f) {
                throw f;
            } catch (RuntimeException expected) {
                String msg = String.valueOf(expected.getMessage());
                if (!msg.contains("已关闭")) {
                    throw new EngineFunctional.HarnessFailure("消息未指明已关闭: " + msg);
                }
                if (msg.contains("不支持")) {
                    throw new EngineFunctional.HarnessFailure("关闭后仍说「不支持」: " + msg);
                }
                return expected.getClass().getSimpleName() + ": " + msg;
            }
        });

        EngineFunctional.finishAndExit("EnginePostgres-report.txt");
    }

    // ==================== PostgreSQL 专有语义 ====================

    private static void pgSemantics(Engine e) {
        EngineFunctional.emit("--- postgresql.semantics ---");

        EngineFunctional.check("pg.服务端版本确实是 PostgreSQL", () -> {
            Object version = e.queryValue("SELECT version()");
            if (version == null || !String.valueOf(version).contains("PostgreSQL")) {
                throw new EngineFunctional.HarnessFailure("version() 异常: " + version);
            }
            return String.valueOf(version).substring(0, Math.min(60, String.valueOf(version).length()));
        });

        EngineFunctional.check("pg.Unicode 往返（中文 + emoji + 制表符）", () -> {
            e.executeSql("DROP TABLE IF EXISTS t_acc_utf");
            e.executeSql("CREATE TABLE t_acc_utf (id INT PRIMARY KEY, name VARCHAR(64), note TEXT)");
            String name = "张三";
            String note = "中文/emoji \uD83D\uDE00/制表\t结束";
            e.executeSql("INSERT INTO t_acc_utf (id, name, note) VALUES (?, ?, ?)", 1, name, note);
            List<Map<String, Object>> rows =
                    e.querySql("SELECT name, note FROM t_acc_utf WHERE id = ?", 1);
            if (rows == null || rows.size() != 1) {
                throw new EngineFunctional.HarnessFailure("读回行数异常: "
                        + (rows == null ? "null" : rows.size()));
            }
            Map<String, Object> row = rows.get(0);
            Object gotName = valueIgnoreCase(row, "name");
            Object gotNote = valueIgnoreCase(row, "note");
            if (!name.equals(gotName)) {
                throw new EngineFunctional.HarnessFailure("name 往返不一致: " + gotName);
            }
            if (!note.equals(gotNote)) {
                throw new EngineFunctional.HarnessFailure("note 往返不一致: " + gotNote);
            }
            return "round-trip ok, len=" + note.length();
        });

        EngineFunctional.check("pg.JSONB 与 ->> / ::int 运算符真实可用", () -> {
            e.executeSql("DROP TABLE IF EXISTS t_acc_json");
            e.executeSql("CREATE TABLE t_acc_json (id INT PRIMARY KEY, doc JSONB)");
            e.executeSql("INSERT INTO t_acc_json (id, doc) VALUES (?, CAST(? AS JSONB))",
                    1, "{\"a\":1,\"b\":\"中文\"}");
            Object b = e.queryValue("SELECT doc->>'b' FROM t_acc_json WHERE id = 1");
            if (!"中文".equals(b)) {
                throw new EngineFunctional.HarnessFailure("doc->>'b' 期望 中文，实际 " + b);
            }
            Object a = e.queryValue("SELECT (doc->>'a')::int FROM t_acc_json WHERE id = 1");
            if (!(a instanceof Number num) || num.intValue() != 1) {
                throw new EngineFunctional.HarnessFailure("(doc->>'a')::int 期望 1，实际 " + a);
            }
            return "jsonb ok, b=" + b + " a=" + a;
        });

        EngineFunctional.check("pg.BIGSERIAL + TIMESTAMPTZ 默认值真实生效", () -> {
            e.executeSql("DROP TABLE IF EXISTS t_acc_ts");
            e.executeSql("CREATE TABLE t_acc_ts ("
                    + "id BIGSERIAL PRIMARY KEY, ts TIMESTAMPTZ NOT NULL DEFAULT now())");
            e.executeSql("INSERT INTO t_acc_ts DEFAULT VALUES");
            long n = e.count("SELECT COUNT(*) FROM t_acc_ts");
            if (n != 1) {
                throw new EngineFunctional.HarnessFailure("插入后行数期望 1，实际 " + n);
            }
            Object id = e.queryValue("SELECT id FROM t_acc_ts");
            if (!(id instanceof Number num) || num.longValue() != 1L) {
                throw new EngineFunctional.HarnessFailure("BIGSERIAL 首个值期望 1，实际 " + id);
            }
            Object ts = e.queryValue("SELECT ts FROM t_acc_ts");
            if (ts == null) {
                throw new EngineFunctional.HarnessFailure("TIMESTAMPTZ 默认为 null");
            }
            return "id=" + id + " ts=" + ts.getClass().getSimpleName();
        });

        EngineFunctional.check("pg.queryPage 真实边界：第2页=(3,4)，末页只剩1行", () -> {
            e.executeSql("DROP TABLE IF EXISTS t_acc_page");
            e.executeSql("CREATE TABLE t_acc_page (id INT PRIMARY KEY, name VARCHAR(32))");
            for (int i = 1; i <= 5; i++) {
                e.executeSql("INSERT INTO t_acc_page (id, name) VALUES (?, ?)", i, "n" + i);
            }
            List<Map<String, Object>> p2 =
                    e.queryPage("SELECT id, name FROM t_acc_page ORDER BY id", 2, 2);
            assertIds(p2, "第2页", 3L, 4L);
            List<Map<String, Object>> p3 =
                    e.queryPage("SELECT id, name FROM t_acc_page ORDER BY id", 3, 2);
            assertIds(p3, "第3页", 5L);
            List<Map<String, Object>> p9 =
                    e.queryPage("SELECT id, name FROM t_acc_page ORDER BY id", 9, 2);
            if (p9 == null || !p9.isEmpty()) {
                throw new EngineFunctional.HarnessFailure("越界页应为空");
            }
            return "p2=3,4 p3=5 p9=empty";
        });

        EngineFunctional.check("pg.查询不存在的表：异常带服务端原因", () -> {
            try {
                e.querySql("SELECT * FROM t_acc_no_such_table");
                throw new EngineFunctional.HarnessFailure("未抛异常");
            } catch (EngineFunctional.HarnessFailure f) {
                throw f;
            } catch (Exception expected) {
                String msg = String.valueOf(expected.getMessage()).toLowerCase();
                if (!msg.contains("t_acc_no_such_table")) {
                    throw new EngineFunctional.HarnessFailure(
                            "异常消息未带表名: " + expected.getMessage());
                }
                if (!msg.contains("does not exist") && !msg.contains("relation")) {
                    throw new EngineFunctional.HarnessFailure(
                            "异常消息未带服务端原因: " + expected.getMessage());
                }
                return "SQLState=" + sqlState(expected);
            }
        });

        EngineFunctional.check("pg.主键冲突：写失败且不留半条数据", () -> {
            long before = e.count("SELECT COUNT(*) FROM t_acc_utf");
            try {
                e.executeSql("INSERT INTO t_acc_utf (id, name, note) VALUES (?, ?, ?)",
                        1, "重复", "重复");
                throw new EngineFunctional.HarnessFailure("主键冲突未抛异常");
            } catch (EngineFunctional.HarnessFailure f) {
                throw f;
            } catch (Exception expected) {
                long after = e.count("SELECT COUNT(*) FROM t_acc_utf");
                if (after != before) {
                    throw new EngineFunctional.HarnessFailure(
                            "失败写入改变了行数: " + before + " -> " + after);
                }
                return "SQLState=" + sqlState(expected) + " rows=" + after;
            }
        });

        EngineFunctional.check("pg.引擎方言与真实分页一致：supportsLimit=true", () -> {
            var dialect = ((com.chua.datasource.support.engine.AbstractEngine) e).getDialect();
            if (dialect == null || !"postgresql".equals(dialect.protocol())) {
                throw new EngineFunctional.HarnessFailure("方言协议异常: "
                        + (dialect == null ? null : dialect.protocol()));
            }
            if (!dialect.supportsLimit()) {
                throw new EngineFunctional.HarnessFailure("postgresql 应支持 LIMIT");
            }
            String sql = dialect.processSql("SELECT 1", new com.chua.common.support.lang.datasource.dialect.Pagination()
                    .setPageNum(2).setPageSize(3));
            if (!sql.endsWith("LIMIT 3 OFFSET 3")) {
                throw new EngineFunctional.HarnessFailure("分页 SQL 异常: " + sql);
            }
            return sql;
        });

        cleanup(e);
    }

    // ==================== 辅助 ====================

    private static void cleanup(Engine e) {
        for (String table : new String[]{"t_acc_user", "t_acc_utf", "t_acc_json", "t_acc_ts", "t_acc_page"}) {
            try {
                e.executeSql("DROP TABLE IF EXISTS " + table);
            } catch (Exception ignored) {
                // 清理失败不影响断言结论
            }
        }
    }

    private static void assertIds(List<Map<String, Object>> rows, String page, Long... expected) {
        if (rows == null || rows.size() != expected.length) {
            throw new EngineFunctional.HarnessFailure(page + " 行数期望 " + expected.length
                    + "，实际 " + (rows == null ? "null" : rows.size()));
        }
        for (int i = 0; i < expected.length; i++) {
            Object got = valueIgnoreCase(rows.get(i), "id");
            if (!(got instanceof Number num) || num.longValue() != expected[i]) {
                throw new EngineFunctional.HarnessFailure(
                        page + " 第" + (i + 1) + "行 id 期望 " + expected[i] + "，实际 " + got);
            }
        }
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String column) {
        if (row == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (column.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String sqlState(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sql) {
                return sql.getSQLState();
            }
            cur = cur.getCause();
        }
        return "n/a";
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
