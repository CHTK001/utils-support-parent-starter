import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.sqlserver.support.engine.SqlServerEngine;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

/**
 * 引擎验收运行器 —— 阶段二十三：SQL Server 真实实例读写与 T-SQL 语义。
 *
 * <p>本机没有容器运行时（`docker` / `podman` / `nerdctl` / `wsl` 均不存在），
 * 因此本阶段使用 <b>Microsoft SQL Server 2022 Express</b> 原生服务实例
 * （由 `winget` / 官方 Express 引导器安装，实例名 {@code SQLEXPRESS}，
 * 已开启 TCP 与 SQL 身份验证）。与阶段十四（只验配置层）和阶段二十一
 * （只验方言 SQL 文本）不同，本阶段<b>真的连库、真的建表、真的读写</b>。</p>
 *
 * <p>覆盖内容：DDL/DML、分页真实边界（{@code OFFSET .. ROWS FETCH NEXT .. ROWS ONLY}）、
 * 类型映射、{@code NVARCHAR} 的 Unicode/emoji 往返、T-SQL 专有语法
 * （{@code @@VERSION} / {@code TOP} / {@code LEN} / {@code ISNULL} / {@code IDENTITY} /
 * {@code DATETIME2} / {@code SYSDATETIME()}）、约束失败语义。</p>
 *
 * <p><b>跳过语义</b>：实例不可达时打印 {@code [SKIP]} 与 {@code STAGE23_SKIP=1}
 * 并以退出码 0 结束——其他机器不装 SQL Server 也能跑完整个套件；
 * 日志里能看到 SKIP 标记，不会被误当成「已通过」。</p>
 */
public final class EngineSqlServer {

    /** 实例地址：默认本机 1433（可用环境变量覆盖）。 */
    private static final String HOST = env("MSSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(env("MSSQL_PORT", "1433"));
    private static final String DATABASE = env("MSSQL_DB", "engine_accept");
    private static final String USER = env("MSSQL_USER", "sa");
    private static final String PASSWORD = env("MSSQL_PASSWORD", "PgAccept#2026");

    private EngineSqlServer() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段二十三：SQL Server 真实实例（" + HOST + ":" + PORT + "/" + DATABASE + "）");
        EngineFunctional.emit("=".repeat(78));

        if (!reachable()) {
            EngineFunctional.emit("[SKIP] 未检测到 SQL Server 实例 " + HOST + ":" + PORT
                    + "（本阶段需要先启动 SQLEXPRESS 服务并开启 TCP）");
            EngineFunctional.emit("STAGE23_SKIP=1");
            EngineFunctional.emit("  结果: PASS=0  FAIL=0  SKIP=1");
            EngineFunctional.emit("=".repeat(78));
            System.exit(0);
        }

        SqlServerEngine engine = (SqlServerEngine) Engine.create("sqlserver");
        try {
            // 1. 先连 master 建库：SQL Server 的 CREATE DATABASE 不能在目标库内执行。
            //    第一个加入的数据源即默认数据源，因此先加 master，建完库再加 default。
            engine.addDataSource("master", HOST, PORT, "master", USER, PASSWORD);
            engine.executeSql("IF DB_ID(N'" + DATABASE + "') IS NULL CREATE DATABASE [" + DATABASE + "]");
            engine.addDataSource("default", HOST, PORT, DATABASE, USER, PASSWORD);
            engine.setDefaultDataSourceName("default");
            engine.queryValue("SELECT 1");
        } catch (RuntimeException e) {
            EngineFunctional.recordFail("sqlserver 初始化失败: " + e);
            EngineFunctional.finishAndExit("EngineSqlServer-report.txt");
            return;
        }

        // 2. T-SQL 专有语义。必须在通用套件之前：sqlSuite 结束时会 close()
        //    引擎（其 finally 块），关闭后再调用只会拿到「引擎已关闭」类异常。
        sqlServerSemantics(engine);

        // 3. 复用通用 JDBC 套件：建表 / 插入 / 查询 / 分页 / count / Lambda / batch /
        //    全表更新删除拒绝 / 非法入参等，全部打真实服务端。
        EngineFunctional.sqlSuite("sqlserver", () -> engine, EngineFunctional.Profile.JDBC);

        // 4. 关闭后的错误语义：sqlSuite 的 finally 已 close() 引擎。
        EngineFunctional.check("sqlserver.关闭后调用：报「引擎已关闭」而不是「不支持 SQL」", () -> {
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

        EngineFunctional.finishAndExit("EngineSqlServer-report.txt");
    }

    // ==================== SQL Server / T-SQL 专有语义 ====================

    private static void sqlServerSemantics(Engine e) {
        EngineFunctional.emit("--- sqlserver.semantics ---");

        EngineFunctional.check("mssql.服务端版本确实是 Microsoft SQL Server", () -> {
            Object version = e.queryValue("SELECT @@VERSION");
            String text = String.valueOf(version);
            if (version == null || !text.contains("Microsoft SQL Server")) {
                throw new EngineFunctional.HarnessFailure("@@VERSION 异常: " + version);
            }
            return text.substring(0, Math.min(60, text.length())).replace('\n', ' ').replace('\r', ' ');
        });

        EngineFunctional.check("mssql.连接落在目标库（DB_NAME() 一致）", () -> {
            Object name = e.queryValue("SELECT DB_NAME()");
            if (!DATABASE.equals(String.valueOf(name))) {
                throw new EngineFunctional.HarnessFailure("DB_NAME() 期望 " + DATABASE + "，实际 " + name);
            }
            return String.valueOf(name);
        });

        EngineFunctional.check("mssql.NVARCHAR Unicode 往返（中文 + emoji + 制表符）", () -> {
            e.executeSql("DROP TABLE IF EXISTS t_acc_utf");
            e.executeSql("CREATE TABLE t_acc_utf (id INT PRIMARY KEY, name NVARCHAR(64), note NVARCHAR(64))");
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
            // T-SQL LEN 统计字符数（emoji 为代理对，SQL Server 计为 2 个 UTF-16 单元）
            Object len = e.queryValue("SELECT LEN(N'中文')");
            if (!(len instanceof Number num) || num.intValue() != 2) {
                throw new EngineFunctional.HarnessFailure("LEN(N'中文') 期望 2，实际 " + len);
            }
            return "round-trip ok, len=" + note.length() + " LEN(中文)=" + len;
        });

        EngineFunctional.check("mssql.IDENTITY + DATETIME2 默认值真实生效", () -> {
            e.executeSql("DROP TABLE IF EXISTS t_acc_ts");
            e.executeSql("CREATE TABLE t_acc_ts ("
                    + "id INT IDENTITY(1,1) PRIMARY KEY, ts DATETIME2 NOT NULL DEFAULT SYSDATETIME())");
            e.executeSql("INSERT INTO t_acc_ts DEFAULT VALUES");
            long n = e.count("SELECT COUNT(*) FROM t_acc_ts");
            if (n != 1) {
                throw new EngineFunctional.HarnessFailure("插入后行数期望 1，实际 " + n);
            }
            Object id = e.queryValue("SELECT id FROM t_acc_ts");
            if (!(id instanceof Number num) || num.longValue() != 1L) {
                throw new EngineFunctional.HarnessFailure("IDENTITY 首个值期望 1，实际 " + id);
            }
            Object ts = e.queryValue("SELECT ts FROM t_acc_ts");
            if (ts == null) {
                throw new EngineFunctional.HarnessFailure("DATETIME2 默认为 null");
            }
            return "id=" + id + " ts=" + ts.getClass().getSimpleName();
        });

        EngineFunctional.check("mssql.queryPage 真实边界：第2页=(3,4)，末页只剩1行", () -> {
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

        EngineFunctional.check("mssql.T-SQL 专有语法真实可用（TOP / ISNULL / CAST）", () -> {
            List<Map<String, Object>> top =
                    e.querySql("SELECT TOP 2 id FROM t_acc_page ORDER BY id");
            if (top == null || top.size() != 2) {
                throw new EngineFunctional.HarnessFailure("SELECT TOP 2 期望 2 行，实际 "
                        + (top == null ? "null" : top.size()));
            }
            Object first = valueIgnoreCase(top.get(0), "id");
            if (!(first instanceof Number num) || num.longValue() != 1L) {
                throw new EngineFunctional.HarnessFailure("TOP 首行 id 期望 1，实际 " + first);
            }
            Object isNull = e.queryValue("SELECT ISNULL(NULL, N'x')");
            if (!"x".equals(isNull)) {
                throw new EngineFunctional.HarnessFailure("ISNULL 期望 x，实际 " + isNull);
            }
            Object date = e.queryValue("SELECT CAST('2020-01-02' AS DATE)");
            if (date == null || !String.valueOf(date).startsWith("2020-01-02")) {
                throw new EngineFunctional.HarnessFailure("CAST AS DATE 异常: " + date);
            }
            return "top=2 isnull=" + isNull + " date=" + date;
        });

        EngineFunctional.check("mssql.queryPage 裁剪 ORDER BY 后总数仍正确（窗口函数/字面量/无排序）", () -> {
            List<Map<String, Object>> win = e.queryPage(
                    "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t_acc_page ORDER BY id", 1, 3);
            if (win == null || win.size() != 3) {
                throw new EngineFunctional.HarnessFailure("窗口函数场景第1页期望 3 行，实际 "
                        + (win == null ? "null" : win.size()));
            }
            assertIds(win, "窗口函数场景第1页", 1L, 2L, 3L);

            List<Map<String, Object>> lit = e.queryPage(
                    "SELECT id, name FROM t_acc_page WHERE name <> 'order by' ORDER BY id", 2, 2);
            assertIds(lit, "字面量场景第2页", 3L, 4L);

            List<Map<String, Object>> noOrder = e.queryPage("SELECT id, name FROM t_acc_page", 1, 2);
            if (noOrder == null || noOrder.size() != 2) {
                throw new EngineFunctional.HarnessFailure("无 ORDER BY 场景期望 2 行，实际 "
                        + (noOrder == null ? "null" : noOrder.size()));
            }

            List<Map<String, Object>> withParam = e.queryPage(
                    "SELECT id, name FROM t_acc_page WHERE id > ? ORDER BY id", 1, 2, 2);
            assertIds(withParam, "带参数场景第1页", 3L, 4L);
            return "window=3 literal=3,4 noOrder=2 param=3,4";
        });

        EngineFunctional.check("mssql.查询不存在的表：异常带服务端原因", () -> {
            try {
                e.querySql("SELECT * FROM t_acc_no_such_table");
                throw new EngineFunctional.HarnessFailure("未抛异常");
            } catch (EngineFunctional.HarnessFailure f) {
                throw f;
            } catch (Exception expected) {
                String msg = String.valueOf(expected.getMessage());
                String lower = msg.toLowerCase();
                if (!lower.contains("t_acc_no_such_table")) {
                    throw new EngineFunctional.HarnessFailure(
                            "异常消息未带表名: " + expected.getMessage());
                }
                // 服务端语言可能是英文或中文（本机为中文媒体）：
                // en: Invalid object name '...' / zh: 对象名 '...' 无效。
                if (!lower.contains("invalid object name") && !msg.contains("无效")
                        && !lower.contains("does not exist") && !msg.contains("不存在")) {
                    throw new EngineFunctional.HarnessFailure(
                            "异常消息未带服务端原因: " + expected.getMessage());
                }
                return "SQLState=" + sqlState(expected);
            }
        });

        EngineFunctional.check("mssql.主键冲突：写失败且不留半条数据", () -> {
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
                String msg = String.valueOf(expected.getMessage()).toLowerCase();
                if (!msg.contains("primary key") && !msg.contains("duplicate")) {
                    throw new EngineFunctional.HarnessFailure(
                            "异常消息未带约束原因: " + expected.getMessage());
                }
                return "SQLState=" + sqlState(expected) + " rows=" + after;
            }
        });

        EngineFunctional.check("mssql.引擎方言与真实分页一致：supportsLimit=false + OFFSET/FETCH", () -> {
            var dialect = ((com.chua.datasource.support.engine.AbstractEngine) e).getDialect();
            if (dialect == null || !"sqlserver".equals(dialect.protocol())) {
                throw new EngineFunctional.HarnessFailure("方言协议异常: "
                        + (dialect == null ? null : dialect.protocol()));
            }
            if (dialect.supportsLimit()) {
                throw new EngineFunctional.HarnessFailure("sqlserver 不应声称支持 LIMIT");
            }
            String sql = dialect.processSql("SELECT 1", new com.chua.common.support.lang.datasource.dialect.Pagination()
                    .setPageNum(2).setPageSize(3));
            if (!sql.endsWith("OFFSET 3 ROWS FETCH NEXT 3 ROWS ONLY")) {
                throw new EngineFunctional.HarnessFailure("分页 SQL 异常: " + sql);
            }
            return sql;
        });

        cleanup(e);
    }

    // ==================== 辅助 ====================

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void cleanup(Engine e) {
        for (String table : new String[]{"t_acc_user", "t_acc_utf", "t_acc_ts", "t_acc_page"}) {
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
