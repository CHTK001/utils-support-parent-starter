import com.chua.greptimedb.support.client.GreptimeJdbcClient;
import com.chua.greptimedb.support.engine.GreptimeDbEngine;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 引擎验收运行器 —— 阶段十九：{@code GreptimeDbEngine}（SPI {@code greptimedb}）。
 *
 * <p>本机没有 GreptimeDB 服务端。但该引擎的查询/分页/计数/删除全部走
 * <b>MySQL 协议 JDBC</b>（{@link GreptimeJdbcClient}，内部是
 * {@code DriverManager.getConnection(url)}），而 JDBC 的
 * {@link Connection} / {@link PreparedStatement} / {@link ResultSet} /
 * {@link ResultSetMetaData} 全是接口。因此验收里向 {@link DriverManager}
 * 注册一个<b>只认 {@code jdbc:greptimefake:} 前缀</b>的假 {@link Driver}：
 * </p>
 *
 * <ul>
 *   <li>引擎照常渲染 SQL、照常绑定 {@code ?} 参数；</li>
 *   <li>假驱动记录<b>实际执行的每一条 SQL 与每个参数</b>，并按 SQL 形态回放结果集；</li>
 *   <li>断言聚焦「引擎到底发了什么 SQL、又如何映射结果」，第三方 MySQL 驱动不参与。</li>
 * </ul>
 *
 * <p>这样把该引擎从「仅契约面」升级为「真实 SQL 生成 + 真实 JDBC 调用链」，
 * 但仍<b>不是</b>真实 GreptimeDB 读写验收（没有服务端参与）。</p>
 */
public final class EngineGreptimeDb {

    /**
     * 用于验证驼峰转下划线的实体（无 {@code @TableName}）。
     */
    public static class PlainUser {
        private Long id;
        private String name;

        public PlainUser() {
        }

        public PlainUser(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * 用于验证列名归一化（{@code cpuUtil} → {@code cpu_util}）。
     */
    public static class CpuMetric {
        private String host;
        private Integer cpuUtil;

        public CpuMetric() {
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getCpuUtil() {
            return cpuUtil;
        }

        public void setCpuUtil(Integer cpuUtil) {
            this.cpuUtil = cpuUtil;
        }
    }

    private EngineGreptimeDb() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十九：GreptimeDbEngine（假 JDBC 驱动驱动真实 SQL 生成）");
        EngineFunctional.emit("=".repeat(78));

        labelSuite();
        querySuite();
        mapSuite();
        writeSuite();
        execSuite();
        urlSuite();
        lifeSuite();

        EngineFunctional.finishAndExit("EngineGreptimeDb-report.txt");
    }

    // ==================== 表名与列名归一化 ====================

    private static void labelSuite() {
        EngineFunctional.emit("--- greptime.label ---");

        EngineFunctional.check("label.@TableName 生效：FROM t_acc_user（不再出现 AccUser）", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                String sql = db.lastSql();
                if (!sql.contains(" FROM t_acc_user")) {
                    throw new EngineFunctional.HarnessFailure("表名未使用 @TableName: " + sql);
                }
                if (sql.contains("AccUser")) {
                    throw new EngineFunctional.HarnessFailure("仍用简单类名当表名: " + sql);
                }
                return sql;
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("label.无 @TableName：驼峰转下划线 plain_user", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.query(PlainUser.class).list();
                String sql = db.lastSql();
                if (!sql.contains(" FROM plain_user")) {
                    throw new EngineFunctional.HarnessFailure("期望 plain_user: " + sql);
                }
                return sql;
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("label.列名归一化：cpuUtil → cpu_util", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.query(CpuMetric.class).eq(CpuMetric::getCpuUtil, 5).list();
                String sql = db.lastSql();
                if (!sql.contains(" FROM cpu_metric")) {
                    throw new EngineFunctional.HarnessFailure("表名错误: " + sql);
                }
                if (!sql.contains("cpu_util = ?")) {
                    throw new EngineFunctional.HarnessFailure("列名未归一化: " + sql);
                }
                if (!Integer.valueOf(5).equals(db.lastParams().get(0))) {
                    throw new EngineFunctional.HarnessFailure("参数错误: " + db.lastParams());
                }
                return sql;
            } finally {
                close(e, db);
            }
        });
    }

    // ==================== 查询渲染 ====================

    private static void querySuite() {
        EngineFunctional.emit("--- greptime.query ---");

        EngineFunctional.check("query.eq 参数化：WHERE name = ? 且值不进 SQL 文本", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.query(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice").list();
                String sql = db.lastSql();
                if (!sql.contains("WHERE name = ?")) {
                    throw new EngineFunctional.HarnessFailure("条件未参数化: " + sql);
                }
                if (sql.contains("alice")) {
                    throw new EngineFunctional.HarnessFailure("值被内联: " + sql);
                }
                if (!"alice".equals(db.lastParams().get(0))) {
                    throw new EngineFunctional.HarnessFailure("参数 p0 错误: " + db.lastParams());
                }
                return sql;
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("query.gt + orderByDesc：条件与排序都下推", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.query(EngineFunctional.AccUser.class)
                        .gt(EngineFunctional.AccUser::getAge, 20)
                        .orderByDesc(EngineFunctional.AccUser::getAge)
                        .list();
                String sql = db.lastSql();
                if (!sql.contains("age > ?")) {
                    throw new EngineFunctional.HarnessFailure("条件未下推: " + sql);
                }
                if (!sql.contains("ORDER BY age DESC")) {
                    throw new EngineFunctional.HarnessFailure("排序未下推: " + sql);
                }
                return sql;
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("query.one() 返回首行（通用包装器不下推分页，与其它 JDBC 引擎一致）", () -> {
            FakeDb db = new FakeDb();
            db.columns = List.of("id", "name", "age");
            db.rows = java.util.Collections.singletonList(new Object[]{1L, "alice", 30});
            GreptimeDbEngine e = engine(db);
            try {
                EngineFunctional.AccUser u = e.query(EngineFunctional.AccUser.class).one();
                if (u == null || !Long.valueOf(1L).equals(u.getId()) || !"alice".equals(u.getName())) {
                    throw new EngineFunctional.HarnessFailure("one() 未返回首行: " + u);
                }
                return "first row";
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("query.limit(1) 显式下推 LIMIT 1 OFFSET 0", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.query(EngineFunctional.AccUser.class).limit(1).list();
                String sql = db.lastSql();
                if (!sql.endsWith("LIMIT 1 OFFSET 0")) {
                    throw new EngineFunctional.HarnessFailure("limit 未下推: " + sql);
                }
                return sql;
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("query.page(2,3) 下推 LIMIT 3 OFFSET 3", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.query(EngineFunctional.AccUser.class).page(2, 3);
                String sql = db.cyphers();
                if (!db.sqls.stream().anyMatch(s -> s.endsWith("LIMIT 3 OFFSET 3"))) {
                    throw new EngineFunctional.HarnessFailure("分页未下推: " + sql);
                }
                return sql;
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("query.page 的 total 来自 COUNT(*) 子查询", () -> {
            FakeDb db = new FakeDb();
            db.total = 12L;
            GreptimeDbEngine e = engine(db);
            try {
                var page = e.query(EngineFunctional.AccUser.class).page(2, 3);
                if (page.getTotal() != 12L) {
                    throw new EngineFunctional.HarnessFailure("期望 total=12，实际 " + page.getTotal());
                }
                boolean counted = db.sqls.stream().anyMatch(s -> s.startsWith("SELECT COUNT(*) FROM ("));
                if (!counted) {
                    throw new EngineFunctional.HarnessFailure("未发起计数子查询: " + db.sqls);
                }
                return "total=12";
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("query.count() 返回 COUNT 子查询结果", () -> {
            FakeDb db = new FakeDb();
            db.total = 23L;
            GreptimeDbEngine e = engine(db);
            try {
                long n = e.query(EngineFunctional.AccUser.class).count();
                if (n != 23L) {
                    throw new EngineFunctional.HarnessFailure("期望 23，实际 " + n);
                }
                return "count=23";
            } finally {
                close(e, db);
            }
        });
    }

    // ==================== 结果映射 ====================

    private static void mapSuite() {
        EngineFunctional.emit("--- greptime.map ---");

        EngineFunctional.check("map.列标签 → 实体（Long/String/Integer 类型转换）", () -> {
            FakeDb db = new FakeDb();
            db.columns = List.of("id", "name", "age");
            db.rows = java.util.Collections.singletonList(new Object[]{1L, "alice", 30});
            GreptimeDbEngine e = engine(db);
            try {
                List<EngineFunctional.AccUser> list = e.query(EngineFunctional.AccUser.class).list();
                if (list.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("期望 1 行，实际 " + list.size());
                }
                EngineFunctional.AccUser u = list.get(0);
                if (!Long.valueOf(1L).equals(u.getId()) || !"alice".equals(u.getName())
                        || !Integer.valueOf(30).equals(u.getAge())) {
                    throw new EngineFunctional.HarnessFailure("映射错误: " + u.getId()
                            + "/" + u.getName() + "/" + u.getAge());
                }
                return "1 row mapped";
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("map.列标签大小写宽松匹配（ID/NAME/AGE）", () -> {
            FakeDb db = new FakeDb();
            db.columns = List.of("ID", "NAME", "AGE");
            db.rows = java.util.Collections.singletonList(new Object[]{2L, "bob", 41});
            GreptimeDbEngine e = engine(db);
            try {
                EngineFunctional.AccUser u = e.query(EngineFunctional.AccUser.class).one();
                if (!Long.valueOf(2L).equals(u.getId()) || !"bob".equals(u.getName())
                        || !Integer.valueOf(41).equals(u.getAge())) {
                    throw new EngineFunctional.HarnessFailure("宽松匹配失败: " + u.getId()
                            + "/" + u.getName() + "/" + u.getAge());
                }
                return "case-insensitive labels";
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("map.未知列与 null 值被跳过，其余字段仍映射", () -> {
            FakeDb db = new FakeDb();
            db.columns = List.of("id", "extra", "name");
            db.rows = java.util.Collections.singletonList(new Object[]{3L, "ignored", null});
            GreptimeDbEngine e = engine(db);
            try {
                EngineFunctional.AccUser u = e.query(EngineFunctional.AccUser.class).one();
                if (!Long.valueOf(3L).equals(u.getId()) || u.getName() != null || u.getAge() != null) {
                    throw new EngineFunctional.HarnessFailure("跳过语义错误: " + u.getId()
                            + "/" + u.getName());
                }
                return "unknown + null skipped";
            } finally {
                close(e, db);
            }
        });
    }

    // ==================== 写入语义（该引擎明确不支持的两条） ====================

    private static void writeSuite() {
        EngineFunctional.emit("--- greptime.write ---");

        EngineFunctional.check("write.store 抛 UOE：禁止内存旁路存储", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.store("t_acc_user", List.of(new EngineFunctional.AccUser(1L, "alice", 30)));
                throw new EngineFunctional.HarnessFailure("store 未抛 UOE");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("不支持内存存储")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("write.update 抛 UOE 并提示 upsert", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.update(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice")
                        .set(EngineFunctional.AccUser::getName, "x").update();
                throw new EngineFunctional.HarnessFailure("update 未抛 UOE");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("不支持 UPDATE")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                if (!db.sqls.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("UOE 之前已发出 SQL: " + db.sqls);
                }
                return expected.getMessage();
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("write.delete 无 WHERE 被基类守卫拒绝（防清表，且不发 SQL）", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.delete(EngineFunctional.AccUser.class).remove();
                throw new EngineFunctional.HarnessFailure("无 WHERE 的删除未抛异常");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("禁止无 WHERE")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                if (!db.sqls.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("拒绝前已发出 SQL: " + db.sqls);
                }
                return expected.getMessage();
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("write.delete 带条件：DELETE ... WHERE name = ? 返回影响行数", () -> {
            FakeDb db = new FakeDb();
            db.updateCount = 2;
            GreptimeDbEngine e = engine(db);
            try {
                int n = e.delete(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice").remove();
                String sql = db.lastSql();
                if (!sql.startsWith("DELETE FROM t_acc_user WHERE name = ?")) {
                    throw new EngineFunctional.HarnessFailure("删除 SQL 错误: " + sql);
                }
                if (!"alice".equals(db.lastParams().get(0))) {
                    throw new EngineFunctional.HarnessFailure("删除参数错误: " + db.lastParams());
                }
                if (n != 2) {
                    throw new EngineFunctional.HarnessFailure("期望返回 2，实际 " + n);
                }
                return sql + " -> " + n;
            } finally {
                close(e, db);
            }
        });
    }

    // ==================== 通用执行器 ====================

    private static void execSuite() {
        EngineFunctional.emit("--- greptime.exec ---");

        EngineFunctional.check("exec.getExecutor().query 返回列名→值映射", () -> {
            FakeDb db = new FakeDb();
            db.columns = List.of("id", "name");
            db.rows = java.util.Collections.singletonList(new Object[]{1L, "alice"});
            GreptimeDbEngine e = engine(db);
            try {
                List<Map<String, Object>> rows = e.getExecutor().query("SELECT * FROM t");
                if (rows.size() != 1 || !"alice".equals(rows.get(0).get("name"))) {
                    throw new EngineFunctional.HarnessFailure("执行器结果错误: " + rows);
                }
                return rows.toString();
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("exec.getExecutor().execute 返回影响行数", () -> {
            FakeDb db = new FakeDb();
            db.updateCount = 5;
            GreptimeDbEngine e = engine(db);
            try {
                int n = e.getExecutor().execute("DELETE FROM t WHERE id = ?", 9);
                if (n != 5) {
                    throw new EngineFunctional.HarnessFailure("期望 5，实际 " + n);
                }
                return "updateCount=5";
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("exec.batch 透传多行参数并返回各行影响行数", () -> {
            FakeDb db = new FakeDb();
            db.batchCounts = new int[]{1, 1, 0};
            GreptimeDbEngine e = engine(db);
            try {
                int[] out = e.getExecutor().batch("INSERT INTO t VALUES (?)",
                        java.util.Arrays.<Object[]>asList(new Object[]{1}, new Object[]{2}, new Object[]{3}));
                if (out.length != 3 || out[2] != 0) {
                    throw new EngineFunctional.HarnessFailure("batch 结果错误: " + java.util.Arrays.toString(out));
                }
                if (db.batchRows != 3) {
                    throw new EngineFunctional.HarnessFailure("batch 行数未透传: " + db.batchRows);
                }
                return java.util.Arrays.toString(out);
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("exec.queryPage 追加 LIMIT 2 OFFSET 4", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                e.getExecutor().queryPage("SELECT * FROM t",
                        new com.chua.common.support.lang.datasource.dialect.Pagination()
                                .setPageNum(3).setPageSize(2));
                String sql = db.lastSql();
                if (!sql.endsWith("LIMIT 2 OFFSET 4")) {
                    throw new EngineFunctional.HarnessFailure("分页未追加: " + sql);
                }
                return sql;
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("exec.query(sql, Class) 映射为实体", () -> {
            FakeDb db = new FakeDb();
            db.columns = List.of("id", "name", "age");
            db.rows = java.util.Collections.singletonList(new Object[]{7L, "carol", 28});
            GreptimeDbEngine e = engine(db);
            try {
                List<EngineFunctional.AccUser> list =
                        e.getExecutor().query("SELECT * FROM t", EngineFunctional.AccUser.class);
                if (list.size() != 1 || !"carol".equals(list.get(0).getName())) {
                    throw new EngineFunctional.HarnessFailure("实体映射错误: " + list);
                }
                return "1 entity";
            } finally {
                close(e, db);
            }
        });

        EngineFunctional.check("exec.未注册的数据源：getExecutor(name)=null、client(name) 抛 IAE", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            try {
                if (e.getExecutor("missing") != null) {
                    throw new EngineFunctional.HarnessFailure("缺失数据源却返回了执行器");
                }
                try {
                    e.client("missing");
                    throw new EngineFunctional.HarnessFailure("client(missing) 未抛异常");
                } catch (IllegalArgumentException expected) {
                    if (!expected.getMessage().contains("数据源不存在")) {
                        throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                    }
                    return "null executor + IAE";
                }
            } finally {
                close(e, db);
            }
        });
    }

    // ==================== URL 推导（纯函数） ====================

    private static void urlSuite() {
        EngineFunctional.emit("--- greptime.url ---");

        EngineFunctional.check("url.端点 → jdbc:mysql://host:4002/db + 固定参数", () -> {
            String url = GreptimeJdbcClient.jdbcUrlFromEndpoint("172.16.0.40:4001", "public");
            String expected = "jdbc:mysql://172.16.0.40:4002/public?useSSL=false"
                    + "&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=60000"
                    + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
            if (!expected.equals(url)) {
                throw new EngineFunctional.HarnessFailure("URL 不符: " + url);
            }
            return url;
        });

        EngineFunctional.check("url.协议前缀与路径被剥离，IPv6 保留方括号", () -> {
            String http = GreptimeJdbcClient.hostOf("http://h.example:4001/path");
            String v6 = GreptimeJdbcClient.hostOf("[::1]:4001");
            if (!"h.example".equals(http) || !"[::1]".equals(v6)) {
                throw new EngineFunctional.HarnessFailure("hostOf 错误: " + http + " / " + v6);
            }
            return http + " | " + v6;
        });

        EngineFunctional.check("url.空端点/非法库名被拒绝", () -> {
            try {
                GreptimeJdbcClient.hostOf("  ");
                throw new EngineFunctional.HarnessFailure("空端点未被拒绝");
            } catch (IllegalArgumentException expected) {
                // 期望
            }
            try {
                GreptimeJdbcClient.jdbcUrlFromEndpoint("h:4001", "bad db;drop");
                throw new EngineFunctional.HarnessFailure("非法库名未被拒绝");
            } catch (IllegalArgumentException expected) {
                return "rejected";
            }
        });

        EngineFunctional.check("url.setJdbcUrl 只接受 jdbc: 前缀，空串表示清除", () -> {
            GreptimeDbEngine e = new GreptimeDbEngine();
            try {
                e.setJdbcUrl("mysql://h:4002/db");
                throw new EngineFunctional.HarnessFailure("非 jdbc: 前缀未被拒绝");
            } catch (IllegalArgumentException expected) {
                // 期望
            }
            if (e.setJdbcUrl("  ") == null) {
                throw new EngineFunctional.HarnessFailure("setJdbcUrl 应返回 this");
            }
            return "guarded";
        });
    }

    // ==================== 生命周期 ====================

    private static void lifeSuite() {
        EngineFunctional.emit("--- greptime.life ---");

        EngineFunctional.check("life.未配置数据源：查询抛 ISE（不是空结果）", () -> {
            GreptimeDbEngine e = new GreptimeDbEngine();
            try {
                e.query(EngineFunctional.AccUser.class).list();
                throw new EngineFunctional.HarnessFailure("未配置却返回了结果");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("未配置 GreptimeDB 数据源")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            }
        });

        EngineFunctional.check("life.close() 后再查询抛 ISE，重新注册可恢复", () -> {
            FakeDb db = new FakeDb();
            GreptimeDbEngine e = engine(db);
            e.close();
            try {
                e.query(EngineFunctional.AccUser.class).list();
                throw new EngineFunctional.HarnessFailure("close() 后仍可查询");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("已关闭")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
            }
            engineAgain(e, new FakeDb());
            try {
                e.query(EngineFunctional.AccUser.class).list();
                return "closed -> ISE -> re-registered -> ok";
            } finally {
                e.close();
                db.deregister();
            }
        });

        EngineFunctional.check("life.数据源名不允许空", () -> {
            GreptimeDbEngine e = new GreptimeDbEngine();
            try {
                e.addDataSource(" ", "127.0.0.1:4001", "public", null, null);
                throw new EngineFunctional.HarnessFailure("空名称未被拒绝");
            } catch (IllegalArgumentException expected) {
                return expected.getMessage();
            }
        });
    }

    // ==================== 基础设施 ====================

    private static GreptimeDbEngine engine(FakeDb db) {
        GreptimeDbEngine engine = new GreptimeDbEngine();
        db.register();
        engine.addDataSource("default", "127.0.0.1:4001", "public", null, null);
        engine.setJdbcUrl(db.jdbcUrl());
        return engine;
    }

    /** 引擎关闭后重新注册：验证 closed 标志被复位。 */
    private static void engineAgain(GreptimeDbEngine engine, FakeDb db) {
        db.register();
        engine.addDataSource("again", "127.0.0.1:4001", "public", null, null);
        engine.setJdbcUrl(db.jdbcUrl());
    }

    private static void close(GreptimeDbEngine engine, FakeDb db) {
        try {
            engine.close();
        } catch (RuntimeException ignored) {
            // 关闭期异常不影响断言结论
        }
        db.deregister();
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    /**
     * 回放型假 JDBC 库：记录 SQL 与参数，按 SQL 形态回放结果集。
     */
    static final class FakeDb {
        private static final ClassLoader CL = FakeDb.class.getClassLoader();
        private static final AtomicInteger SEQ = new AtomicInteger();

        private final int id = SEQ.incrementAndGet();
        private final Driver driver = new FakeDriver(this);

        final List<String> sqls = new ArrayList<>();
        final List<List<Object>> params = new ArrayList<>();
        List<String> columns = List.of("id", "name", "age");
        List<Object[]> rows = java.util.Collections.singletonList(new Object[]{1L, "alice", 30});
        long total = 7L;
        int updateCount = 1;
        int[] batchCounts = new int[]{1};
        int batchRows;
        boolean connectionClosed;
        boolean failOnQuery;

        void register() {
            try {
                DriverManager.registerDriver(driver);
            } catch (SQLException ex) {
                throw new IllegalStateException("注册假驱动失败", ex);
            }
        }

        void deregister() {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException ignored) {
                // 注销失败不影响断言
            }
        }

        String jdbcUrl() {
            return "jdbc:greptimefake:" + id + ":public";
        }

        String lastSql() {
            return sqls.isEmpty() ? "" : sqls.get(sqls.size() - 1);
        }

        List<Object> lastParams() {
            return params.isEmpty() ? List.of() : params.get(params.size() - 1);
        }

        String cyphers() {
            return String.join(" || ", sqls);
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(CL, new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "prepareStatement":
                                return statement((String) args[0]);
                            case "isClosed":
                                return connectionClosed;
                            case "close":
                                connectionClosed = true;
                                return null;
                            case "toString":
                                return "FakeConnection";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private PreparedStatement statement(String sql) {
            sqls.add(sql);
            List<Object> bound = new ArrayList<>();
            params.add(bound);
            return (PreparedStatement) Proxy.newProxyInstance(CL,
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setObject":
                                bind(bound, (Integer) args[0], args[1]);
                                return null;
                            case "setNull":
                                bind(bound, (Integer) args[0], null);
                                return null;
                            case "clearParameters":
                                bound.clear();
                                return null;
                            case "executeQuery":
                                if (failOnQuery) {
                                    throw new SQLException("fake query failure");
                                }
                                return resultSet(sql);
                            case "executeUpdate":
                                return updateCount;
                            case "addBatch":
                                batchRows++;
                                return null;
                            case "executeBatch":
                                return batchCounts;
                            case "close":
                            case "setFetchSize":
                            case "setQueryTimeout":
                                return null;
                            case "isClosed":
                                return false;
                            case "toString":
                                return sql;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private static void bind(List<Object> bound, int index, Object value) {
            while (bound.size() < index) {
                bound.add(null);
            }
            bound.set(index - 1, value);
        }

        private ResultSet resultSet(String sql) {
            boolean counting = sql.contains("COUNT(*)");
            List<String> cols = counting ? List.of("total") : columns;
            List<Object[]> data = counting
                    ? java.util.Collections.singletonList(new Object[]{total})
                    : rows;
            ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(CL,
                    new Class<?>[]{ResultSetMetaData.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getColumnCount":
                                return cols.size();
                            case "getColumnLabel":
                            case "getColumnName":
                                return cols.get((Integer) args[0] - 1);
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
            final int[] cursor = {-1};
            return (ResultSet) Proxy.newProxyInstance(CL, new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "next":
                                cursor[0]++;
                                return cursor[0] < data.size();
                            case "getObject":
                                return data.get(cursor[0])[(Integer) args[0] - 1];
                            case "getMetaData":
                                return meta;
                            case "getRow":
                                return cursor[0] + 1;
                            case "close":
                                return null;
                            case "wasNull":
                                return false;
                            case "toString":
                                return "FakeResultSet";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }
    }

    /**
     * 只接受本 {@link FakeDb} 专属前缀的假驱动。
     */
    static final class FakeDriver implements Driver {
        private final FakeDb db;

        FakeDriver(FakeDb db) {
            this.db = db;
        }

        @Override
        public Connection connect(String url, Properties info) {
            return acceptsURL(url) ? db.connection() : null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:greptimefake:" + db.id + ":");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
