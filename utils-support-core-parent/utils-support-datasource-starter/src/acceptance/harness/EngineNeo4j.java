import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.neo4j.support.engine.Neo4jEngine;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.Value;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 引擎验收运行器 —— 阶段十八：{@code Neo4jEngine}（SPI {@code neo4j}）。
 *
 * <p>本机没有 Neo4j 服务端。但引擎的 {@code addDataSource} 接受<b>任意</b>
 * {@link Driver}，而驱动本身是接口（{@code Driver} / {@code Session} /
 * {@code Transaction} / {@code Result} / {@code Record} / {@code Value} 全是接口）。
 * 因此验收里用 {@link Proxy} 构建一个「回放型假图数据库」：</p>
 *
 * <ul>
 *   <li>引擎照常拼 Cypher、照常把实体展开成属性 Map；</li>
 *   <li>假驱动记录<b>实际执行的每一条 Cypher 与参数</b>，并按 Cypher 形态回放固定记录；</li>
 *   <li>断言聚焦在「引擎到底发了什么、又如何解释结果」，而不是第三方驱动。</li>
 * </ul>
 *
 * <p>本阶段锁定四个真实缺陷的修复：</p>
 * <ul>
 *   <li>缺陷 22：{@code query(X).count()} 未覆写，直接抛
 *       {@code UnsupportedOperationException}（引擎里本就有 {@code cypherCount}）；</li>
 *   <li>缺陷 23：标签用 {@code getSimpleName()} 推导，绕过 {@code @TableName}
 *       与驼峰转下划线；</li>
 *   <li>缺陷 24：{@code close()} 无条件关闭<b>调用方注入</b>的驱动，外部持有的连接被擅自释放；</li>
 *   <li>缺陷 25：驱动抛出的 {@code RuntimeException}（服务端错误）被原样透出，丢失 Cypher 上下文。</li>
 * </ul>
 */
public final class EngineNeo4j {

    /** 无 {@code @TableName} 的实体：用于验证驼峰转下划线。 */
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

    private EngineNeo4j() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段十八：Neo4jEngine（动态代理假驱动驱动真实 Cypher 生成）");
        EngineFunctional.emit("=".repeat(78));

        labelSuite();
        querySuite();
        mapSuite();
        storeSuite();
        writeSuite();
        errorSuite();
        lifeSuite();

        EngineFunctional.finishAndExit("EngineNeo4j-report.txt");
    }

    // ==================== 标签解析（缺陷 23 回归锁） ====================

    private static void labelSuite() {
        EngineFunctional.emit("--- neo4j.label ---");

        EngineFunctional.check("label.@TableName 生效：写入标签为 t_acc_user", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.store("t_acc_user", List.of(new EngineFunctional.AccUser(1L, "alice", 30)));
                String cypher = g.lastCypher();
                if (!cypher.contains("(n:`t_acc_user`)")) {
                    throw new EngineFunctional.HarnessFailure(
                            "写入标签未使用 @TableName 值: " + cypher);
                }
                return cypher;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("label.无 @TableName：驼峰转下划线 plain_user", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.query(PlainUser.class).list();
                String cypher = g.lastCypher();
                if (!cypher.contains("(n:`plain_user`)")) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望 plain_user，实际: " + cypher);
                }
                return cypher;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("label.写入与读取使用同一标签", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                List<EngineFunctional.AccUser> rows = new ArrayList<>();
                rows.add(new EngineFunctional.AccUser(1L, "alice", 30));
                e.store("t_acc_user", rows);
                String write = g.lastCypher();
                e.query(EngineFunctional.AccUser.class).list();
                String read = g.lastCypher();
                String wl = between(write, "(n:", ")");
                String rl = between(read, "(n:", ")");
                if (!wl.equals(rl)) {
                    throw new EngineFunctional.HarnessFailure(
                            "写入标签 " + wl + " 与读取标签 " + rl + " 不一致");
                }
                return wl + " == " + rl;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("label.不再出现 AccUser 简单类名（防回退）", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                String cypher = g.lastCypher();
                if (cypher.contains("AccUser")) {
                    throw new EngineFunctional.HarnessFailure(
                            "仍在用 getSimpleName() 推导标签: " + cypher);
                }
                return "no simple-name label";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("label.store(name) 不改变标签（图库按实体类型定标签，行为已钉住）", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.store("whatever_name", List.of(new EngineFunctional.AccUser(1L, "alice", 30)));
                String cypher = g.lastCypher();
                if (cypher.contains("whatever_name")) {
                    throw new EngineFunctional.HarnessFailure(
                            "标签来源发生变化，需重新评估读写一致性: " + cypher);
                }
                if (!cypher.contains("(n:`t_acc_user`)")) {
                    throw new EngineFunctional.HarnessFailure("标签应为实体解析值: " + cypher);
                }
                return "name ignored, label=t_acc_user";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 查询构造 ====================

    private static void querySuite() {
        EngineFunctional.emit("--- neo4j.query ---");

        EngineFunctional.check("query.无条件：MATCH (n) RETURN n", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.query(EngineFunctional.AccUser.class).list();
                String cypher = g.lastCypher();
                if (!"MATCH (n:`t_acc_user`) RETURN n".equals(cypher)) {
                    throw new EngineFunctional.HarnessFailure("无条件查询形态错误: " + cypher);
                }
                return cypher;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.eq 参数化：WHERE n.name = $p0（值不内联）", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.query(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice").list();
                String cypher = g.lastCypher();
                if (!cypher.contains("WHERE n.name = $p0")) {
                    throw new EngineFunctional.HarnessFailure("条件未参数化: " + cypher);
                }
                if (cypher.contains("alice")) {
                    throw new EngineFunctional.HarnessFailure("值被内联进了 Cypher: " + cypher);
                }
                Object p0 = g.lastParams().get("p0");
                if (!"alice".equals(p0)) {
                    throw new EngineFunctional.HarnessFailure("参数 p0 错误: " + p0);
                }
                return cypher + " p0=" + p0;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.多条件 AND 且参数编号递增", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.query(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice")
                        .gt(EngineFunctional.AccUser::getAge, 20)
                        .list();
                String cypher = g.lastCypher();
                if (!cypher.contains("n.name = $p0 AND n.age > $p1")) {
                    throw new EngineFunctional.HarnessFailure("多条件拼接错误: " + cypher);
                }
                if (!Integer.valueOf(20).equals(g.lastParams().get("p1"))) {
                    throw new EngineFunctional.HarnessFailure(
                            "参数 p1 错误: " + g.lastParams().get("p1"));
                }
                return cypher;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.one() 下推 LIMIT 1", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.query(EngineFunctional.AccUser.class).one();
                String cypher = g.lastCypher();
                if (!cypher.endsWith("LIMIT 1")) {
                    throw new EngineFunctional.HarnessFailure("one() 未下推 LIMIT 1: " + cypher);
                }
                return cypher;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.page(2,3) 下推 SKIP 3 LIMIT 3", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                g.total = 7;
                e.query(EngineFunctional.AccUser.class).page(2, 3);
                String first = g.cyphers.get(0);
                if (!first.endsWith("SKIP 3 LIMIT 3")) {
                    throw new EngineFunctional.HarnessFailure("分页未下推: " + first);
                }
                return first;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.page 的 total 来自 count(n) 查询", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                g.total = 7;
                var page = e.query(EngineFunctional.AccUser.class).page(2, 3);
                if (page.getTotal() != 7L) {
                    throw new EngineFunctional.HarnessFailure("期望 total=7，实际 " + page.getTotal());
                }
                boolean counted = g.cyphers.stream().anyMatch(c -> c.contains("count(n) AS total"));
                if (!counted) {
                    throw new EngineFunctional.HarnessFailure("未发起计数查询: " + g.cyphers);
                }
                return "total=7";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.count() 返回 count(n)，不再抛 UOE（缺陷 22 回归锁）", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                g.total = 42;
                long n = e.query(EngineFunctional.AccUser.class).count();
                if (n != 42L) {
                    throw new EngineFunctional.HarnessFailure("期望 42，实际 " + n);
                }
                if (!g.lastCypher().contains("count(n) AS total")) {
                    throw new EngineFunctional.HarnessFailure(
                            "count() 未走计数查询: " + g.lastCypher());
                }
                return "count=42";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("query.orderByDesc 生成 ORDER BY n.name DESC", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.query(EngineFunctional.AccUser.class)
                        .orderByDesc(EngineFunctional.AccUser::getName).list();
                String cypher = g.lastCypher();
                if (!cypher.contains("ORDER BY n.name DESC")) {
                    throw new EngineFunctional.HarnessFailure("排序未下推: " + cypher);
                }
                return cypher;
            } finally {
                e.close();
            }
        });
    }

    // ==================== 结果映射 ====================

    private static void mapSuite() {
        EngineFunctional.emit("--- neo4j.map ---");

        EngineFunctional.check("map.节点属性映射为实体（含数字类型转换）", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", 1);
                row.put("name", "alice");
                row.put("age", 30);
                g.rows = List.of(row);
                List<EngineFunctional.AccUser> list = e.query(EngineFunctional.AccUser.class).list();
                if (list.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("期望 1 行，实际 " + list.size());
                }
                EngineFunctional.AccUser u = list.get(0);
                if (!Long.valueOf(1L).equals(u.getId()) || !"alice".equals(u.getName())
                        || !Integer.valueOf(30).equals(u.getAge())) {
                    throw new EngineFunctional.HarnessFailure("映射错误: id=" + u.getId()
                            + " name=" + u.getName() + " age=" + u.getAge());
                }
                return u.getId() + "/" + u.getName() + "/" + u.getAge();
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("map.缺失属性保持 null", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", 9);
                g.rows = List.of(row);
                EngineFunctional.AccUser u = e.query(EngineFunctional.AccUser.class).one();
                if (!Long.valueOf(9L).equals(u.getId()) || u.getName() != null || u.getAge() != null) {
                    throw new EngineFunctional.HarnessFailure("缺失属性未保持 null: name="
                            + u.getName() + " age=" + u.getAge());
                }
                return "id=9, name=null, age=null";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 写入 ====================

    private static void storeSuite() {
        EngineFunctional.emit("--- neo4j.store ---");

        EngineFunctional.check("store.UNWIND $rows 批量创建，属性和来自 getter", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                List<EngineFunctional.AccUser> rows = new ArrayList<>();
                rows.add(new EngineFunctional.AccUser(1L, "alice", 30));
                rows.add(new EngineFunctional.AccUser(2L, "bob", 25));
                e.store("t_acc_user", rows);
                String cypher = g.lastCypher();
                if (!cypher.startsWith("UNWIND $rows AS row CREATE (n:`t_acc_user`)")) {
                    throw new EngineFunctional.HarnessFailure("批量写入形态错误: " + cypher);
                }
                Object raw = g.lastParams().get("rows");
                if (!(raw instanceof List<?> list) || list.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("rows 参数错误: " + raw);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> first = (Map<String, Object>) list.get(0);
                if (!"alice".equals(first.get("name")) || !Integer.valueOf(30).equals(first.get("age"))) {
                    throw new EngineFunctional.HarnessFailure("属性 Map 错误: " + first);
                }
                return "bulk 2 rows -> t_acc_user";
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("store.空列表不触达驱动", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                e.store("t_acc_user", new ArrayList<>());
                if (!g.cyphers.isEmpty()) {
                    throw new EngineFunctional.HarnessFailure("空列表仍执行了 Cypher: " + g.cyphers);
                }
                return "no-op";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 更新 / 删除 / 原生 Cypher ====================

    private static void writeSuite() {
        EngineFunctional.emit("--- neo4j.write ---");

        EngineFunctional.check("write.update 生成 SET 并返回 updated 计数", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                g.updated = 3;
                int n = e.update(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice")
                        .set(EngineFunctional.AccUser::getName, "bob")
                        .update();
                String cypher = g.lastCypher();
                if (!cypher.contains("SET n.name = $set0")) {
                    throw new EngineFunctional.HarnessFailure("SET 子句错误: " + cypher);
                }
                if (!cypher.contains("RETURN count(n) AS updated")) {
                    throw new EngineFunctional.HarnessFailure("缺少 updated 计数: " + cypher);
                }
                if (n != 3) {
                    throw new EngineFunctional.HarnessFailure("期望返回 3，实际 " + n);
                }
                return cypher + " -> " + n;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("write.delete 生成 DETACH DELETE 并返回 deleted 计数", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                g.deleted = 2;
                int n = e.delete(EngineFunctional.AccUser.class)
                        .eq(EngineFunctional.AccUser::getName, "alice")
                        .remove();
                String cypher = g.lastCypher();
                if (!cypher.contains("DETACH DELETE n")) {
                    throw new EngineFunctional.HarnessFailure("缺少 DETACH DELETE: " + cypher);
                }
                if (n != 2) {
                    throw new EngineFunctional.HarnessFailure("期望返回 2，实际 " + n);
                }
                return cypher + " -> " + n;
            } finally {
                e.close();
            }
        });

        EngineFunctional.check("write.execute 位置参数转 $pN 且返回 counters 之和", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                g.nodesCreated = 2;
                g.nodesDeleted = 1;
                g.relCreated = 3;
                g.relDeleted = 4;
                int n = e.execute("CREATE (n:Test {id: $p0})", 5);
                if (!"CREATE (n:Test {id: $p0})".equals(g.lastCypher())) {
                    throw new EngineFunctional.HarnessFailure(
                            "Cypher 被改写: " + g.lastCypher());
                }
                if (!Integer.valueOf(5).equals(g.lastParams().get("p0"))) {
                    throw new EngineFunctional.HarnessFailure(
                            "位置参数未转为 p0: " + g.lastParams());
                }
                if (n != 10) {
                    throw new EngineFunctional.HarnessFailure(
                            "期望 counters 之和 10，实际 " + n);
                }
                return "counters=10";
            } finally {
                e.close();
            }
        });
    }

    // ==================== 失败语义（缺陷 25 回归锁） ====================

    private static void errorSuite() {
        EngineFunctional.emit("--- neo4j.error ---");

        EngineFunctional.check("error.未初始化：查询抛 ISE（不是空结果/NPE）", () -> {
            Neo4jEngine e = new Neo4jEngine();
            try {
                e.query(EngineFunctional.AccUser.class).list();
                throw new EngineFunctional.HarnessFailure("未初始化却返回了结果");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("驱动未初始化")) {
                    throw new EngineFunctional.HarnessFailure("消息不明确: " + expected.getMessage());
                }
                return expected.getMessage();
            }
        });

        EngineFunctional.check("error.驱动异常保留 Cypher 上下文与原始 cause", () -> {
            FakeGraph g = new FakeGraph();
            g.fail = new RuntimeException("simulated server failure");
            Neo4jEngine e = engine(g);
            try {
                try {
                    e.query(EngineFunctional.AccUser.class).list();
                    throw new EngineFunctional.HarnessFailure("驱动异常被吞成空结果");
                } catch (RuntimeException expected) {
                    String msg = expected.getMessage();
                    if (msg == null || !msg.contains("Neo4j 查询失败") || !msg.contains("t_acc_user")) {
                        throw new EngineFunctional.HarnessFailure("异常丢失 Cypher 上下文: " + msg);
                    }
                    if (expected.getCause() == null
                            || !"simulated server failure".equals(expected.getCause().getMessage())) {
                        throw new EngineFunctional.HarnessFailure("异常丢失原始 cause");
                    }
                    return msg;
                }
            } finally {
                e.close();
            }
        });
    }

    // ==================== 生命周期与能力声明 ====================

    private static void lifeSuite() {
        EngineFunctional.emit("--- neo4j.life ---");

        EngineFunctional.check("life.close() 不关闭注入的驱动（缺陷 24 回归锁）", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            e.close();
            if (g.driverClosed) {
                throw new EngineFunctional.HarnessFailure(
                        "关闭了调用方注入的驱动——外部持有的连接被擅自释放");
            }
            return "injected driver untouched";
        });

        EngineFunctional.check("life.close() 后查询抛 ISE，重复 close 不抛", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            e.close();
            e.close();
            try {
                e.query(EngineFunctional.AccUser.class).list();
                throw new EngineFunctional.HarnessFailure("close() 后仍可查询");
            } catch (IllegalStateException expected) {
                return "closed, then ISE";
            }
        });

        EngineFunctional.check("life.能力声明自洽：无执行器 / 无元数据 / querySql 抛 UOE", () -> {
            FakeGraph g = new FakeGraph();
            Neo4jEngine e = engine(g);
            try {
                if (e.getExecutor() != null) {
                    throw new EngineFunctional.HarnessFailure("getExecutor() 应为 null");
                }
                if (e.supportsSql()) {
                    throw new EngineFunctional.HarnessFailure("无执行器却声明 supportsSql=true");
                }
                if (e.supportsMeta()) {
                    throw new EngineFunctional.HarnessFailure("不支持元数据却声明 supportsMeta=true");
                }
                try {
                    e.querySql("MATCH (n) RETURN n");
                    throw new EngineFunctional.HarnessFailure("querySql 未抛异常");
                } catch (UnsupportedOperationException expected) {
                    return "supportsSql=false, supportsMeta=false";
                }
            } finally {
                e.close();
            }
        });
    }

    // ==================== 基础设施 ====================

    private static Neo4jEngine engine(FakeGraph graph) {
        Neo4jEngine engine = new Neo4jEngine();
        engine.addDataSource("default", new EngineDataSource<Driver>() {
            @Override
            public String name() {
                return "default";
            }

            @Override
            public Driver getSource() {
                return graph.driver;
            }

            @Override
            public EngineDataSource<Driver> setSource(Object source) {
                return this;
            }

            @Override
            public Dialect getDialect() {
                return null;
            }

            @Override
            public EngineDataSource<Driver> setDialect(Dialect dialect) {
                return this;
            }

            @Override
            public String url() {
                return "bolt://fake:7687";
            }

            @Override
            public String username() {
                return null;
            }

            @Override
            public String password() {
                return null;
            }
        });
        return engine;
    }

    private static String between(String text, String open, String close) {
        int from = text.indexOf(open);
        if (from < 0) {
            return "";
        }
        from += open.length();
        int to = text.indexOf(close, from);
        return to < 0 ? "" : text.substring(from, to);
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
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asParams(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 回放型假图数据库：记录每条 Cypher 与参数，按 Cypher 形态回放固定记录。
     *
     * <p>刻意<b>不</b>实现图语义（不解析 Cypher、不做匹配），
     * 只负责真实地接收请求、给出可预测的记录——断言因此聚焦在「引擎发了什么」。</p>
     */
    static final class FakeGraph {
        private static final ClassLoader CL = FakeGraph.class.getClassLoader();

        final List<String> cyphers = new ArrayList<>();
        final List<Map<String, Object>> params = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long total;
        int updated;
        int deleted;
        int nodesCreated;
        int nodesDeleted;
        int relCreated;
        int relDeleted;
        RuntimeException fail;
        boolean driverClosed;
        boolean sessionClosed;
        boolean committed;

        final Driver driver = (Driver) Proxy.newProxyInstance(CL,
                new Class<?>[]{Driver.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "session":
                            return session();
                        case "close":
                            driverClosed = true;
                            return null;
                        case "isEncrypted":
                            return false;
                        case "toString":
                            return "FakeDriver";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        String lastCypher() {
            return cyphers.isEmpty() ? "" : cyphers.get(cyphers.size() - 1);
        }

        Map<String, Object> lastParams() {
            return params.isEmpty() ? Map.of() : params.get(params.size() - 1);
        }

        private Session session() {
            return (Session) Proxy.newProxyInstance(CL, new Class<?>[]{Session.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "run":
                                return result((String) args[0],
                                        args.length > 1 ? asParams(args[1]) : Map.of());
                            case "beginTransaction":
                                return tx();
                            case "executeWrite":
                            case "executeRead":
                                return ((TransactionCallback<?>) args[0]).execute(txContext());
                            case "close":
                                sessionClosed = true;
                                return null;
                            case "isOpen":
                                return !sessionClosed;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private Transaction tx() {
            return (Transaction) Proxy.newProxyInstance(CL, new Class<?>[]{Transaction.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "run":
                                return result((String) args[0],
                                        args.length > 1 ? asParams(args[1]) : Map.of());
                            case "commit":
                                committed = true;
                                return null;
                            case "isOpen":
                                return true;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        /**
         * {@code TransactionContext} 与 {@code Transaction} 是两条并列的接口
         * （前者只有查询面 {@code SimpleQueryRunner}），{@code executeWrite} 的
         * 回调参数类型是前者，因此单独造一个。
         */
        private org.neo4j.driver.TransactionContext txContext() {
            return (org.neo4j.driver.TransactionContext) Proxy.newProxyInstance(CL,
                    new Class<?>[]{org.neo4j.driver.TransactionContext.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "run":
                                return result((String) args[0],
                                        args.length > 1 ? asParams(args[1]) : Map.of());
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private Result result(String cypher, Map<String, Object> parameters) {
            cyphers.add(cypher);
            params.add(parameters);
            if (fail != null) {
                throw fail;
            }
            List<Record> records = new ArrayList<>();
            if (cypher.contains("count(n) AS total")) {
                records.add(record(Map.of("total", total)));
            } else if (cypher.contains("count(n) AS updated")) {
                records.add(record(Map.of("updated", updated)));
            } else if (cypher.contains("count(n) AS deleted")) {
                records.add(record(Map.of("deleted", deleted)));
            } else if (cypher.contains("RETURN n")) {
                for (Map<String, Object> row : rows) {
                    records.add(record(Map.of("n", row)));
                }
            } else if (cypher.contains("RETURN 1 AS n")) {
                records.add(record(Map.of("n", 1)));
            }
            return (Result) Proxy.newProxyInstance(CL, new Class<?>[]{Result.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "list":
                                if (args == null || args.length == 0) {
                                    return new ArrayList<>(records);
                                }
                                Function<Record, Object> mapper = castFunction(args[0]);
                                List<Object> mapped = new ArrayList<>(records.size());
                                for (Record r : records) {
                                    mapped.add(mapper.apply(r));
                                }
                                return mapped;
                            case "single":
                                if (records.isEmpty()) {
                                    throw new RuntimeException("no record (fake)");
                                }
                                return records.get(0);
                            case "consume":
                                return summary();
                            case "hasNext":
                                return !records.isEmpty();
                            case "next":
                                if (records.isEmpty()) {
                                    throw new java.util.NoSuchElementException("fake");
                                }
                                return records.remove(0);
                            case "stream":
                                return records.stream();
                            case "isOpen":
                                return true;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        @SuppressWarnings("unchecked")
        private static Function<Record, Object> castFunction(Object value) {
            return (Function<Record, Object>) value;
        }

        private ResultSummary summary() {
            SummaryCounters counters = (SummaryCounters) Proxy.newProxyInstance(CL,
                    new Class<?>[]{SummaryCounters.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "nodesCreated":
                                return nodesCreated;
                            case "nodesDeleted":
                                return nodesDeleted;
                            case "relationshipsCreated":
                                return relCreated;
                            case "relationshipsDeleted":
                                return relDeleted;
                            case "containsUpdates":
                                return true;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
            return (ResultSummary) Proxy.newProxyInstance(CL,
                    new Class<?>[]{ResultSummary.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "counters":
                                return counters;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private static Record record(Map<String, Object> values) {
            return (Record) Proxy.newProxyInstance(CL, new Class<?>[]{Record.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "get":
                                if (args[0] instanceof Integer index) {
                                    String key = new ArrayList<>(values.keySet()).get(index);
                                    return value(values.get(key));
                                }
                                return value(values.get((String) args[0]));
                            case "asMap":
                                return new LinkedHashMap<>(values);
                            case "keys":
                                return new ArrayList<>(values.keySet());
                            case "values":
                                List<Value> all = new ArrayList<>();
                                for (Object v : values.values()) {
                                    all.add(value(v));
                                }
                                return all;
                            case "size":
                                return values.size();
                            case "index":
                                return new ArrayList<>(values.keySet()).indexOf(args[0]);
                            case "contains":
                                return values.containsKey(args[0]);
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private static Value value(Object raw) {
            return (Value) Proxy.newProxyInstance(CL, new Class<?>[]{Value.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "asMap":
                                return raw;
                            case "asInt":
                                return ((Number) raw).intValue();
                            case "asLong":
                                return ((Number) raw).longValue();
                            case "asDouble":
                                return ((Number) raw).doubleValue();
                            case "asString":
                                return String.valueOf(raw);
                            case "asBoolean":
                                return (Boolean) raw;
                            case "asObject":
                                return raw;
                            case "isNull":
                                return raw == null;
                            case "raw":
                                return raw;
                            case "toString":
                                return String.valueOf(raw);
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }
    }
}
