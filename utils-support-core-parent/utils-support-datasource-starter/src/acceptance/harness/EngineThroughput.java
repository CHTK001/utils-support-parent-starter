// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package harness;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.engine.DefaultReactorEngine;
import com.chua.datasource.support.engine.InMemoryEngine;
import com.chua.datasource.support.engine.ReactorEngine;
import com.chua.h2.support.engine.H2Engine;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 引擎验收 —— 阶段二十五（1.3 吞吐与并发）：同步/异步吞吐对比、批量写入增益、并发支持、索引随写失效。
 *
 * <p>覆盖 1.2 实施的三处优化：
 * <ol>
 *   <li>{@code JdbcEngine.batchSql} 覆写 —— H2 上对比「批量 3000 行 vs 逐条 3000 行」，断言批量更快且结果正确；</li>
 *   <li>{@code InMemoryEngine} 索引随写失效 —— store 后查询命中、executeSql 更新后索引查询命中新值；</li>
 *   <li>{@code ReactorEngine} 便捷 default 方法 —— queryOne/queryValue/count/exists/queryPage 与同步口径一致；
 *       {@code DefaultReactorEngine} 包装同步引擎后同步/异步结果一致。</li>
 * </ol>
 * </p>
 *
 * <p>退出码 = 失败数（0 表示全通过）。结果以 UTF-8 落盘 EngineThroughput-report.txt。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class EngineThroughput {

    /** 验收用实体：表名显式钉死，避免依赖驼峰转换规则。 */
    @TableName("t_idx")
    public static class AccUser {
        private Long id;
        private String name;
        private Integer age;

        public AccUser() {
        }

        public AccUser(Long id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
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

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    /** 语法错误标记，与引擎自身异常区分。 */
    static final class HarnessFailure extends RuntimeException {
        HarnessFailure(String message) {
            super(message);
        }
    }

    private static int pass = 0;
    private static int fail = 0;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> OUT = new ArrayList<>();

    private EngineThroughput() {
    }

    static void emit(String line) {
        OUT.add(line);
        System.out.println(line); // [P3C E1 豁免]
    }

    public static void main(String[] args) {
        emit("=".repeat(78));
        emit("  引擎验收 阶段二十五（1.3）：吞吐 / 批量 / 并发 / 便捷方法");
        emit("=".repeat(78));

        long t0 = System.nanoTime();
        try {
            throughputH2();
            throughputMemory();
            convenienceMethods();
            concurrency();
        } catch (Throwable t) {
            recordFail("top-level failure -> " + chain(t));
        }
        long t1 = System.nanoTime();
        emit("");
        emit("wall clock: " + String.format("%.2f", (t1 - t0) / 1e9) + " s");
        finishAndExit("EngineThroughput-report.txt");
    }

    /** 吞吐对比：H2 上批量 vs 逐条（batchSql 覆写）。 */
    private static void throughputH2() throws Exception {
        H2Engine e = new H2Engine();
        e.addDataSource("default", "jdbc:h2:mem:thr;DB_CLOSE_DELAY=-1");
        emit("== 场景 A：H2 批量 vs 逐条（batchSql 覆写） ==");
        e.executeSql("DROP TABLE IF EXISTS t_thr");
        e.executeSql("CREATE TABLE t_thr (id INT, name VARCHAR(64), age INT)");

        int n = 3000;
        List<Object[]> params = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            params.add(new Object[]{i, "u" + i, i % 80});
        }
        int[] batchAffected = e.batchSql("INSERT INTO t_thr (id, name, age) VALUES (?, ?, ?)", params);
        check("A1.H2.batchSql(3000) 受影响行数全部为 1", () -> {
            int sum = 0;
            for (int a : batchAffected) {
                if (a != 1) {
                    throw new HarnessFailure("affected=" + a);
                }
                sum += a;
            }
            return "sum=" + sum;
        });
        check("A2.H2.batchSql(3000) 后 COUNT=3000", () -> {
            Long c = (Long) e.queryValue("SELECT COUNT(*) FROM t_thr");
            if (c == null || c != 3000) {
                throw new HarnessFailure("count=" + c);
            }
            return "count=" + c;
        });
        check("A3.H2.batchSql(空列表) 返回空数组", () -> {
            int[] r = e.batchSql("INSERT INTO t_thr (id, name, age) VALUES (?, ?, ?)", List.of());
            if (r.length != 0) {
                throw new HarnessFailure("length=" + r.length);
            }
            return "ok";
        });

        // 逐条基线：清空后 3000 条逐条 executeSql（每条独立连接+提交）
        e.executeSql("DELETE FROM t_thr");
        long tIter = System.nanoTime();
        for (Object[] p : params) {
            e.executeSql("INSERT INTO t_thr (id, name, age) VALUES (?, ?, ?)", p);
        }
        long iterMs = (System.nanoTime() - tIter) / 1_000_000;

        e.executeSql("DELETE FROM t_thr");
        long tBatch = System.nanoTime();
        e.batchSql("INSERT INTO t_thr (id, name, age) VALUES (?, ?, ?)", params);
        long batchMs = (System.nanoTime() - tBatch) / 1_000_000;

        emit("  [info] 逐条 3000 行 = " + iterMs + " ms; 批量 3000 行 = " + batchMs + " ms");
        check("A4.H2.批量比逐条更快", () -> {
            if (batchMs >= iterMs) {
                throw new HarnessFailure("batch=" + batchMs + "ms >= iter=" + iterMs + "ms");
            }
            return "iter=" + iterMs + "ms batch=" + batchMs + "ms (batch " + (iterMs * 100 / Math.max(1, batchMs)) + "% of iter)";
        });
        check("A5.H2.批量后 COUNT=3000（无丢失无重复）", () -> {
            Long c = (Long) e.queryValue("SELECT COUNT(*) FROM t_thr");
            if (c == null || c != 3000) {
                throw new HarnessFailure("count=" + c);
            }
            return "count=" + c;
        });
    }

    /** 内存引擎：索引随写失效（Lambda 链式才是 tryIndexLookup 真索引路径）。 */
    private static void throughputMemory() throws Exception {
        InMemoryEngine e = (InMemoryEngine) Engine.create("memory");
        emit("== 场景 B：InMemoryEngine 索引随写失效（Lambda 链式路径） ==");
        e.store("t_idx", List.of(new AccUser(1L, "alice", 30), new AccUser(2L, "bob", 25)));
        e.index();
        check("B1.store+index 后 Lambda 等值查询命中", () -> {
            AccUser u = e.query(AccUser.class).eq(AccUser::getId, 1L).one();
            if (u == null || !"alice".equals(u.getName()) || u.getAge() != 30) {
                throw new HarnessFailure("u=" + u);
            }
            return "id=" + u.getId() + " name=" + u.getName();
        });
        // 内存通道 insert → afterMemoryInsert 失效，新 id 若仍走旧索引会漏命中
        e.insert(AccUser.class, new AccUser(3L, "carol", 40));
        check("B2.insert 内存通道后 Lambda 按新 id 命中", () -> {
            AccUser u = e.query(AccUser.class).eq(AccUser::getId, 3L).one();
            if (u == null || !"carol".equals(u.getName())) {
                throw new HarnessFailure("u=" + u);
            }
            return "id=3 hit";
        });
        // 链式 update → evaluateUpdate 失效，按新值 age=99 查询必须命中
        int affected = e.update(AccUser.class).set(AccUser::getAge, 99)
                .eq(AccUser::getName, "alice").update();
        check("B3.Lambda update 受影响=1 且按 age=99 命中", () -> {
            if (affected != 1) {
                throw new HarnessFailure("affected=" + affected);
            }
            AccUser u = e.query(AccUser.class).eq(AccUser::getAge, 99).one();
            if (u == null || !"alice".equals(u.getName())) {
                throw new HarnessFailure("u=" + u);
            }
            return "alice age=99";
        });
        // 原生 SQL DML → executeSql 先失效对应表索引（line 267）
        e.executeSql("UPDATE t_idx SET age = 55 WHERE name = 'carol'");
        check("B4.executeSql UPDATE 后 Lambda 按新值 age=55 命中", () -> {
            AccUser u = e.query(AccUser.class).eq(AccUser::getAge, 55).one();
            if (u == null || !"carol".equals(u.getName())) {
                throw new HarnessFailure("u=" + u);
            }
            return "carol age=55";
        });
        // 链式 delete → evaluateDelete 失效，旧索引指向被替换集合会产生悬空命中
        int removed = e.delete(AccUser.class).eq(AccUser::getName, "bob").remove();
        check("B5.Lambda delete 受影响=1 且按已删值查询为空", () -> {
            if (removed != 1) {
                throw new HarnessFailure("removed=" + removed);
            }
            List<AccUser> list = e.query(AccUser.class).eq(AccUser::getName, "bob").list();
            if (list != null && !list.isEmpty()) {
                throw new HarnessFailure("bob still hit=" + list.size());
            }
            return "bob gone";
        });
        // 原生 SQL DELETE 同样先失效（executeSql line 267）
        e.executeSql("DELETE FROM t_idx WHERE name = 'alice'");
        check("B6.executeSql DELETE 后 Lambda 按已删值查询为空", () -> {
            List<AccUser> list = e.query(AccUser.class).eq(AccUser::getName, "alice").list();
            if (list != null && !list.isEmpty()) {
                throw new HarnessFailure("alice still hit=" + list.size());
            }
            return "alice gone";
        });
    }

    /** ReactorEngine 便捷方法与同步对齐。 */
    private static void convenienceMethods() throws Exception {
        H2Engine sync = new H2Engine();
        sync.addDataSource("default", "jdbc:h2:mem:conv;DB_CLOSE_DELAY=-1");
        sync.executeSql("DROP TABLE IF EXISTS t_conv");
        sync.executeSql("CREATE TABLE t_conv (id INT, name VARCHAR(64), age INT)");
        sync.executeSql("INSERT INTO t_conv (id, name, age) VALUES (?, ?, ?)", 1, "alice", 30);
        sync.executeSql("INSERT INTO t_conv (id, name, age) VALUES (?, ?, ?)", 2, "bob", 25);
        sync.executeSql("INSERT INTO t_conv (id, name, age) VALUES (?, ?, ?)", 3, "carol", 35);
        ReactorEngine re = new DefaultReactorEngine(sync);

        emit("== 场景 C：ReactorEngine 便捷 default 方法 ==");
        Mono<AccUser> one = re.queryOne("SELECT id, name, age FROM t_conv WHERE id = 2", AccUser.class);
        check("C1.reactor.queryOne 命中", () -> {
            AccUser u = one.block();
            if (u == null || !"bob".equals(u.getName())) {
                throw new HarnessFailure("queryOne=" + u);
            }
            return "name=" + u.getName();
        });
        Mono<Object> scalar = re.queryValue("SELECT MAX(age) FROM t_conv");
        check("C2.reactor.queryValue 取首行首列", () -> {
            Object v = scalar.block();
            if (v == null || !"35".equals(String.valueOf(v))) {
                throw new HarnessFailure("queryValue=" + v);
            }
            return "maxAge=" + v;
        });
        Mono<Long> cnt = re.count("SELECT COUNT(*) FROM t_conv");
        check("C3.reactor.count 与同步一致", () -> {
            long c = cnt.block();
            if (c != 3) {
                throw new HarnessFailure("count=" + c);
            }
            return "count=" + c;
        });
        Mono<Boolean> ex = re.exists("SELECT id FROM t_conv WHERE id = 99");
        check("C4.reactor.exists 无命中=false", () -> {
            if (Boolean.TRUE.equals(ex.block())) {
                throw new HarnessFailure("exists=true");
            }
            return "exists=false";
        });
        Flux<Map<String, Object>> page = re.queryPage("SELECT id, name, age FROM t_conv ORDER BY id", 2, 2);
        check("C5.reactor.queryPage 第 2 页 = 1 行首列=3", () -> {
            List<Map<String, Object>> rows = page.collectList().block();
            if (rows == null || rows.size() != 1) {
                throw new HarnessFailure("pageSize=" + (rows == null ? -1 : rows.size()));
            }
            Map<String, Object> row = rows.get(0);
            Object first = row == null ? null : row.values().iterator().next();
            if (!String.valueOf(first).equals("3")) {
                throw new HarnessFailure("first=" + first);
            }
            return "first=" + first;
        });
        Mono<AccUser> missing = re.queryOne("SELECT id, name, age FROM t_conv WHERE id = 999", AccUser.class);
        check("C6.reactor.queryOne 无命中=empty", () -> {
            if (missing.block() != null) {
                throw new HarnessFailure("expected empty");
            }
            return "empty";
        });
        Mono<Boolean> ex2 = re.exists("SELECT id FROM t_conv WHERE id = 2");
        check("C7.reactor.exists 命中=true", () -> {
            if (!Boolean.TRUE.equals(ex2.block())) {
                throw new HarnessFailure("exists=false");
            }
            return "exists=true";
        });
        Flux<Map<String, Object>> badPage = re.queryPage("SELECT id FROM t_conv", 0, 10);
        check("C8.reactor.queryPage(pageNum=0) 抛 IllegalArgumentException", () -> {
            try {
                badPage.collectList().block();
                throw new HarnessFailure("expected error, got empty");
            } catch (IllegalArgumentException e2) {
                return "IllegalArgumentException: " + e2.getMessage();
            }
        });
        Flux<Map<String, Object>> badPage2 = re.queryPage("SELECT id FROM t_conv", 1, 0);
        check("C9.reactor.queryPage(pageSize=0) 抛 IllegalArgumentException", () -> {
            try {
                badPage2.collectList().block();
                throw new HarnessFailure("expected error, got empty");
            } catch (IllegalArgumentException e2) {
                return "IllegalArgumentException: " + e2.getMessage();
            }
        });
    }

    /** 并发读写。 */
    private static void concurrency() throws Exception {
        emit("== 场景 D：并发读写 ==");
        H2Engine e = new H2Engine();
        e.addDataSource("default", "jdbc:h2:mem:cc;DB_CLOSE_DELAY=-1");
        e.executeSql("DROP TABLE IF EXISTS t_cc");
        e.executeSql("CREATE TABLE t_cc (id INT, name VARCHAR(64), age INT)");
        int threads = 8;
        int perThread = 200;
        AtomicInteger seq = new AtomicInteger(1);
        AtomicReference<Throwable> failRef = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> ths = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int id = seq.getAndIncrement();
                        e.executeSql("INSERT INTO t_cc (id, name, age) VALUES (?, ?, ?)", id, "w" + id, id % 80);
                    }
                } catch (Throwable t2) {
                    failRef.compareAndSet(null, t2);
                } finally {
                    done.countDown();
                }
            }, "w" + t);
            ths.add(th);
            th.start();
        }
        start.countDown();
        check("D1.8 线程并发写入不抛异常", () -> {
            if (!done.await(60, TimeUnit.SECONDS)) {
                throw new HarnessFailure("timeout");
            }
            Throwable t2 = failRef.get();
            if (t2 != null) {
                throw new HarnessFailure("thread failure -> " + chain(t2));
            }
            return "threads ok";
        });
        check("D2.并发写入总行数=threads*perThread", () -> {
            Long c = (Long) e.queryValue("SELECT COUNT(*) FROM t_cc");
            if (c == null || c != (long) threads * perThread) {
                throw new HarnessFailure("count=" + c);
            }
            return "count=" + c;
        });
        check("D3.并发写入无重复 id", () -> {
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (Map<String, Object> row : e.querySql("SELECT id FROM t_cc")) {
                // H2 列标签默认大写，键名大小写不敏感取列，避免 harness 依赖数据库大小写
                int id = ((Number) rowValue(row, "id")).intValue();
                if (!ids.add(id)) {
                    throw new HarnessFailure("duplicate id=" + id);
                }
            }
            return "distinct=" + ids.size();
        });
    }

    static Object rowValue(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object v = row.get(key);
        if (v != null) {
            return v;
        }
        for (Map.Entry<String, Object> en : row.entrySet()) {
            if (key.equalsIgnoreCase(en.getKey())) {
                return en.getValue();
            }
        }
        return null;
    }

    static void check(String name, Probe probe) {
        try {
            String detail = probe.run();
            pass++;
            emit("  [PASS] " + name + (detail == null || detail.isEmpty() ? "" : "  -> " + detail));
        } catch (Throwable t) {
            recordFail(name + "  -> " + chain(t));
        }
    }

    static void recordFail(String message) {
        fail++;
        FAILURES.add(message);
        emit("  [FAIL] " + message);
    }

    static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) {
                sb.append(" -> ");
            }
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append(": ").append(cur.getMessage());
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    static void finishAndExit(String reportFile) {
        emit("=".repeat(78));
        emit("  结果: PASS=" + pass + "  FAIL=" + fail);
        for (String f : FAILURES) {
            emit("    [FAIL] " + f);
        }
        emit("=".repeat(78));
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(reportFile),
                    String.join(System.lineSeparator(), OUT) + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("报告写盘失败: " + e); // [P3C E1 豁免]
        }
        // [P3C SE-安全 豁免]
        System.exit(fail);
    }

    interface Probe {
        String run() throws Exception;
    }
}