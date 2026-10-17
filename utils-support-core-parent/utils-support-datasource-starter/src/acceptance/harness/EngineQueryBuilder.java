import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.wrapper.SqlQueryBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 引擎验收运行器 —— 阶段七：{@code Engine.query()} 非 ORM SQL 文本构建器。
 *
 * <p>{@code Engine} 原来只有 {@code query(Class)} 的 Lambda 包装器与
 * {@code query(String, Object...)} 的原生语句两条通道，缺少"链式拼接但不做实体映射"
 * 的中间形态。{@code query()} 补上了它：返回 {@link SqlQueryBuilder}，把
 * select / table / view / innerJoin / leftJoin / rightJoin / where / and / or /
 * groupBy / having / orderByAsc / orderByDesc / limit / offset 拼成一条语句，
 * 再经引擎既有的 {@code querySql} 通道执行。</p>
 *
 * <p>三个套件分别覆盖：</p>
 * <ul>
 *   <li>{@code render} —— 纯渲染与守卫（不触库）：子句顺序、{@code *} 缺省、
 *       标识符白名单、参数绑定顺序、{@code and/or} 前置守卫、{@code limit/offset} 边界；</li>
 *   <li>{@code memory} —— {@code InMemoryEngine} 的真实执行（自带 SQL 解析器，
 *       支持 WHERE / ORDER BY / LIMIT [OFFSET] / 标量聚合）；</li>
 *   <li>{@code h2} / {@code sqlite} —— 真实 SQL 方言下的完整能力（JOIN / GROUP BY /
 *       HAVING / VIEW / 类型化映射），两个不同厂商各跑一遍以证明拼接结果与方言无关。</li>
 * </ul>
 *
 * <p>本机无外部服务依赖：H2 用内存库，SQLite 用文件库，内存引擎进程内。</p>
 */
public final class EngineQueryBuilder {

    /** 验收主表名。 */
    private static final String U = "t_acc_q_user";

    /** 验收关联表名。 */
    private static final String O = "t_acc_q_order";

    private EngineQueryBuilder() {
    }

    public static void main(String[] args) {
        EngineFunctional.emit("=".repeat(78));
        EngineFunctional.emit("  引擎验收 阶段七：Engine.query() 非 ORM SQL 文本构建器");
        EngineFunctional.emit("=".repeat(78));

        // Engine.create(键) 走 SPI 单例缓存：同一 JVM 内对同一键反复创建得到同一实例，
        // 该实例一旦 close()，后续 Engine.create(同一键) 会拿到已关闭的引擎。
        // 因此 render 与 memory 两个套件共用同一个内存引擎实例，最后统一关闭。
        Engine memory = Engine.create("memory");
        try {
            runQuietly("render", () -> renderSuite(memory));
            runQuietly("memory", () -> memorySuite(memory));
        } finally {
            closeQuietly(memory);
        }
        runQuietly("h2", EngineQueryBuilder::h2Suite);
        runQuietly("sqlite", EngineQueryBuilder::sqliteSuite);

        EngineFunctional.finishAndExit("EngineQueryBuilder-report.txt");
    }

    // ==================== 纯渲染与守卫 ====================

    /** 不触库：只验拼接结果、参数顺序与守卫语义。 */
    static void renderSuite(Engine e) {
        EngineFunctional.emit("--- query-render (纯渲染与守卫，不触库) ---");
        String p = "render.";
        {
            EngineFunctional.check(p + "engine.query() 返回构建器", () -> {
                SqlQueryBuilder q = e.query();
                if (q == null) {
                    throw new EngineFunctional.HarnessFailure("query() 不应返回 null");
                }
                return q.getClass().getSimpleName();
            });

            EngineFunctional.check(p + "全子句按 SQL 语法顺序渲染", () -> {
                String sql = e.query()
                        .select("u.id", "u.name", "o.amount")
                        .table(U, "u")
                        .innerJoin(O + " o", "u.id = o.user_id")
                        .where("u.age > ?", 18)
                        .and("u.age < ?", 60)
                        .or("u.name = ?", "alice")
                        .groupBy("u.id", "u.name", "o.amount")
                        .having("SUM(o.amount) > ?", 100)
                        .orderByDesc("o.amount")
                        .orderByAsc("u.id")
                        .limit(20)
                        .offset(5)
                        .sql();
                String want = "SELECT u.id, u.name, o.amount FROM " + U + " u"
                        + " INNER JOIN " + O + " o ON u.id = o.user_id"
                        + " WHERE u.age > ? AND u.age < ? OR u.name = ?"
                        + " GROUP BY u.id, u.name, o.amount"
                        + " HAVING SUM(o.amount) > ?"
                        + " ORDER BY o.amount DESC, u.id ASC"
                        + " LIMIT 20 OFFSET 5";
                if (!want.equals(sql)) {
                    throw new EngineFunctional.HarnessFailure("实际: " + sql);
                }
                return "ok";
            });

            EngineFunctional.check(p + "select 缺省为 *", () -> {
                String sql = e.query().table("t").sql();
                if (!"SELECT * FROM t".equals(sql)) {
                    throw new EngineFunctional.HarnessFailure(sql);
                }
                return sql;
            });

            EngineFunctional.check(p + "table 别名 / view 语义标注", () -> {
                if (!"SELECT * FROM t x".equals(e.query().table("t", "x").sql())) {
                    throw new EngineFunctional.HarnessFailure("table 别名渲染错误");
                }
                SqlQueryBuilder v = e.query().view("v_x");
                if (!"SELECT * FROM v_x".equals(v.sql()) || !"VIEW".equals(v.getSourceKind())) {
                    throw new EngineFunctional.HarnessFailure("view 渲染或标注错误");
                }
                return "ok";
            });

            EngineFunctional.check(p + "selectCount / selectFunc 渲染聚合", () -> {
                String sql = e.query().selectCount("total").selectFunc("SUM", "amount", "sum_amount")
                        .table(O).sql();
                if (!("SELECT COUNT(*) AS total, SUM(amount) AS sum_amount FROM " + O).equals(sql)) {
                    throw new EngineFunctional.HarnessFailure(sql);
                }
                return sql;
            });

            EngineFunctional.check(p + "未指定来源 sql() 抛 IllegalStateException", () -> {
                expectType(IllegalStateException.class, () -> e.query().select("a").sql());
                return "IllegalStateException";
            });

            EngineFunctional.check(p + "标识符注入被白名单拒绝", () -> {
                expectType(IllegalArgumentException.class, () -> e.query().table("t; DROP TABLE x"));
                expectType(IllegalArgumentException.class, () -> e.query().table("t --"));
                expectType(IllegalArgumentException.class, () -> e.query().select("1=1"));
                expectType(IllegalArgumentException.class, () -> e.query().table("t").orderBy("a; --"));
                expectType(IllegalArgumentException.class, () -> e.query().table("t").groupBy("a'b"));
                return "已拒绝";
            });

            EngineFunctional.check(p + "and/or 无首条件被拒", () -> {
                expectType(IllegalArgumentException.class, () -> e.query().and("a = ?"));
                expectType(IllegalArgumentException.class, () -> e.query().or("a = ?"));
                return "已拒绝";
            });

            EngineFunctional.check(p + "limit/offset 越界被拒", () -> {
                expectType(IllegalArgumentException.class, () -> e.query().limit(0));
                expectType(IllegalArgumentException.class, () -> e.query().limit(-1));
                expectType(IllegalArgumentException.class, () -> e.query().offset(-1));
                return "已拒绝";
            });

            EngineFunctional.check(p + "WHERE 参数先于 HAVING 参数", () -> {
                Object[] bp = e.query().table("t")
                        .where("a = ?", 1).and("b = ?", 2)
                        .groupBy("c").having("COUNT(*) > ?", 3).boundParams();
                if (bp.length != 3 || !Integer.valueOf(1).equals(bp[0])
                        || !Integer.valueOf(2).equals(bp[1]) || !Integer.valueOf(3).equals(bp[2])) {
                    throw new EngineFunctional.HarnessFailure(Arrays.toString(bp));
                }
                return "1,2,3";
            });
        }
    }

    // ==================== InMemoryEngine：真实执行 ====================

    static void memorySuite(Engine e) {
        EngineFunctional.emit("--- query:memory (InMemoryEngine，真实执行) ---");
        String p = "memory.";
        {
            e.store(U, List.of(
                    new EngineFunctional.AccUser(1L, "alice", 30),
                    new EngineFunctional.AccUser(2L, "bob", 25),
                    new EngineFunctional.AccUser(3L, "carol", 35),
                    new EngineFunctional.AccUser(4L, "dave", 40),
                    new EngineFunctional.AccUser(5L, "erin", 45)));

            EngineFunctional.check(p + "select+where+orderByAsc → list 4 行", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("id", "name", "age").table(U)
                        .where("age > ?", 26).orderByAsc("id").list();
                if (rows == null || rows.size() != 4) {
                    throw new EngineFunctional.HarnessFailure("期望 4，实际 " + size(rows));
                }
                return "rows=4";
            });

            EngineFunctional.check(p + "orderByDesc 首个 id=5", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("id", "name", "age").table(U)
                        .where("age > ?", 26).orderByDesc("id").list();
                Object first = col(rows.get(0), "id");
                if (!intEquals(first, 5)) {
                    throw new EngineFunctional.HarnessFailure("首个 id 期望 5，实际 " + first);
                }
                return "firstId=5";
            });

            EngineFunctional.check(p + "list(Class) 类型化映射", () -> {
                List<EngineFunctional.AccUser> list = e.query()
                        .select("id", "name", "age").table(U)
                        .where("name = ?", "alice").list(EngineFunctional.AccUser.class);
                if (list == null || list.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("期望 1，实际 " + size(list));
                }
                EngineFunctional.AccUser u = list.get(0);
                if (!Long.valueOf(1L).equals(u.getId()) || !"alice".equals(u.getName())
                        || !Integer.valueOf(30).equals(u.getAge())) {
                    throw new EngineFunctional.HarnessFailure("映射错误: " + u);
                }
                return u.toString();
            });

            EngineFunctional.check(p + "map() 首行 / map(Class) 首对象", () -> {
                Map<String, Object> row = e.query().select("id", "name", "age").table(U)
                        .where("name = ?", "bob").map();
                if (row == null || !"bob".equals(col(row, "name"))) {
                    throw new EngineFunctional.HarnessFailure("map 首行错误: " + row);
                }
                EngineFunctional.AccUser u = e.query().select("id", "name", "age").table(U)
                        .where("name = ?", "bob").map(EngineFunctional.AccUser.class);
                if (u == null || !Long.valueOf(2L).equals(u.getId())) {
                    throw new EngineFunctional.HarnessFailure("map(Class) 错误: " + u);
                }
                return "bob/2";
            });

            EngineFunctional.check(p + "execute() 返回命中行数", () -> {
                int n = e.query().table(U).where("age > ?", 26).execute();
                if (n != 4) {
                    throw new EngineFunctional.HarnessFailure("期望 4，实际 " + n);
                }
                return "hit=" + n;
            });

            EngineFunctional.check(p + "limit+offset 分页切片（ids 2,3）", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("id").table(U).orderByAsc("id").limit(2).offset(1).list();
                if (rows == null || rows.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("期望 2，实际 " + size(rows));
                }
                if (!intEquals(col(rows.get(0), "id"), 2) || !intEquals(col(rows.get(1), "id"), 3)) {
                    throw new EngineFunctional.HarnessFailure("切片错误: " + rows);
                }
                return "ids=2,3";
            });

            EngineFunctional.check(p + "and/or 组合条件", () -> {
                int n = e.query().table(U)
                        .where("age > ?", 26).and("age < ?", 45).or("name = ?", "erin")
                        .execute();
                // age>26 AND age<45 → alice/carol/dave；OR name='erin' → erin，合计 4
                if (n != 4) {
                    throw new EngineFunctional.HarnessFailure("期望 4（alice,carol,dave,erin），实际 " + n);
                }
                return "hit=4";
            });

            EngineFunctional.check(p + "listAsync 与 executeAsync 返回 Mono", () -> {
                var listMono = e.query().select("id", "name", "age").table(U).listAsync();
                if (listMono == null || listMono.block() == null || listMono.block().size() != 5) {
                    throw new EngineFunctional.HarnessFailure("listAsync 结果错误");
                }
                var countMono = e.query().table(U).where("age > ?", 26).executeAsync();
                if (countMono == null || !Integer.valueOf(4).equals(countMono.block())) {
                    throw new EngineFunctional.HarnessFailure("executeAsync 结果错误");
                }
                return "async-ok";
            });

            EngineFunctional.check(p + "终端不修改构建状态（可重复执行）", () -> {
                SqlQueryBuilder q = e.query().select("id", "name", "age").table(U).where("age > ?", 26);
                String before = q.sql();
                q.list();
                q.map();
                q.execute();
                if (!before.equals(q.sql())) {
                    throw new EngineFunctional.HarnessFailure("终端调用改变了渲染结果");
                }
                return "stable";
            });
        }
    }

    // ==================== H2：真实 SQL ====================

    static void h2Suite() {
        EngineFunctional.emit("--- query:h2 (H2Engine，真实 SQL) ---");
        String p = "h2.";
        Engine e;
        try {
            e = ((com.chua.h2.support.engine.H2Engine) Engine.create("h2"))
                    .addDataSource("default", "jdbc:h2:mem:accquery;DB_CLOSE_DELAY=-1");
        } catch (Throwable t) {
            EngineFunctional.recordFail(p + "初始化失败: " + EngineFunctional.chain(t));
            return;
        }
        try {
            seedSql(e);

            EngineFunctional.check(p + "select * → list 5 行", () -> {
                List<Map<String, Object>> rows = e.query().table(U).list();
                if (rows == null || rows.size() != 5) {
                    throw new EngineFunctional.HarnessFailure("期望 5，实际 " + size(rows));
                }
                return "rows=5";
            });

            EngineFunctional.check(p + "where+orderByDesc 首个 id=5", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("id", "name", "age").table(U)
                        .where("age > ?", 26).orderByDesc("id").list();
                if (rows == null || rows.size() != 4) {
                    throw new EngineFunctional.HarnessFailure("期望 4，实际 " + size(rows));
                }
                if (!intEquals(col(rows.get(0), "id"), 5)) {
                    throw new EngineFunctional.HarnessFailure("首个 id 期望 5");
                }
                return "rows=4 firstId=5";
            });

            EngineFunctional.check(p + "and/or 混排条件（优先括号语义）", () -> {
                int n = e.query().table(U)
                        .where("age > ?", 26).and("age < ?", 45).or("name = ?", "erin")
                        .execute();
                // age>26 AND age<45 → alice30/carol35/dave40；OR name='erin' → erin45，合计 4
                if (n != 4) {
                    throw new EngineFunctional.HarnessFailure("期望 4，实际 " + n);
                }
                return "hit=4";
            });

            EngineFunctional.check(p + "list(Class) 类型化映射（id 为 Long）", () -> {
                List<EngineFunctional.AccUser> list = e.query()
                        .select("id", "name", "age").table(U)
                        .where("name = ?", "alice").list(EngineFunctional.AccUser.class);
                if (list == null || list.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("期望 1，实际 " + size(list));
                }
                EngineFunctional.AccUser u = list.get(0);
                if (!Long.valueOf(1L).equals(u.getId()) || !"alice".equals(u.getName())
                        || !Integer.valueOf(30).equals(u.getAge())) {
                    throw new EngineFunctional.HarnessFailure("映射错误: " + u);
                }
                return u.toString();
            });

            EngineFunctional.check(p + "map() / map(Class) 首行", () -> {
                Map<String, Object> row = e.query().table(U).where("name = ?", "carol").map();
                if (row == null || !"carol".equals(col(row, "name"))) {
                    throw new EngineFunctional.HarnessFailure("map 错误: " + row);
                }
                EngineFunctional.AccUser u = e.query().select("id", "name", "age").table(U)
                        .where("name = ?", "carol").map(EngineFunctional.AccUser.class);
                if (u == null || !Long.valueOf(3L).equals(u.getId())) {
                    throw new EngineFunctional.HarnessFailure("map(Class) 错误: " + u);
                }
                return "carol/3";
            });

            EngineFunctional.check(p + "limit+offset 分页（ids 2,3）", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("id").table(U).orderByAsc("id").limit(2).offset(1).list();
                if (rows == null || rows.size() != 2
                        || !intEquals(col(rows.get(0), "id"), 2)
                        || !intEquals(col(rows.get(1), "id"), 3)) {
                    throw new EngineFunctional.HarnessFailure("切片错误: " + rows);
                }
                return "ids=2,3";
            });

            EngineFunctional.check(p + "innerJoin 取最高金额订单（carol/300）", () -> {
                Map<String, Object> row = e.query()
                        .select("u.name", "o.amount").table(U, "u")
                        .innerJoin(O + " o", "u.id = o.user_id")
                        .where("o.amount > ?", 60)
                        .orderByDesc("o.amount").limit(1).map();
                if (row == null || !"carol".equals(col(row, "name")) || !intEquals(col(row, "amount"), 300)) {
                    throw new EngineFunctional.HarnessFailure("join 结果错误: " + row);
                }
                return "carol/300";
            });

            EngineFunctional.check(p + "leftJoin 取无订单用户（dave,erin）", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("u.name").table(U, "u")
                        .leftJoin(O + " o", "u.id = o.user_id")
                        .where("o.id IS NULL").orderByAsc("u.name").list();
                if (rows == null || rows.size() != 2) {
                    throw new EngineFunctional.HarnessFailure("期望 2 无订单用户，实际 " + size(rows));
                }
                if (!"dave".equals(col(rows.get(0), "name")) || !"erin".equals(col(rows.get(1), "name"))) {
                    throw new EngineFunctional.HarnessFailure("结果错误: " + rows);
                }
                return "dave,erin";
            });

            EngineFunctional.check(p + "groupBy+having（消费≥2 单的用户）", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("user_id").selectCount("cnt").table(O)
                        .groupBy("user_id").having("COUNT(*) >= ?", 2)
                        .orderByAsc("user_id").list();
                if (rows == null || rows.size() != 1) {
                    throw new EngineFunctional.HarnessFailure("期望 1 行，实际 " + size(rows));
                }
                if (!intEquals(col(rows.get(0), "user_id"), 1) || !intEquals(col(rows.get(0), "cnt"), 2)) {
                    throw new EngineFunctional.HarnessFailure("聚合结果错误: " + rows.get(0));
                }
                return "user_id=1,cnt=2";
            });

            EngineFunctional.check(p + "selectFunc 聚合别名（SUM→total）", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("user_id").selectFunc("SUM", "amount", "total").table(O)
                        .groupBy("user_id").orderByAsc("user_id").list();
                if (rows == null || rows.size() != 3) {
                    throw new EngineFunctional.HarnessFailure("期望 3 行，实际 " + size(rows));
                }
                if (!intEquals(col(rows.get(0), "total"), 300)) {
                    throw new EngineFunctional.HarnessFailure("SUM 别名取值错误: " + rows.get(0));
                }
                return "sum0=300";
            });

            EngineFunctional.check(p + "view 作为来源", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("id", "name").view("v_acc_q_elder").orderByAsc("id").list();
                if (rows == null || rows.size() != 3) {
                    throw new EngineFunctional.HarnessFailure("期望 3，实际 " + size(rows));
                }
                if (!"carol".equals(col(rows.get(0), "name"))) {
                    throw new EngineFunctional.HarnessFailure("view 结果错误: " + rows.get(0));
                }
                return "rows=3";
            });

            EngineFunctional.check(p + "listAsync 真实执行", () -> {
                var mono = e.query().select("id", "name", "age").table(U).orderByAsc("id").listAsync();
                if (mono == null || mono.block() == null || mono.block().size() != 5) {
                    throw new EngineFunctional.HarnessFailure("listAsync 结果错误");
                }
                return "async-ok";
            });
        } finally {
            closeQuietly(e);
        }
    }

    // ==================== SQLite：第二方言 ====================

    static void sqliteSuite() {
        EngineFunctional.emit("--- query:sqlite (SqliteEngine，第二方言) ---");
        String p = "sqlite.";
        Engine e;
        try {
            e = ((com.chua.sqlite.support.engine.SqliteEngine) Engine.create("sqlite"))
                    .addDataSource("default", "target/acc-query.sqlite");
        } catch (Throwable t) {
            EngineFunctional.recordFail(p + "初始化失败: " + EngineFunctional.chain(t));
            return;
        }
        try {
            seedSql(e);

            EngineFunctional.check(p + "select+where+orderByDesc", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("id", "name", "age").table(U)
                        .where("age > ?", 26).orderByDesc("id").list();
                if (rows == null || rows.size() != 4 || !intEquals(col(rows.get(0), "id"), 5)) {
                    throw new EngineFunctional.HarnessFailure("结果错误: " + size(rows));
                }
                return "rows=4";
            });

            EngineFunctional.check(p + "innerJoin 聚合", () -> {
                Map<String, Object> row = e.query()
                        .select("u.name").selectFunc("SUM", "o.amount", "total")
                        .table(U, "u").innerJoin(O + " o", "u.id = o.user_id")
                        .groupBy("u.name").having("SUM(o.amount) > ?", 100)
                        .orderByDesc("total").limit(1).map();
                if (row == null || !intEquals(col(row, "total"), 300)) {
                    throw new EngineFunctional.HarnessFailure("结果错误: " + row);
                }
                return "total=" + col(row, "total");
            });

            EngineFunctional.check(p + "list(Class) 类型化映射", () -> {
                List<EngineFunctional.AccUser> list = e.query()
                        .select("id", "name", "age").table(U)
                        .where("age > ?", 26).orderByAsc("id").list(EngineFunctional.AccUser.class);
                if (list == null || list.size() != 4 || !"alice".equals(list.get(0).getName())) {
                    throw new EngineFunctional.HarnessFailure("映射错误: " + list);
                }
                return "size=4 first=alice";
            });

            EngineFunctional.check(p + "limit+offset 分页", () -> {
                List<Map<String, Object>> rows = e.query()
                        .select("id").table(U).orderByAsc("id").limit(2).offset(1).list();
                if (rows == null || rows.size() != 2
                        || !intEquals(col(rows.get(0), "id"), 2)
                        || !intEquals(col(rows.get(1), "id"), 3)) {
                    throw new EngineFunctional.HarnessFailure("切片错误: " + rows);
                }
                return "ids=2,3";
            });

            EngineFunctional.check(p + "execute() 命中行数", () -> {
                int n = e.query().table(U).where("age > ?", 26).execute();
                if (n != 4) {
                    throw new EngineFunctional.HarnessFailure("期望 4，实际 " + n);
                }
                return "hit=4";
            });

            EngineFunctional.check(p + "and/or 条件", () -> {
                int n = e.query().table(U)
                        .where("age > ?", 26).and("age < ?", 45).or("name = ?", "erin").execute();
                if (n != 4) {
                    throw new EngineFunctional.HarnessFailure("期望 4，实际 " + n);
                }
                return "hit=4";
            });
        } finally {
            closeQuietly(e);
        }
    }

    // ==================== 公共装配 ====================

    /** 建表、插数与视图：SQL 方言下两个套件共用。 */
    private static void seedSql(Engine e) {
        e.executeSql("DROP TABLE IF EXISTS " + U);
        e.executeSql("CREATE TABLE " + U + " (id INT, name VARCHAR(64), age INT)");
        e.executeSql("INSERT INTO " + U + " (id, name, age) VALUES (?, ?, ?)", 1, "alice", 30);
        e.executeSql("INSERT INTO " + U + " (id, name, age) VALUES (?, ?, ?)", 2, "bob", 25);
        e.executeSql("INSERT INTO " + U + " (id, name, age) VALUES (?, ?, ?)", 3, "carol", 35);
        e.executeSql("INSERT INTO " + U + " (id, name, age) VALUES (?, ?, ?)", 4, "dave", 40);
        e.executeSql("INSERT INTO " + U + " (id, name, age) VALUES (?, ?, ?)", 5, "erin", 45);
        e.executeSql("DROP TABLE IF EXISTS " + O);
        e.executeSql("CREATE TABLE " + O + " (id INT, user_id INT, amount INT)");
        e.executeSql("INSERT INTO " + O + " (id, user_id, amount) VALUES (?, ?, ?)", 1, 1, 100);
        e.executeSql("INSERT INTO " + O + " (id, user_id, amount) VALUES (?, ?, ?)", 2, 1, 200);
        e.executeSql("INSERT INTO " + O + " (id, user_id, amount) VALUES (?, ?, ?)", 3, 2, 50);
        e.executeSql("INSERT INTO " + O + " (id, user_id, amount) VALUES (?, ?, ?)", 4, 3, 300);
        try {
            e.executeSql("DROP VIEW IF EXISTS v_acc_q_elder");
        } catch (RuntimeException ignored) {
            // SQLite 老版本对 DROP VIEW IF EXISTS 支持不一致，忽略
        }
        e.executeSql("CREATE VIEW v_acc_q_elder AS SELECT id, name, age FROM " + U + " WHERE age >= 35");
    }

    // ==================== 辅助 ====================

    /** 期望抛出指定类型异常。 */
    private static void expectType(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new EngineFunctional.HarnessFailure(
                    "期望 " + type.getSimpleName() + "，实际 " + EngineFunctional.chain(t));
        }
        throw new EngineFunctional.HarnessFailure("未抛 " + type.getSimpleName());
    }

    /** 大小写不敏感的列取值：H2 返回大写标签，SQLite/内存返回原样。 */
    private static Object col(Map<String, Object> row, String name) {
        if (row == null) {
            return null;
        }
        for (Map.Entry<String, Object> en : row.entrySet()) {
            if (en.getKey() != null && en.getKey().equalsIgnoreCase(name)) {
                return en.getValue();
            }
        }
        return null;
    }

    /** 数值宽松比较：不同驱动可能回传 Integer/Long/BigDecimal。 */
    private static boolean intEquals(Object value, int expect) {
        return value instanceof Number num && num.longValue() == expect;
    }

    private static int size(List<?> list) {
        return list == null ? -1 : list.size();
    }

    private static void closeQuietly(Engine e) {
        try {
            e.close();
        } catch (Throwable ignored) {
            // 关闭语义已在阶段一覆盖
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
